package de.plmail.core.data

import de.plmail.core.database.StoreKey
import de.plmail.core.datastore.OutboxStore
import de.plmail.jmap.protocol.JmapError
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One change waiting to reach the server.
 *
 * A flattened shape rather than a serialised [MailAction], deliberately. `SetLabel` carries a whole
 * [Label], and a [Label] is *cache*: its bindings are mailbox ids that may have been re-synced,
 * renumbered or deleted between the tap and the send. Storing them would mean a queue that patches
 * mailbox ids the server has forgotten, which fails with `notFound` and looks like the queue being
 * broken. The key survives that, because a label key is the same value in every account that binds
 * it, so the action is rebuilt against the label list as it is *when the queue drains*.
 */
@Serializable
internal data class PendingMutation(
    val kind: Kind,
    val targets: List<Target>,
    val queuedAt: Long,
    /** `Star.flagged` and `MarkRead.seen`, whichever this is. */
    val flag: Boolean = false,
    /** The collapse key for [Kind.LABEL], re-resolved on drain. */
    val labelKey: String? = null,
    val applied: Boolean = false,
    val until: Long? = null,
) {
    @Serializable
    enum class Kind {
        ARCHIVE,
        MOVE_TO_INBOX,
        TRASH,
        STAR,
        MARK_READ,
        SPAM,
        LABEL,
        SNOOZE,
    }

    @Serializable data class Target(val accountKey: String, val threadId: String)
}

/** How many changes are waiting, for the banner that says so. */
data class OutboxState(val pending: Int, val oldestQueuedAt: Long?) {
    val isEmpty: Boolean
        get() = pending == 0
}

/**
 * Changes the user made while the server was unreachable, kept until it is not.
 *
 * The whole feature exists because of a decision made earlier: every action is applied to the cache
 * **first** and never rolled back automatically, because the list has to move under the user's
 * thumb. Offline, that produced a phone showing an archived conversation that the server had never
 * been told about, and a snackbar saying so — after which the change was simply gone. Local-first
 * without a queue is local-*only* the moment the network is.
 *
 * **A transport failure queues; a server rejection does not.** That distinction is the one thing in
 * this file that must not be blurred. "The server said no" is an answer, and re-sending it produces
 * a loop that never terminates and never tells anybody anything — an `Email/set` refused with
 * `notFound` will be refused again in an hour and in a week. "Nothing answered" is not an answer,
 * and it is the case a queue is for.
 *
 * Ordering is preserved and matters more than it looks: star-then-unstar and unstar-then-star are
 * different end states, and a queue that drained by grouping would settle on whichever it grouped
 * last. Draining stops at the first transport failure for the same reason — a later change replayed
 * before an earlier one is the same bug arriving from the other end.
 */
/**
 * The label list, as the queue needs it.
 *
 * A one-method seam rather than [LabelRepository] itself, and not for testing convenience: the
 * repository reaches Room and OkHttp, so holding it here would make a queue whose only job is to
 * remember four strings unusable anywhere without a database — including in the tests that pin the
 * one rule this file exists for.
 */
fun interface KnownLabels {
    suspend fun byKey(): Map<String, Label>
}

