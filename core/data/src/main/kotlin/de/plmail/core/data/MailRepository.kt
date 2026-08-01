package de.plmail.core.data

import androidx.room.withTransaction
import de.plmail.core.database.AccountEntity
import de.plmail.core.database.EmailBodyEntity
import de.plmail.core.database.EmailEntity
import de.plmail.core.database.MailboxEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.database.ThreadEntity
import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.Identity
import de.plmail.jmap.mail.MailThread
import de.plmail.jmap.mail.Mailbox
import de.plmail.jmap.protocol.Session
import de.plmail.jmap.protocol.ThreadId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * The cache, written to and read from.
 *
 * Everything here is a projection of the server. Nothing invents a row, nothing merges a partial
 * update onto an existing one, and nothing stores a value the server could not hand back — that
 * rule is what licenses the database's destructive-migration policy, and it stays true only as long
 * as this file keeps it.
 */
@Singleton
class MailRepository @Inject constructor(private val database: PlMailDatabase) {

    fun observeAccounts(): Flow<List<AccountEntity>> = database.accounts().observeAll()

    fun observeMailboxes(): Flow<List<MailboxEntity>> = database.mailboxes().observeAll()

    fun observeThread(uid: String): Flow<ThreadEntity?> = database.threads().observe(uid)

    /**
     * Brings the account list in line with a freshly fetched Session.
     *
     * Accounts the session no longer lists are deleted rather than left behind: a credential that
     * has lost access to a mailbox should stop showing it, and a stale row would keep it in the
     * sidebar forever with a sync that quietly fails.
     *
     * The existing sync cursors are deliberately *not* touched. They live on the same row, and
     * rewriting them here would silently re-page every account on every launch.
     */
    suspend fun replaceAccounts(server: String, session: Session) {
        val accounts =
            session.accountIds.mapIndexedNotNull { index, id ->
                session.account(id)?.toEntity(server, id.value, sortIndex = index)
            }

        database.withTransaction {
            val existing = database.accounts().all().associateBy { it.uid }

            database
                .accounts()
                .upsert(
                    accounts.map { fresh ->
                        // Carried forward explicitly rather than by omission:
                        // Room's upsert replaces the whole row, so a mapper that
                        // does not know about cursors would reset them to null.
                        existing[fresh.uid]?.let {
                            fresh.copy(
                                emailState = it.emailState,
                                threadState = it.threadState,
                                mailboxState = it.mailboxState,
                                lastSyncedAt = it.lastSyncedAt,
                                lastSyncError = it.lastSyncError,
                            )
                        } ?: fresh
                    }
                )

            database.accounts().deleteMissing(accounts.map { it.uid })
        }
    }

    /**
     * Replaces one account's mailboxes.
     *
     * A full replace rather than an upsert because a label deleted on the server has to disappear
     * here; an upsert alone would leave it in the sidebar, and tapping it would query a binding
     * that no longer exists.
     */
    suspend fun replaceMailboxes(accountKey: String, mailboxes: List<Mailbox>) {
        val fresh = mailboxes.map { it.toEntity(accountKey) }

        database.withTransaction {
            val stale =
                database.mailboxes().forAccount(accountKey).map { it.uid } -
                    fresh.map { it.uid }.toSet()

            database.mailboxes().upsert(fresh)
            database.mailboxes().delete(stale)
        }
    }

    suspend fun replaceIdentities(accountKey: String, identities: List<Identity>) {
        database.withTransaction {
            database.identities().deleteForAccount(accountKey)
            database
                .identities()
                .upsert(identities.mapIndexed { index, it -> it.toEntity(accountKey, index) })
        }
    }

