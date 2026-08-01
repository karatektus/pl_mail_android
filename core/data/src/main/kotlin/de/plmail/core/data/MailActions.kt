package de.plmail.core.data

import androidx.room.withTransaction
import de.plmail.core.database.EmailEntity
import de.plmail.core.database.FeedEntryEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.jmap.methods.EmailPatch
import de.plmail.jmap.methods.EmailSet
import de.plmail.jmap.methods.ThreadPatch
import de.plmail.jmap.methods.ThreadSet
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.MailboxId
import de.plmail.jmap.protocol.RequestBuilder
import de.plmail.jmap.protocol.StateToken
import de.plmail.jmap.protocol.ThreadId
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
    private val mail: MailRepository,
) {

    /**
     * Applies [action] to [targets] and reports what the server made of it.
     *
     * The local write happens first and is never rolled back automatically. Silently reverting on
     * failure would make rows move on their own some seconds after the user acted, which is more
     * confusing than the failure — the undo the caller was given is the honest way back.
     */
    suspend fun apply(action: MailAction, targets: List<ActionTarget>): ActionOutcome {
        // Before the local write, not after. The way back from a snooze is the
        // time the conversation was sleeping until beforehand, and the next line
        // overwrites it -- so reading it when undo is tapped finds the value the
        // snooze just set and "undo" restores the change it was asked to revert.
        val undoable = UndoableAction(action, targets, wayBack(action, targets))

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
     * Undo is the same path as [apply], so it can fail and be reported identically.
     *
     * Usually one step. Where the way back needs several — conversations restored to different
     * snooze times — they are applied in turn and announced as the single change the user made,
     * because they made one gesture and three snackbars for one tap is worse than none.
     */
    suspend fun undo(undoable: UndoableAction): ActionOutcome {
        val outcomes = undoable.steps.map { apply(it.action, it.targets) }

        val undone =
            UndoableAction(
                // Null where the steps disagree: a selection spanning awake and
                // sleeping conversations is genuinely "unsnoozed" for some and
                // "snoozed" for others, and naming one of those is wrong about
                // the rest.
                action = undoable.steps.map { it.action }.distinct().singleOrNull(),
                targets = undoable.targets,
                // The way back from the way back, which is the original change.
                // Taken from what each sub-apply worked out rather than rebuilt,
                // so undoing an undo is exact for the same reason undo is.
                steps = outcomes.flatMap { it.undoable.steps },
            )

        // The first refusal decides. A partial undo has still moved rows, so the
        // way back is offered either way -- the same rule as apply.
        val rejection = outcomes.filterIsInstance<ActionOutcome.Rejected>().firstOrNull()

        return rejection?.let { ActionOutcome.Rejected(undone, it.reason) }
            ?: ActionOutcome.Applied(undone)
    }

    /**
     * [wayBackFrom], with the pre-state read out of the cache.
     *
     * Only snooze needs the read, and only snooze pays for it: every other action's inverse is a
     * property of the action itself.
     */
    private suspend fun wayBack(action: MailAction, targets: List<ActionTarget>): List<UndoStep> {
        val before =
            if (action is MailAction.Snooze) {
                targets.associateWith {
                    database
                        .threads()
                        .byUid(StoreKey.objectKey(it.accountKey, it.threadId))
                        ?.snoozedUntil
                }
            } else {
                emptyMap()
            }

        return wayBackFrom(action, targets, before)
    }

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
                    MailAction.MoveToInbox -> restoreToInbox(target, threadUid)

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

                    // The bindings on the message rows, so the label sheet
                    // reflects the tick the moment it is tapped rather than
                    // after a round trip -- and the label's own feed, so the
                    // conversation appears in or leaves that list at the same
                    // moment.
                    is MailAction.SetLabel -> {
                        val binding = action.label.bindings.bindingIn(target.accountKey)

                        if (binding != null) {
                            database.emails().inThread(target.accountKey, target.threadId).let {
                                messages ->
                                database
                                    .emails()
                                    .upsert(
                                        messages.map { it.withBinding(binding, action.applied) }
                                    )
                            }

                            if (!action.applied) {
                                database.feed().clearThread(action.label.feedId, threadUid)
                            }

                            // And the row's own copy of which labels it carries,
                            // which is what the list draws chips from. Without
                            // this the sheet ticks the label immediately and the
                            // row behind it keeps its old chips until some later
                            // sync happens to touch the conversation -- so the
                            // two halves of the same gesture disagree, on
                            // screen, for an unpredictable length of time.
                            mail.refreshLabelsOf(target.accountKey, target.threadId)
                        }
                    }

                    is MailAction.Snooze -> {
                        database.threads().setSnoozedUntil(threadUid, action.until)

                        if (action.until != null) {
                            // Snoozing takes the conversation out of the inbox
                            // on the server, so the local list has to lose it
                            // too -- otherwise the row sits there until the next
                            // refresh and the snooze looks like it did nothing.
                            database.feed().clearThread(Feed.UNIFIED_INBOX.id, threadUid)
                        } else {
                            // And waking it up is the same rule in reverse, which
                            // is the half that was missing. Undoing a snooze
                            // cleared the timestamp, told the server, and left
                            // the row out of the list -- so the one gesture whose
                            // entire purpose is "no, not that one" changed
                            // nothing anybody could see until the next sync.
                            restoreToInbox(target, threadUid)

                            // And out of the Snoozed list, which is where an
                            // unsnooze is made from. Resolved through the
                            // account's own snoozed binding because the mailbox
                            // is created lazily -- an account that has never
                            // snoozed anything has no such label, and that is an
                            // ordinary state rather than a missing row.
                            snoozedFeedId(target.accountKey)?.let {
                                database.feed().clearThread(it, threadUid)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Puts a conversation back into the unified inbox's list.
     *
     * Shared by the two ways mail returns to the inbox — undoing an archive or a trash, and waking
     * something snoozed — because the failure they share is the same one: the row left the list the
     * moment the action landed, so a way back that does not re-insert it reads as a control that
     * did nothing.
     */
    private suspend fun restoreToInbox(target: ActionTarget, threadUid: String) {
        val thread = database.threads().byUid(threadUid) ?: return

        database
            .feed()
            .upsertEntries(
                listOf(
                    FeedEntryEntity(
                        // Exactly the key FeedMediator writes and clearThread
                        // deletes, and it has to be: a row keyed any other way is
                        // a second copy of the same conversation the next time
                        // the list pages, and one nothing can ever remove. This
                        // was a literal `${Feed…}#$threadUid` for a while -- the
                        // same constant string for every conversation, so
                        // restoring two of them left one row that matched
                        // neither.
                        uid = "${Feed.UNIFIED_INBOX.id}#$threadUid",
                        feedId = Feed.UNIFIED_INBOX.id,
                        sortDate = thread.latestReceivedAt,
                        accountKey = target.accountKey,
                        threadId = target.threadId,
                        emailId = target.threadId,
                    )
                )
            )
    }

    /** Where this account's snoozed mail is listed, or null if it has never snoozed anything. */
    private suspend fun snoozedFeedId(accountKey: String): String? =
        database.mailboxes().byRole(accountKey, SNOOZED_ROLE)?.let { labelFeedId(it.labelKey()) }

    /**
     * One `Email/set` per account.
     *
     * Grouped rather than one call per message: a bulk archive of forty conversations is one round
     * trip per account, and the server's `maxObjectsInSet` is 500.
     */
    private suspend fun send(action: MailAction, targets: List<ActionTarget>) {
        if (action is MailAction.Snooze) return sendSnooze(action, targets)

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
                                    "This account has no mailbox to move ${action::class.simpleName} " +
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

    /**
     * Snooze, which is `Thread/set` and therefore its own path.
     *
     * A conversation is the unit here, not a message, so this is the one action that does not have
     * to resolve thread ids into email ids first — and the one that would be wrong if it did, since
     * snoozing half a conversation is not a thing the product offers.
     */
    private suspend fun sendSnooze(action: MailAction.Snooze, targets: List<ActionTarget>) {
        val at = action.until?.let(::asUtcDateTime)

        targets
            .groupBy { it.accountKey }
            .forEach { (accountKey, forAccount) ->
                val account = database.accounts().byUid(accountKey) ?: return@forEach
                val client = clients.forAccount(accountKey) ?: return@forEach

                val request = RequestBuilder()
                val handle =
                    request.add(
                        ThreadSet(
                            accountId = AccountId(account.accountId),
                            update =
                                forAccount.associate {
                                    ThreadId(it.threadId) to ThreadPatch.snoozedUntil(at)
                                },
                        )
                    )

                val result = client.send(request).result(handle)

                if (result.notUpdated.isNotEmpty()) {
                    val failure = result.notUpdated.values.first()

                    error(failure.description ?: failure.type)
                }
            }
    }

    /**
     * The wire form of a snooze time: UTC, seconds, `Z`.
     *
     * Spelled out rather than left to a default formatter. JMAP's UTCDate is a specific shape and a
     * local-zone timestamp would snooze somebody's mail to the wrong hour without failing.
     */
    private fun asUtcDateTime(epochMillis: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(epochMillis))

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

            is MailAction.SetLabel -> {
                val binding =
                    action.label.bindings.bindingIn(accountKey)
                        ?: error(
                            "\"${action.label.name}\" is not one of this account's labels, so it " +
                                "cannot be put on mail here."
                        )

                EmailPatch.build {
                    if (action.applied) addMailbox(binding) else removeMailbox(binding)
                }
            }

            // Handled by sendSnooze, which speaks Thread/set.
            is MailAction.Snooze -> null

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

    private companion object {
        /** The role plMail gives the mailbox a snoozed conversation waits in. */
        const val SNOOZED_ROLE = "snoozed"
    }
}

/** Where a label lives in one account, or null when it is not bound there at all. */
internal fun List<LabelBinding>.bindingIn(accountKey: String): MailboxId? = firstOrNull {
    it.accountKey == accountKey
}
    ?.let { MailboxId(it.mailboxId) }

/**
 * The same message with one binding added or removed.
 *
 * `mailboxIds` is stored as a comma-separated list rather than a table, because nothing joins on it
 * — but that makes naive string editing dangerous, so this goes through a set: adding twice is
 * idempotent and removing "12" must not also strike "120".
 */
internal fun EmailEntity.withBinding(binding: MailboxId, applied: Boolean): EmailEntity {
    val current = mailboxIds.split(",").filter { it.isNotBlank() }.toMutableSet()

    if (applied) current.add(binding.value) else current.remove(binding.value)

    return copy(mailboxIds = current.joinToString(","))
}
