package de.plmail.core.data

import androidx.room.withTransaction
import de.plmail.core.database.FeedCursorEntity
import de.plmail.core.database.FeedEntryEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.database.ThreadEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts synced conversations into the lists they belong to, and takes them out of the ones they do
 * not.
 *
 * Every list in the app reads `feed_entries`, and until this existed only [FeedMediator] and
 * `MailActions.restoreToInbox` ever wrote that table. So a delta sync could ask `Email/changes`
 * correctly, receive a genuinely new message, store the message, store the conversation summarised
 * from it — and change nothing anybody could see. The mail was on the device and invisible, which
 * is the same screen as a sync that never ran at all.
 *
 * **Membership is decided from the thread row alone.** It already carries `labelKeys` and
 * `category`, both of them the server's own answer written by `MailRepository.storeEmails` moments
 * earlier, so placing a conversation costs one read rather than a query per list.
 *
 * **Symmetric by construction.** A conversation not in a list's membership has its row removed, and
 * that is not a bonus: it is the same bug from the other end. A conversation archived in the
 * browser has to leave this phone's inbox, and nothing did that either.
 *
 * This is deliberately *not* part of `storeEmails`. That method is also the pager's `onPage`, where
 * [FeedMediator] is already writing the right rows for the list being paged, and running a
 * projection there would double the write cost of every page for nothing.
 */