    /**
     * Writes a page of messages and the threads they belong to.
     *
     * One transaction for both, because a thread row is derived from its messages: committing the
     * summary without the messages it summarises would leave a list row describing mail that is not
     * in the database, which a reader tapping it would open as empty.
     *
     * Threads are summarised from the messages **already stored plus the ones arriving**, not from
     * the arriving page alone. A page carrying one reply to a long conversation would otherwise
     * rewrite that conversation's row as a one-message thread from a single participant.
     */
    suspend fun storeEmails(
        accountKey: String,
        emails: List<Email>,
        threads: List<MailThread> = emptyList(),
        fetchedAt: Long,
    ) {
        if (emails.isEmpty() && threads.isEmpty()) return

        database.withTransaction {
            database.emails().upsert(emails.map { it.toEntity(accountKey) })

            emails.forEach { email ->
                email.toBodyEntity(accountKey, fetchedAt)?.let { database.emails().upsertBody(it) }

                val attachments = email.toAttachmentEntities(accountKey)
                if (attachments.isNotEmpty()) database.emails().upsertAttachments(attachments)
            }

            // Every thread the page touched, whether or not a Thread/get came
            // with it. A list page fetches messages alone -- one per
            // conversation -- so requiring the Thread object here would leave
            // the rows those messages belong to unsummarised, and the list
            // reading from them empty.
            val fetched = threads.associateBy { it.id.value }
            val touched = (fetched.keys + emails.mapNotNull { it.threadId?.value }).toSet()

            // Read back rather than reusing `emails`: the summary has to cover
            // every message the thread now has, not just the ones in this page.
            database
                .threads()
                .upsert(
                    touched.map { threadId ->
                        val uid = StoreKey.objectKey(accountKey, threadId)
                        val thread = fetched[threadId] ?: MailThread(id = ThreadId(threadId))

                        val row =
                            thread.toEntity(
                                accountKey,
                                database.emails().inThread(accountKey, threadId),
                            )

                        // Every field on that row is derived from the messages
                        // — except the snooze time, which is a property of the
                        // conversation and exists nowhere in `Email/get`. So a
                        // caller that did not fetch the Thread has not learned
                        // that it is null; it has learned nothing, and writing
                        // null anyway is how the only local record of when mail
                        // is due back gets destroyed by an unrelated page load.
                        // Callers that *do* fetch it — the pagers and the delta
                        // sync — carry the server's answer, including a null
                        // that genuinely means the mail is awake again.
                        if (threadId in fetched) row
                        else row.copy(snoozedUntil = database.threads().byUid(uid)?.snoozedUntil)
                    }
                )
        }
    }

    /** One conversation's messages, oldest first, from the cache. */
    suspend fun messagesInThread(accountKey: String, threadId: String): List<EmailEntity> =
        database.emails().inThread(accountKey, threadId)

    /**
     * The conversation's own row, for the things that are not properties of any message.
     *
     * Snooze is the only one today, and it is the reason this exists: the reader has to offer
     * "unsnooze" rather than "snooze" for something already put away, and no message carries that.
     */
    suspend fun thread(accountKey: String, threadId: String): ThreadEntity? =
        database.threads().byUid(StoreKey.objectKey(accountKey, threadId))

    suspend fun body(emailUid: String): EmailBodyEntity? = database.emails().body(emailUid)

    /**
     * Marks a message read locally.
     *
     * Local only for now. The `Email/set` that tells the server arrives with M5, where it belongs
     * alongside the rest of the local-first mutations and the undo that goes with them — writing it
     * here would mean a mutation with no rollback path.
     */
    suspend fun markSeen(accountKey: String, emailUid: String) {
        database.withTransaction {
            val email = database.emails().byUid(emailUid) ?: return@withTransaction
            if (email.isSeen) return@withTransaction

            database.emails().upsert(listOf(email.copy(isSeen = true)))

            // The thread row is denormalised, so it has to be recomputed or the
            // list keeps showing the conversation as unread.
            email.threadId?.let { threadId ->
                database.threads().byUid(StoreKey.objectKey(accountKey, threadId))?.let { thread ->
                    val messages = database.emails().inThread(accountKey, threadId)
                    database
                        .threads()
                        .upsert(listOf(thread.copy(isUnread = messages.any { !it.isSeen })))
                }
            }
        }
    }

    /**
     * Stores the Email state a page was read at.
     *
     * Only ever moves forward from *absent* to set here; delta sync owns it afterwards. A blank
     * state is ignored rather than written, because a cursor of "" is not a starting point and
     * would send `Email/changes` somewhere it cannot answer from.
     */
    suspend fun recordEmailState(accountKey: String, state: String) {
        if (state.isBlank()) return

        database.accounts().setEmailState(accountKey, state)
    }

    /** Records the outcome of a sync so the diagnostics screen can show it. */
    suspend fun recordSync(accountKey: String, at: Long?, error: String?) {
        database.accounts().recordSync(accountKey, at, error)
    }

    /** The composite key for one account on one server. */
    fun accountKey(server: String, accountId: String): String = StoreKey.account(server, accountId)
}