@Singleton
class Outbox
@Inject
constructor(
    private val store: OutboxStore,
    private val labels: KnownLabels,
) {

    val state: Flow<OutboxState> =
        store.queue.map { raw ->
            val queued = decode(raw)

            OutboxState(pending = queued.size, oldestQueuedAt = queued.minOfOrNull { it.queuedAt })
        }

    /**
     * Records a change the server could not be told about.
     *
     * Returns false for anything that cannot be replayed, which today is only an action this queue
     * has no shape for — and there are none. It is a `Boolean` rather than `Unit` so the caller can
     * report the difference between "queued, it will go" and "lost", instead of promising the first
     * and doing the second.
     */
    suspend fun enqueue(action: MailAction, targets: List<ActionTarget>, at: Long): Boolean {
        val pending = action.asPending(targets, at) ?: return false

        store.update { raw -> encode(decode(raw) + pending) }

        return true
    }

    /**
     * The conversations the queue is still holding a change for, as store keys.
     *
     * Read by [FeedProjection] before it rewrites a feed from the server's answer, and it is the
     * one thing standing between a sync and a visible lie. `SyncWorker` drains this queue *before*
     * syncing precisely so that the server's copy cannot undo a change made offline — but a drain
     * that could not reach the server leaves the change here, and the sync that follows would then
     * project the conversation back into the inbox the user archived it out of. Skipping these is
     * how "your archive is still true on your phone" survives the network coming back halfway.
     *
     * Store keys rather than the targets themselves, because that is the form the caller compares
     * against and the only one in which an account and a thread id cannot be paired up wrongly.
     */
    suspend fun pendingTargets(): Set<String> =
        decode(store.queue.first())
            .flatMap { it.targets }
            .mapTo(mutableSetOf()) { StoreKey.objectKey(it.accountKey, it.threadId) }

    /**
     * Sends everything waiting, oldest first, and keeps whatever did not go.
     *
     * Takes the sender as a parameter rather than holding [MailActions], because [MailActions] is
     * what calls [enqueue] — injecting it here would be a dependency cycle that Hilt reports as a
     * wall of generated type names with no mention of either class.
     */
    suspend fun drain(send: suspend (MailAction, List<ActionTarget>) -> Unit): DrainResult {
        val queued = decode(store.queue.first())

        if (queued.isEmpty()) return DrainResult(sent = 0, remaining = 0)

        // Resolved once for the whole drain. A label key has to become a Label
        // with this account's binding on it, and the sidebar's list is the only
        // thing that knows the mapping.
        val known = labels.byKey()

        var sent = 0

        for ((index, pending) in queued.withIndex()) {
            val action = pending.asAction(known)

            if (action == null) {
                // A label the server has since deleted, or one this device no
                // longer syncs. Dropped rather than retried forever: there is
                // nothing to apply it to, and a queue that cannot empty is a
                // banner that never goes away.
                sent++
                continue
            }

            try {
                send(action, pending.targets.map { ActionTarget(it.accountKey, it.threadId) })
                sent++
            } catch (offline: IOException) {
                return stopAt(queued, index, sent, offline)
            } catch (unreachable: JmapError.Unreachable) {
                return stopAt(queued, index, sent, unreachable)
            } catch (refused: Exception) {
                // An answer, however unwelcome. Dropped, because replaying it
                // changes nothing except how long the queue stays full.
                sent++
            }
        }

        store.update { raw -> encode(decode(raw).drop(sent)) }

        return DrainResult(sent = sent, remaining = 0)
    }

    private suspend fun stopAt(
        queued: List<PendingMutation>,
        index: Int,
        sent: Int,
        cause: Throwable,
    ): DrainResult {
        // Only the ones that actually went. Everything from the failure onward
        // stays in order, including the one that failed — it was never sent, and
        // dropping it would lose a change the user can still see on their phone.
        store.update { raw -> encode(decode(raw).drop(sent)) }

        return DrainResult(sent = sent, remaining = queued.size - index, error = cause)
    }

    data class DrainResult(val sent: Int, val remaining: Int, val error: Throwable? = null)

    private fun decode(raw: String): List<PendingMutation> =
        if (raw.isBlank()) emptyList()
        else
        // A queue that will not parse is a queue written by a build that is
        // no longer installed. Cleared rather than crashed on: appearance is
        // not worth a crash loop and neither is this, and the alternative is
        // an app that cannot start until somebody clears its data.
        runCatching { JSON.decodeFromString<List<PendingMutation>>(raw) }.getOrDefault(emptyList())

    private fun encode(queue: List<PendingMutation>): String = JSON.encodeToString(queue)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** The queue's own shape for an action, or null for one it cannot replay. */
private fun MailAction.asPending(
    targets: List<ActionTarget>,
    at: Long,
): PendingMutation? {
    val stored = targets.map { PendingMutation.Target(it.accountKey, it.threadId) }

    return when (this) {
        MailAction.Archive -> PendingMutation(PendingMutation.Kind.ARCHIVE, stored, at)
        MailAction.MoveToInbox -> PendingMutation(PendingMutation.Kind.MOVE_TO_INBOX, stored, at)
        MailAction.Trash -> PendingMutation(PendingMutation.Kind.TRASH, stored, at)
        MailAction.MarkSpam -> PendingMutation(PendingMutation.Kind.SPAM, stored, at)
        is MailAction.Star -> PendingMutation(PendingMutation.Kind.STAR, stored, at, flag = flagged)
        is MailAction.MarkRead ->
            PendingMutation(PendingMutation.Kind.MARK_READ, stored, at, flag = seen)
        is MailAction.SetLabel ->
            PendingMutation(
                PendingMutation.Kind.LABEL,
                stored,
                at,
                labelKey = label.key,
                applied = applied,
            )
        is MailAction.Snooze ->
            PendingMutation(PendingMutation.Kind.SNOOZE, stored, at, until = until)
    }
}

/** The action again, rebuilt against the label list as it is now. */
private fun PendingMutation.asAction(known: Map<String, Label>): MailAction? =
    when (kind) {
        PendingMutation.Kind.ARCHIVE -> MailAction.Archive
        PendingMutation.Kind.MOVE_TO_INBOX -> MailAction.MoveToInbox
        PendingMutation.Kind.TRASH -> MailAction.Trash
        PendingMutation.Kind.SPAM -> MailAction.MarkSpam
        PendingMutation.Kind.STAR -> MailAction.Star(flag)
        PendingMutation.Kind.MARK_READ -> MailAction.MarkRead(flag)
        PendingMutation.Kind.SNOOZE -> MailAction.Snooze(until)
        PendingMutation.Kind.LABEL -> known[labelKey]?.let { MailAction.SetLabel(it, applied) }
    }