@Singleton
class FeedProjection
@Inject
constructor(
    private val database: PlMailDatabase,
    private val outbox: Outbox,
) {

    /**
     * Brings every live list in line with what these conversations now are.
     *
     * One transaction for the whole batch, matching the write it follows: a list that gained rows
     * while a half-applied projection was still deciding about the rest would draw a state that was
     * never true of the server.
     */
    suspend fun reconcile(accountKey: String, threadIds: Collection<String>) {
        if (threadIds.isEmpty()) return

        // Read once for the whole call rather than per conversation. A drain
        // finishing mid-projection would otherwise split one batch into rows
        // decided under two different queues.
        val pending = outbox.pendingTargets()

        // Which of this account's labels *is* the inbox. `labelKeys` holds
        // collapse keys, not roles, so the binding has to be resolved before
        // "is this in the inbox" can be asked of a row at all.
        val inboxKey = database.mailboxes().byRole(accountKey, INBOX_ROLE)?.labelKey()

        // Whether this server classifies mail at all, which decides what an
        // unclassified conversation means -- see targetFeeds. Read once for the
        // batch and outside the transaction: it is a fact about the server, and
        // the rows this reads are the ones storeEmails has already committed.
        val classifies = database.threads().hasCategories()

        database.withTransaction {
            val live =
                database
                    .feed()
                    .feedsPagedBy(accountKey)
                    .filterNot { it == Feed.SEARCH.id }
                    .map {
                        it to database.feed().cursor(it, accountKey)
                    }

            if (live.isEmpty()) return@withTransaction

            threadIds.forEach { threadId ->
                project(accountKey, threadId, live, inboxKey, pending, classifies)
            }
        }
    }

    private suspend fun project(
        accountKey: String,
        threadId: String,
        live: List<Pair<String, FeedCursorEntity?>>,
        inboxKey: String?,
        pending: Set<String>,
        classifies: Boolean,
    ) {
        val uid = StoreKey.objectKey(accountKey, threadId)

        // The highest risk in this whole change, and the reason the queue is
        // consulted at all. The cache already carries the change the user made
        // offline -- that is why it is queued rather than applied twice -- so
        // the server's copy of this conversation is out of date by exactly the
        // gesture they made. Projecting it would resurrect a conversation they
        // watched leave the list.
        if (uid in pending) return

        val thread = database.threads().byUid(uid)

        if (thread == null) {
            // Destroyed, or never summarised. Either way there is nothing for a
            // row to join to, and a feed row pointing at no thread is a gap in
            // the list rather than an entry in it.
            live.forEach { (feedId, _) -> database.feed().clearThread(feedId, uid) }
            return
        }

        val targets = targetFeeds(thread, inboxKey, classifies)

        live.forEach { (feedId, cursor) ->
            if (feedId !in targets) {
                // Unconditional, unlike the insert below. A row that should not
                // be there should go whether or not it is inside the window the
                // list has paged -- it is already on screen if it is above it,
                // and harmless to delete if it is not.
                database.feed().clearThread(feedId, uid)
                return@forEach
            }

            if (isWithinWindow(thread, cursor))
                database.feed().upsertEntries(listOf(entry(feedId, thread)))
        }
    }

    /**
     * Whether a conversation belongs above the list's tail.
     *
     * A list is only paged as far down as somebody has scrolled, and a conversation older than that
     * belongs *below* the last row it holds — inserting it there would put a message from 2019
     * immediately under last week's mail, at the bottom of a list that simply has not reached it
     * yet. Which is a real case rather than a hypothetical: a read receipt or a label applied on
     * the web brings old conversations back through `Email/changes` all the time.
     *
     * Exhausted is the exception that proves it: a list that has reached the end of its account has
     * no tail left to fall below.
     */
    private fun isWithinWindow(thread: ThreadEntity, cursor: FeedCursorEntity?): Boolean {
        val tail = cursor?.lastSortDate ?: return true

        return cursor.isExhausted || thread.latestReceivedAt >= tail
    }

    /**
     * Which lists this conversation belongs in, from what the server said about it.
     *
     * Categories hang off the inbox binding rather than standing alone, because that is what they
     * are: the server classifies mail as it arrives and never unclassifies it, so a conversation in
     * Trash keeps its category, and a tab that ignored the binding would show the bin's promotions.
     * The same reasoning `FeedRepository.category` builds its filter from.
     *
     * **A null category belongs to no category list**, on a server that classifies. The server's
     * own inbox query does exactly this, and folding null into Primary would put mail on this
     * phone's Primary tab that the browser's does not have. So would a wire value this build has
     * never heard of, which `fromWire` answers null for on purpose.
     *
     * **Where nothing is classified at all, Primary holds the inbox**, which is the same rule
     * [FeedRepository.category] queries the server by and has to be, or the rows the pager fetches
     * and the rows the sync projects would be two different lists. That is what makes Primary a
     * start destination the app can open on without first asking whether this plMail classifies —
     * see [MailView]. [classifies] is passed in rather than read here because this runs inside the
     * projection's transaction, once per conversation.
     */
    private fun targetFeeds(
        thread: ThreadEntity,
        inboxKey: String?,
        classifies: Boolean,
    ): Set<String> {
        val carried = thread.labelKeys.split(",").filter { it.isNotBlank() }
        val feeds = carried.mapTo(mutableSetOf(), ::labelFeedId)

        if (inboxKey != null && inboxKey in carried) {
            feeds += Feed.UNIFIED_INBOX.id

            val category = MailCategory.fromWire(thread.category)

            when {
                category != null -> feeds += category.feedId
                !classifies -> feeds += MailCategory.PRIMARY.feedId
            }
        }

        return feeds
    }

    /**
     * The row, keyed exactly as [FeedMediator] writes it and `clearThread` deletes it.
     *
     * It has to be: a row keyed any other way is a second copy of the same conversation the next
     * time the list pages, and one that nothing can ever remove. `MailActions.restoreToInbox`
     * records what happened the last time this was got wrong.
     */
    private fun entry(feedId: String, thread: ThreadEntity): FeedEntryEntity =
        FeedEntryEntity(
            uid = "$feedId#${thread.uid}",
            feedId = feedId,
            sortDate = thread.latestReceivedAt,
            accountKey = thread.accountKey,
            threadId = thread.threadId,
            // The thread id, as `restoreToInbox` also writes. The column names
            // the message a row renders from, and nothing reads it: the list
            // query joins on the conversation and draws the summary off it.
            // Fetching the newest message id to fill it honestly would be a
            // query per conversation for a value with no reader.
            emailId = thread.threadId,
        )

    private companion object {
        const val INBOX_ROLE = "inbox"
    }
}
