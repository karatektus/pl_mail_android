package de.plmail.core.data

import androidx.room.withTransaction
import de.plmail.core.database.FeedEntryEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.jmap.methods.EmailPatch
import de.plmail.jmap.methods.EmailSet
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.MailboxId
import de.plmail.jmap.protocol.RequestBuilder
import de.plmail.jmap.protocol.StateToken
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies an action locally, then tells the server.
 *
 * **Local first, always.** The list has to move under the user's thumb at the moment they swipe;
 * waiting for a NAS on the end of a domestic uplink turns every archive into a visible pause. The
 * consequence is that a rejection arrives after the row has already gone, which is why
 * [ActionOutcome.Rejected] exists and why the UI must show it rather than log it.
 *
 * Nothing here is a hard delete. Trash goes through `Email/set` `destroy`, which in plMail moves
 * the message to Trash; its undo restores the Inbox label like any other move.
 */
@Singleton
class MailActions
@Inject
constructor(
    private val database: PlMailDatabase,
    private val clients: AccountClients,
) {

    /**
     * Applies [action] to [targets] and reports what the server made of it.
     *
     * The local write happens first and is never rolled back automatically. Silently reverting on
     * failure would make rows move on their own some seconds after the user acted, which is more
     * confusing than the failure — the undo the caller was given is the honest way back.
     */
    suspend fun apply(action: MailAction, targets: List<ActionTarget>): ActionOutcome {
        val undoable = UndoableAction(action, targets)
        if (targets.isEmpty()) return ActionOutcome.Applied(undoable)

        applyLocally(action, targets)

        return try {
            send(action, targets)
            ActionOutcome.Applied(undoable)
        } catch (rejected: Exception) {
            ActionOutcome.Rejected(undoable, rejected.message ?: "The server rejected the change.")
        }
    }

    /**
     * Undo is the same path with the inverse action, so it can fail and be reported identically.
     */
    suspend fun undo(undoable: UndoableAction): ActionOutcome =
        apply(undoable.action.inverse, undoable.targets)

    /**
     * Writes the change to the cache.
     *
     * Feed rows are removed for the actions that take a conversation out of the list, because the
     * list reads the feed table: leaving the row would show an archived conversation until the next
     * refresh, which is exactly the "did that work?" moment swiping is supposed to avoid.
     */
    private suspend fun applyLocally(action: MailAction, targets: List<ActionTarget>) {
        database.withTransaction {
            targets.forEach { target ->
                val threadUid = StoreKey.objectKey(target.accountKey, target.threadId)

                when (action) {
                    MailAction.Archive,
                    MailAction.Trash,
                    MailAction.MarkSpam ->
                        database.feed().clearThread(Feed.UNIFIED_INBOX.id, threadUid)

                    // Undoing a removal has to put the row back, or the list
                    // keeps the conversation hidden until the next refresh and
                    // "undo" appears to have done nothing.
                    MailAction.MoveToInbox ->
                        database.threads().byUid(threadUid)?.let { thread ->
                            database
                                .feed()
                                .upsertEntries(
                                    listOf(
                                        FeedEntryEntity(
                                            uid = "${'$'}{Feed.UNIFIED_INBOX.id}#${'$'}threadUid",
                                            feedId = Feed.UNIFIED_INBOX.id,
                                            sortDate = thread.latestReceivedAt,
                                            accountKey = target.accountKey,
                                            threadId = target.threadId,
                                            emailId = target.threadId,
                                        )
                                    )
                                )
                        }

                    is MailAction.Star -> database.threads().setFlagged(threadUid, action.flagged)

                    is MailAction.MarkRead -> {
                        database.threads().setUnread(threadUid, !action.seen)

                        database
                            .emails()
                            .inThread(target.accountKey, target.threadId)
                            .filter { it.isSeen != action.seen }
                            .let { changed ->
                                database
                                    .emails()
                                    .upsert(changed.map { it.copy(isSeen = action.seen) })
                            }
                    }
                }
            }
        }
    }

    /**
     * One `Email/set` per account.
     *
     * Grouped rather than one call per message: a bulk archive of forty conversations is one round
     * trip per account, and the server's `maxObjectsInSet` is 500.
     */
    private suspend fun send(action: MailAction, targets: List<ActionTarget>) {
        targets
            .groupBy { it.accountKey }
            .forEach { (accountKey, forAccount) ->
                val account = database.accounts().byUid(accountKey) ?: return@forEach
                val client = clients.forAccount(accountKey) ?: return@forEach
                // Resolved from the cache rather than taken from the caller.
                // Thread ids and message ids are different id spaces, and a
                // list row only knows the former.
                val ids =
                    forAccount
                        .flatMap { database.emails().inThread(it.accountKey, it.threadId) }
                        .map { EmailId(it.emailId) }

                if (ids.isEmpty()) return@forEach

                val request = RequestBuilder()
                val accountId = AccountId(account.accountId)

                val set =
                    if (action == MailAction.Trash) {
                        // `destroy` is the move to Trash. The server has no
                        // hard-delete path, so this is a relocation.
                        EmailSet(
                            accountId = accountId,
                            destroy = ids,
                            ifInState = state(account.emailState),
                        )
                    } else {
                        // A missing binding is reported, never skipped. Silently
                        // doing nothing here is the worst possible outcome: the
                        // row has already left the list locally, so the user
                        // sees a successful archive while the server keeps the
                        // message and hands it straight back on the next sync.
                        val patch =
                            patchFor(action, accountKey)
                                ?: error(
                                    "This account has no mailbox to move ${'$'}{action::class.simpleName} " +
                                        "relative to yet. Its labels have not been synced."
                                )

                        EmailSet(
                            accountId = accountId,
                            update = ids.associateWith { patch },
                            // The state the local copy was built from. Two
                            // clients editing one conversation otherwise take
                            // turns overwriting each other silently.
                            ifInState = state(account.emailState),
                        )
                    }

                val handle = request.add(set)
                val result = client.send(request).result(handle)

                // `Email/set` reports per-message refusals inside a perfectly
                // successful 200, so a request that "worked" can have changed
                // nothing. Ignoring notUpdated is why archiving appeared to
                // succeed while the server kept the message and handed it back
                // on the next sync.
                if (result.hasFailures) {
                    val failure = result.firstFailure()

                    error(failure?.description ?: failure?.type ?: "The server refused the change.")
                }
            }
    }

    private fun state(token: String?): StateToken? = token?.let(::StateToken)

    /**
     * The patch for an action, resolved against this account's own bindings.
     *
     * Null when the account has no Inbox binding to move in or out of — a move with nothing to move
     * relative to is not a no-op to be sent blindly, it is a change we cannot express.
     */
    private suspend fun patchFor(action: MailAction, accountKey: String): EmailPatch? =
        when (action) {
            is MailAction.Star -> EmailPatch.build { flagged(action.flagged) }
            is MailAction.MarkRead -> EmailPatch.build { seen(action.seen) }

            // Archiving removes Inbox and adds nothing. Adding an Archive label
            // instead leaves the message in the inbox as well, which is not
            // what anyone means by archiving.
            // Archiving removes Inbox and adds nothing -- on an account where
            // the message belongs to something else as well. JMAP requires every
            // message to be in at least one mailbox, and the server enforces it
            // ("An Email must belong to at least one Mailbox"), so on a plain
            // IMAP account whose message is *only* in Inbox that removal is
            // invalid. There the Archive binding is where it has to go, which is
            // what that binding is for: IMAP location bookkeeping.
            MailAction.Archive -> {
                val inbox = inbox(accountKey) ?: return null
                val needsSomewhere = onlyInInbox(accountKey, inbox)
                val archive = if (needsSomewhere) binding(accountKey, "archive") else null

                if (needsSomewhere && archive == null) return null

                EmailPatch.build {
                    archive?.let { addMailbox(it) }
                    removeMailbox(inbox)
                }
            }

            MailAction.MoveToInbox -> inbox(accountKey)?.let { EmailPatch.build { addMailbox(it) } }

            MailAction.MarkSpam -> {
                val junk = binding(accountKey, "junk") ?: return null
                val inbox = inbox(accountKey)

                EmailPatch.build {
                    addMailbox(junk)
                    // Removed as well as added: spam that stays in the inbox
                    // has not been marked as anything the user can see.
                    inbox?.let { removeMailbox(it) }
                }
            }

            MailAction.Trash -> null
        }

    private suspend fun inbox(accountKey: String): MailboxId? = binding(accountKey, "inbox")

    /**
     * Whether this account's messages sit in the Inbox and nowhere else.
     *
     * A Gmail-backed account keeps everything in an All Mail container as well, so removing Inbox
     * leaves the message somewhere. A plain IMAP account may not, and there the same removal is
     * rejected outright.
     */
    private suspend fun onlyInInbox(accountKey: String, inbox: MailboxId): Boolean =
        database.mailboxes().forAccount(accountKey).none {
            it.mailboxId != inbox.value && it.role == "all"
        }

    private suspend fun binding(accountKey: String, role: String): MailboxId? =
        database.mailboxes().byRole(accountKey, role)?.let { MailboxId(it.mailboxId) }
}
