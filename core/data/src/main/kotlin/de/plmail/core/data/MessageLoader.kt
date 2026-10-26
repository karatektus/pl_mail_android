package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import de.plmail.core.datastore.CredentialStore
import de.plmail.jmap.methods.EmailGet
import de.plmail.jmap.methods.ThreadGet
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.RequestBuilder
import de.plmail.jmap.protocol.ThreadId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Fetches a message in full, on demand.
 *
 * List pages deliberately do not carry bodies — `EmailGet.LIST_ROW_PROPERTIES` stops short of them
 * because a body can be 400KB and a page of fifty would be twenty megabytes to draw a list nobody
 * has scrolled yet. So the reader asks for the rest when a conversation is opened, and this is what
 * asks.
 *
 * Bodies land in their own table, so a later list query still never faults one in.
 */
@Singleton
class MessageLoader
@Inject
constructor(
    private val database: PlMailDatabase,
    private val credentials: CredentialStore,
    private val clients: AccountClients,
    private val mail: MailRepository,
) {

    /**
     * Downloads one conversation as the server holds it: the messages the device has never seen,
     * and the bodies of those it has, skipping anything already cached.
     *
     * **Every message, not only the cached ones (#62).** A list page is collapsed to one message
     * per conversation, so a thread paged in from the list arrives as its newest message alone, and
     * nothing else ever fetched the rest: a conversation of four opened as one, under its last
     * reply's subject. So the conversation is asked what it holds — one `Thread/get` per open,
     * which is the price of knowing — and the `Email/get` that follows asks for the members the
     * device lacks along with the bodies it lacks, or is not sent at all when there are none.
     *
     * Returns silently when there is no connection: the reader shows what it has either way.
     */
    suspend fun loadBodies(accountKey: String, threadId: String) {
        val stored = database.emails().inThread(accountKey, threadId)

        // A draft counts as missing however much of it is on disk. Everything
        // else in this table is a message that has arrived and will never say
        // anything different, which is what makes caching a body once correct;
        // a draft is the one row somebody is still editing, quite possibly in
        // another window right now. `MailRepository.storeEmails` drops the
        // cached copy whenever a sync touches the row, and this is the other
        // half: a conversation reopened without a sync in between still shows
        // the draft as it stands rather than as it was first seen.
        //
        // So does a row whose cache contradicts it -- see `contradicts`. That
        // is the repair, and it has to be here rather than only in the sync:
        // the mail this happened to was sent weeks ago and the server will
        // never report it as changed again, so a phone already holding a
        // poisoned marker would keep it forever. This is the screen where the
        // blank message is, and it is where it gets fixed.
        val (missing, held) =
            stored.partition { email ->
                val body = database.emails().body(email.uid)
                email.isDraft || body == null || body.contradicts(email)
            }

        val now = System.currentTimeMillis()

        // Opening a conversation is the event eviction should be measured from,
        // and until this it was not measured at all: `fetchedAt` recorded when a
        // body was *downloaded*, so `BodyPrefetcher.prune` would drop a thread
        // reread every week on the sixtieth day after its one fetch. Done before
        // the early return, because a thread whose bodies are all cached is
        // precisely the one being reread.
        if (held.isNotEmpty()) database.emails().touchBodies(held.map { it.uid }, now)

        val connection = credentials.connection.first() ?: return
        val account = database.accounts().byUid(accountKey) ?: return

        // The shared client, whose Session is already known: a client of its
        // own asked the server for it again on every conversation opened.
        val client = clients.current() ?: return

        val accountId = AccountId(account.accountId)

        val members = RequestBuilder()
        val threadGet = members.add(ThreadGet(accountId, ids = listOf(ThreadId(threadId))))
        val threads = client.send(members).result(threadGet).list

        // Null when the server no longer has the conversation -- merged into
        // another, or erased -- and then the bodies are all there is to fetch.
        val onServer = threads.firstOrNull()?.emailIds.orEmpty().map { it.value }
        val known = stored.map { it.emailId }.toSet()
        val wanted = (missing.map { it.emailId } + onServer.filterNot { it in known }).distinct()

        if (wanted.isEmpty()) return

        val request = RequestBuilder()
        val get =
            request.add(
                EmailGet(
                    accountId = accountId,
                    ids = wanted.map(::EmailId),
                    properties = EmailGet.READER_PROPERTIES,
                    fetchTextBodyValues = true,
                    // NOTE the capitalisation inside EmailGet: fetchHTMLBodyValues.
                    // The wrong spelling is silently ignored and returns empty
                    // body values with nothing to debug.
                    fetchHtmlBodyValues = true,
                )
            )

        val emails = client.send(request).result(get).list

        // Through the repository, so bodies and attachments are written the same
        // way a sync writes them and the thread summary is recomputed once --
        // with the conversation as the server just described it, so its row
        // counts the messages that have only now arrived.
        mail.storeEmails(accountKey, emails, threads, fetchedAt = now)

        // A message with neither text nor html stores no body row, so without
        // this it is "missing a body" again the next time the thread is opened --
        // one `Email/get` per open, forever, for a message that has nothing to
        // fetch. See `markFetchedBodylessMessages`.
        database.markFetchedBodylessMessages(accountKey, emails, now)
    }
}
