package de.plmail.core.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY sortIndex, name")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY sortIndex, name") suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE uid = :uid") suspend fun byUid(uid: String): AccountEntity?

    @Upsert suspend fun upsert(accounts: List<AccountEntity>)

    @Query("UPDATE accounts SET emailState = :state WHERE uid = :uid")
    suspend fun setEmailState(uid: String, state: String?)

    /**
     * The first cursor an account is given, and only the first.
     *
     * A page load reports the Email state its answer was read at, which is *now* — so writing it
     * unconditionally steps the delta cursor over everything that has changed since the last
     * `Email/changes`, and those changes can then never be reported. Scrolling deep into a list
     * would silently cost the user the mail that arrived while they scrolled.
     *
     * Expressed in SQL rather than as a read-then-write in Kotlin because delta sync writes this
     * column on every round of a sync that runs concurrently with page loads; the check and the
     * write have to be one statement or the window between them is exactly the bug.
     *
     * No comparison of tokens, deliberately. Today the server's state is `MAX(sequence)` and looks
     * orderable, but it is an opaque JMAP token — a client that started ordering it would have
     * taken on a server implementation detail that can change without anybody being able to tell
     * it. "Only if absent" needs no ordering.
     */
    @Query("UPDATE accounts SET emailState = :state WHERE uid = :uid AND emailState IS NULL")
    suspend fun setEmailStateIfAbsent(uid: String, state: String)

    @Query("UPDATE accounts SET mailboxState = :state WHERE uid = :uid")
    suspend fun setMailboxState(uid: String, state: String?)

    @Query("UPDATE accounts SET threadState = :state WHERE uid = :uid")
    suspend fun setThreadState(uid: String, state: String?)

    /**
     * A sync that worked. Clears the last error, because it is no longer true.
     *
     * Two statements rather than one with nullable parameters, and that is the whole point. The
     * single version wrote both columns every time, so recording a *failure* passed `at = null` and
     * erased the timestamp of the last sync that had worked — deleting the one fact a diagnostics
     * screen exists to show. "Last synced three days ago, and here is the error" is a diagnosis;
     * "never synced, and here is the error" is the same screen lying about the same server.
     */
    @Query("UPDATE accounts SET lastSyncedAt = :at, lastSyncError = NULL WHERE uid = :uid")
    suspend fun recordSyncSucceeded(uid: String, at: Long)

    @Query("UPDATE accounts SET lastSyncError = :error WHERE uid = :uid")
    suspend fun recordSyncFailed(uid: String, error: String?)

    @Query("DELETE FROM accounts WHERE uid NOT IN (:keep)")
    suspend fun deleteMissing(keep: List<String>)

    /**
     * How much of an account is actually on this device, and how far back it reaches.
     *
     * The honest answer to "why can't I find that mail from March" — this app pages backwards as
     * the user scrolls, so what is searchable is what has been paged, and nothing in the app said
     * so anywhere. One grouped query rather than one per account: the accounts screen draws every
     * row at once and this runs on the way in.
     *
     * `MIN(receivedAt)` ignores nulls in SQLite, which is the behaviour wanted rather than a
     * coincidence to work around: `receivedAt` is nullable to match the wire type, and a message
     * with no date cannot be the boundary of a date range.
     */
    @Query(
        """
        SELECT accountKey, COUNT(*) AS messages, MIN(receivedAt) AS oldestReceivedAt
        FROM emails GROUP BY accountKey
        """
    )
    fun observeCachedWindows(): Flow<List<CachedWindowRow>>
}

/** What one account's cache holds: how many messages, and the oldest one's date. */
data class CachedWindowRow(
    val accountKey: String,
    val messages: Int,
    val oldestReceivedAt: Long?,
)

@Dao
interface MailboxDao {
    @Query("SELECT * FROM mailboxes ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<MailboxEntity>>

    @Query("SELECT * FROM mailboxes WHERE accountKey = :accountKey")
    suspend fun forAccount(accountKey: String): List<MailboxEntity>

    /** The binding for a role in one account — what `inMailbox` filters need. */
    @Query("SELECT * FROM mailboxes WHERE accountKey = :accountKey AND role = :role LIMIT 1")
    suspend fun byRole(accountKey: String, role: String): MailboxEntity?

    /**
     * Every binding of one user-scoped label, across accounts.
     *
     * The reason `labelId` is indexed: applying a label the user sees as one thing means writing to
     * a different binding id in each account.
     */
    @Query("SELECT * FROM mailboxes WHERE labelId = :labelId")
    suspend fun bindingsOfLabel(labelId: String): List<MailboxEntity>

    /**
     * Binding id to collapse key, for one account.
     *
     * What a thread row's labels are resolved through when it is summarised. `labelId` where the
     * server sends one and the row's own uid otherwise, matching `MailboxEntity.labelKey()` —
     * duplicated here as SQL because doing it in Kotlin would mean loading every mailbox row per
     * page, and this is on the write path of every sync.
     */
    @Query(
        "SELECT mailboxId, COALESCE(labelId, uid) AS labelKey FROM mailboxes WHERE accountKey = :accountKey"
    )
    suspend fun bindingKeyRows(accountKey: String): List<BindingKey>

    @Upsert suspend fun upsert(mailboxes: List<MailboxEntity>)

    @Query("DELETE FROM mailboxes WHERE uid IN (:uids)") suspend fun delete(uids: List<String>)
}

@Dao
interface ThreadDao {
    @Query("SELECT * FROM threads WHERE uid = :uid") suspend fun byUid(uid: String): ThreadEntity?

    @Query("SELECT * FROM threads WHERE uid = :uid") fun observe(uid: String): Flow<ThreadEntity?>

    @Upsert suspend fun upsert(threads: List<ThreadEntity>)

    @Query("UPDATE threads SET isUnread = :unread WHERE uid = :uid")
    suspend fun setUnread(uid: String, unread: Boolean)

    @Query("UPDATE threads SET isFlagged = :flagged WHERE uid = :uid")
    suspend fun setFlagged(uid: String, flagged: Boolean)

    @Query("UPDATE threads SET snoozedUntil = :until WHERE uid = :uid")
    suspend fun setSnoozedUntil(uid: String, until: Long?)

    @Query("DELETE FROM threads WHERE uid IN (:uids)") suspend fun delete(uids: List<String>)

    /**
     * Whether this server classifies mail into inbox categories at all.
     *
     * What decides whether the sidebar draws the category group. There is no capability to ask for
     * — the categories are a plMail extension on Thread rather than a `using` URN — so the honest
     * signal is whether any conversation this device has actually seen carries one. A plMail that
     * predates the extension leaves every row null and the group never appears, which is the
     * degradation that matters: this client has to stay usable against an older server rather than
     * offering five destinations that are permanently empty.
     *
     * `LIMIT 1` inside an `EXISTS` rather than a count: the answer is a boolean and the table has a
     * row per cached conversation.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM threads WHERE category IS NOT NULL LIMIT 1)")
    fun observeHasCategories(): Flow<Boolean>

    /**
     * The same answer once, for the sync path, which has no business collecting a flow.
     *
     * What decides whether an *unclassified* conversation may interrupt — see
     * [de.plmail.core.data.notifyScopeKeys]. False on a device that has cached nothing yet, which
     * is the safe direction: everything in the inbox counts as Primary until the first sync proves
     * the server classifies, rather than a first run that says nothing at all.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM threads WHERE category IS NOT NULL LIMIT 1)")
    suspend fun hasCategories(): Boolean

    /**
     * Which inbox categories hold a conversation at all.
     *
     * What decides whether a category is offered as a destination — the same rule the web applies
     * to its tab strip, so the phone and the browser show the same set rather than each being
     * defensibly different. Membership rather than unread: a tab with fifty read promotions in it
     * is a tab, and one with nothing in it is a promise the server cannot keep.
     *
     * Narrowed by `isInInbox` because a category is an *inbox* idea: the server never unclassifies
     * mail, so a conversation dragged to Trash keeps saying "promotions" and would otherwise hold a
     * tab open over an empty list. That used to be a join against the unified-inbox feed, which
     * stopped being true the moment nothing paged that feed — see [ThreadEntity.isInInbox].
     */
    @Query(
        """
        SELECT DISTINCT category FROM threads
        WHERE category IS NOT NULL AND isInInbox = 1
        """
    )
    fun observePopulatedCategories(): Flow<List<String>>

    /**
     * The inbox conversations the server still calls **new**, newest first.
     *
     * What the digest above Primary and the sidebar's dots are both drawn from. `isNew` is the
     * server's own marker — never displayed to this user, and inside plMail's newness window — so
     * this asks no question about time and holds no window of its own. That is the whole change
     * from the local approximation it replaced: two clients now read one fact instead of each
     * keeping a private guess that could only agree by coincidence.
     *
     * Joined against the inbox feed rather than read off the thread row alone, for the same reason
     * [observePopulatedCategories] is: the server never unclassifies mail, so a conversation
     * dragged to Trash keeps saying "promotions" and would otherwise be announced from the bin.
     *
     * Not grouped in SQL. The digest needs the senders behind each count as well as the count, and
     * the result is bounded by what is genuinely new rather than by the size of the mailbox.
     */
    @Query(
        """
        SELECT * FROM threads
        WHERE category IS NOT NULL AND isNew = 1 AND isInInbox = 1
        ORDER BY latestReceivedAt DESC
        """
    )
    fun observeNew(): Flow<List<ThreadEntity>>

    /**
     * The conversations whose displays have not been reported to the server yet.
     *
     * The read half of [de.plmail.core.data.ShownThreads]: rows this device has drawn while the
     * server still believes nobody has seen them. Bounded because it can only ever hold what is new
     * — an account with nothing new answers with nothing, which is the ordinary case and has to
     * cost one indexed read.
     */
    @Query(
        """
        SELECT * FROM threads
        WHERE accountKey = :accountKey AND isNew = 1 AND threadId IN (:threadIds)
        """
    )
    suspend fun stillNew(accountKey: String, threadIds: List<String>): List<ThreadEntity>

    /**
     * Retires the marker locally, for conversations the server has been told about.
     *
     * Written straight away rather than waiting for the next sync, and that is what stops the
     * digest flickering: the rows leave the bundle the moment the report is accepted, instead of
     * staying until an `Email/changes` happens to re-fetch the conversation.
     */
    @Query(
        "UPDATE threads SET isNew = 0 WHERE accountKey = :accountKey AND threadId IN (:threadIds)"
    )
    suspend fun clearNew(accountKey: String, threadIds: List<String>)
}

@Dao
interface EmailDao {
    @Query("SELECT * FROM emails WHERE uid = :uid") suspend fun byUid(uid: String): EmailEntity?

    @Query(
        "SELECT * FROM emails WHERE threadId = :threadId AND accountKey = :accountKey ORDER BY receivedAt"
    )
    fun observeThread(accountKey: String, threadId: String): Flow<List<EmailEntity>>

    /**
     * The same rows, once, for summarising a thread after a page has been written.
     *
     * A suspend read rather than the flow above: the summary is computed inside the same
     * transaction as the write, and collecting a flow there would deadlock on the writer.
     */
    @Query("SELECT * FROM emails WHERE threadId = :threadId AND accountKey = :accountKey")
    suspend fun inThread(accountKey: String, threadId: String): List<EmailEntity>

    @Upsert suspend fun upsert(emails: List<EmailEntity>)

    @Upsert suspend fun upsertBody(body: EmailBodyEntity)

    @Query("SELECT * FROM email_bodies WHERE uid = :uid")
    suspend fun body(uid: String): EmailBodyEntity?

    /**
     * The newest messages whose bodies are not on the device, for the prefetcher to go and get.
     *
     * Newest first because that is the order they will be opened in. The `LEFT JOIN` rather than a
     * `NOT IN` subquery so SQLite can walk the body table's primary key rather than materialising
     * every cached uid — this runs over the whole account on every periodic sync.
     */
    @Query(
        """
        SELECT emails.* FROM emails
        LEFT JOIN email_bodies ON emails.uid = email_bodies.uid
        WHERE emails.accountKey = :accountKey AND email_bodies.uid IS NULL
        ORDER BY emails.receivedAt DESC LIMIT :limit
        """
    )
    suspend fun withoutBodies(accountKey: String, limit: Int): List<EmailEntity>

    /**
     * Marks bodies as used, so eviction is by last read rather than by when they were downloaded.
     *
     * Without this, prefetching and pruning fight each other: a conversation reread every week
     * would still be dropped sixty days after the one fetch that brought it down, and then
     * prefetched again — the cache would churn hardest on exactly the mail that is worth keeping.
     */
    @Query("UPDATE email_bodies SET fetchedAt = :at WHERE uid IN (:uids)")
    suspend fun touchBodies(uids: List<String>, at: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttachments(attachments: List<AttachmentEntity>)

    @Query("SELECT * FROM attachments WHERE emailUid = :emailUid")
    suspend fun attachments(emailUid: String): List<AttachmentEntity>

    @Query("DELETE FROM emails WHERE uid IN (:uids)") suspend fun delete(uids: List<String>)

    /**
     * Which of these the cache already holds.
     *
     * The question a sync asks to work out what is *new to this device*, which is a different
     * question from what the server calls created — a re-indexed message, or one that arrives on a
     * server that reports every touched row as created, is old mail as far as the person holding
     * the phone is concerned. Returning the known ids rather than the unknown ones keeps the query
     * a plain `IN` over the primary key.
     */
    @Query("SELECT uid FROM emails WHERE uid IN (:uids)")
    suspend fun known(uids: List<String>): List<String>

    /**
     * Senders whose name or address matches, newest first.
     *
     * The composer's address book. Not `DISTINCT`: SQLite refuses a `DISTINCT` whose `ORDER BY`
     * names a column outside the projection, and ordering by recency is the whole value here — the
     * person mailed yesterday should outrank one mailed in 2019. Duplicates are collapsed in
     * Kotlin, where the address can be lower-cased first.
     */
    @Query(
        """
        SELECT fromName AS name, fromAddress AS address FROM emails
        WHERE fromAddress IS NOT NULL AND (fromAddress LIKE :pattern OR fromName LIKE :pattern)
        ORDER BY receivedAt DESC LIMIT :limit
        """
    )
    suspend fun sendersLike(pattern: String, limit: Int): List<AddressRow>

    /**
     * Recipient lists that mention the pattern, newest first.
     *
     * Recipients are stored as a JSON blob because nothing queries on them — except this, which
     * uses `LIKE` over the blob as a coarse filter and parses only the rows that survive it. The
     * alternative, a harvested-address table, would be the first row in this database that is not
     * reconstructible from the server.
     */
    @Query(
        """
        SELECT toJson, ccJson FROM emails
        WHERE toJson LIKE :pattern OR ccJson LIKE :pattern
        ORDER BY receivedAt DESC LIMIT :limit
        """
    )
    suspend fun recipientsLike(pattern: String, limit: Int): List<RecipientRow>

    /**
     * Frees space without losing the message; bodies re-fetch on demand.
     *
     * `fetchedAt` is bumped on every read — see [touchBodies] — so this is a least-recently-used
     * eviction rather than an age one, which is the difference between dropping mail nobody has
     * looked at since spring and dropping the thread someone reads every Monday.
     *
     * Flagged mail and drafts are spared unconditionally. Both are things the user has said are
     * theirs to come back to, and a draft in particular is not always re-fetchable in the form it
     * is held locally: evicting one is the only case here that can lose something.
     */
    @Query(
        """
        DELETE FROM email_bodies WHERE fetchedAt < :before
        AND uid NOT IN (SELECT uid FROM emails WHERE isFlagged = 1 OR isDraft = 1)
        """
    )
    suspend fun evictBodiesOlderThan(before: Long)
}

/** One mailbox binding and the label it collapses onto. */
data class BindingKey(val mailboxId: String, val labelKey: String)

/** One `{name, address}` pair, projected out of a message row rather than stored anywhere. */
data class AddressRow(val name: String?, val address: String)

/** The two recipient blobs of one message, for the composer's address book to parse. */
data class RecipientRow(val toJson: String?, val ccJson: String?)

@Dao
interface FeedDao {
    /**
     * The list, newest first, as a Paging source.
     *
     * Reads the materialised feed table rather than sorting every cached thread: the feed is the
     * persisted answer to one particular query as far down as it has been paged, which is what
     * makes a cold launch instant.
     */
    @Transaction
    @Query(
        """
        SELECT t.* FROM feed_entries f
        JOIN threads t ON t.accountKey = f.accountKey AND t.threadId = f.threadId
        WHERE f.feedId = :feedId
        ORDER BY f.sortDate DESC, f.uid DESC
        """
    )
    fun pagingSource(feedId: String): PagingSource<Int, ThreadEntity>

    @Upsert suspend fun upsertEntries(entries: List<FeedEntryEntity>)

    /**
     * How many rows one list holds.
     *
     * Distinguishes "synced, and empty" from "never synced", which decide opposite things about
     * whether to go to the network before the first frame.
     */
    @Query("SELECT COUNT(*) FROM feed_entries WHERE feedId = :feedId")
    suspend fun count(feedId: String): Int

    /**
     * The same count, observed.
     *
     * What the list asks to decide whether it is genuinely empty. Paging's own item count cannot
     * answer that: it trails this table by one Room invalidation, and a list that reads it while it
     * trails tells someone a label holds nothing at the exact moment its rows were written.
     */
    @Query("SELECT COUNT(*) FROM feed_entries WHERE feedId = :feedId")
    fun observeCount(feedId: String): Flow<Int>

    @Query("DELETE FROM feed_entries WHERE feedId = :feedId") suspend fun clearFeed(feedId: String)

    /**
     * Drops one conversation from one list.
     *
     * What archiving does to the inbox: the list reads this table, so leaving the row would keep
     * showing a conversation the user has just archived until the next refresh.
     */
    @Query("DELETE FROM feed_entries WHERE feedId = :feedId AND uid = :feedId || '#' || :threadUid")
    suspend fun clearThread(feedId: String, threadUid: String)

    @Query("DELETE FROM feed_entries WHERE feedId = :feedId AND accountKey = :accountKey")
    suspend fun clearAccountFromFeed(feedId: String, accountKey: String)

    @Query("SELECT * FROM feed_cursors WHERE feedId = :feedId AND accountKey = :accountKey")
    suspend fun cursor(feedId: String, accountKey: String): FeedCursorEntity?

    @Upsert suspend fun upsertCursor(cursor: FeedCursorEntity)

    /**
     * Throws away how deep one list has been paged.
     *
     * Scoped to the feed, and the account-scoped version this replaced was a real bug: refreshing
     * the inbox deleted the cursors of every label and category list that account contributes to,
     * so the next append in any of them started from the top and rewrote rows the user had already
     * scrolled past.
     */
    @Query("DELETE FROM feed_cursors WHERE feedId = :feedId")
    suspend fun clearCursors(feedId: String)

    /**
     * Every list this account has actually been paged into.
     *
     * What "live" means for a list that has to receive newly synced mail, and it keys on cursors
     * rather than on entries on purpose: the two answers differ precisely for a list that has been
     * paged and is genuinely empty — a Promotions tab with nothing in it yet — which is exactly the
     * list that must not be skipped when a promotion finally arrives.
     */
    @Query("SELECT DISTINCT feedId FROM feed_cursors WHERE accountKey = :accountKey")
    suspend fun feedsPagedBy(accountKey: String): List<String>
}

@Dao
interface CalendarDao {
    /**
     * Every calendar, in the server's own order.
     *
     * Invisible ones included: `isVisible` is a display preference the server does not act on, so
     * whether to draw one is a question for whatever is drawing, and a DAO that filtered would make
     * a calendar ticked back on look empty until the next refresh.
     */
    @Query("SELECT * FROM calendars ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<CalendarEntity>>

    @Query("SELECT * FROM calendars WHERE accountKey = :accountKey")
    suspend fun forAccount(accountKey: String): List<CalendarEntity>

    @Query("SELECT * FROM calendars WHERE uid = :uid")
    suspend fun byUid(uid: String): CalendarEntity?

    @Upsert suspend fun upsert(calendars: List<CalendarEntity>)

    @Query("DELETE FROM calendars WHERE uid IN (:uids)") suspend fun delete(uids: List<String>)
}

@Dao
interface CalendarEventDao {
    /**
     * The agenda: every occurrence from a date onwards, earliest first.
     *
     * One join rather than three reads, for the same reason the feed denormalises: a list draws
     * fifty of these and resolving each row's series and calendar separately is a query per row.
     *
     * `isAllDay DESC` puts the all-day rows at the top of their day, which is where every calendar
     * on every platform puts them — they have no time to sort by, and interleaving them with timed
     * events by a start of 00:00 puts "Sommerfest" above an 08:00 meeting for a reason that reads
     * as a bug.
     */
    @Query(
        """
        SELECT o.date AS date, o.startLocal AS startLocal, o.endLocal AS endLocal,
               o.zoneId AS zoneId, o.isAllDay AS isAllDay, o.eventKey AS eventKey,
               e.eventId AS eventId, e.eventUid AS eventUid,
               COALESCE(o.titleOverride, e.title) AS title,
               e.location AS location, e.description AS description, e.status AS status,
               e.isRecurring AS isRecurring, e.calendarKey AS calendarKey,
               c.name AS calendarName, c.color AS calendarColor, c.isVisible AS calendarIsVisible
        FROM calendar_occurrences o
        JOIN calendar_events e ON e.uid = o.eventKey
        LEFT JOIN calendars c ON c.uid = o.calendarKey
        WHERE o.date >= :from
        ORDER BY o.date, o.isAllDay DESC, o.startLocal, e.eventId
        LIMIT :limit
        """
    )
    fun observeAgenda(from: String, limit: Int): Flow<List<AgendaRow>>

    /** The same rows bounded at both ends, for a month grid or a week. [to] is exclusive. */
    @Query(
        """
        SELECT o.date AS date, o.startLocal AS startLocal, o.endLocal AS endLocal,
               o.zoneId AS zoneId, o.isAllDay AS isAllDay, o.eventKey AS eventKey,
               e.eventId AS eventId, e.eventUid AS eventUid,
               COALESCE(o.titleOverride, e.title) AS title,
               e.location AS location, e.description AS description, e.status AS status,
               e.isRecurring AS isRecurring, e.calendarKey AS calendarKey,
               c.name AS calendarName, c.color AS calendarColor, c.isVisible AS calendarIsVisible
        FROM calendar_occurrences o
        JOIN calendar_events e ON e.uid = o.eventKey
        LEFT JOIN calendars c ON c.uid = o.calendarKey
        WHERE o.date >= :from AND o.date < :to
        ORDER BY o.date, o.isAllDay DESC, o.startLocal, e.eventId
        """
    )
    fun observeBetween(from: String, to: String): Flow<List<AgendaRow>>

    @Query("SELECT * FROM calendar_events WHERE uid = :uid")
    suspend fun byUid(uid: String): CalendarEventEntity?

    @Upsert suspend fun upsertEvents(events: List<CalendarEventEntity>)

    @Upsert suspend fun upsertOccurrences(occurrences: List<CalendarOccurrenceEntity>)

    @Query(
        "SELECT * FROM calendar_occurrences WHERE date >= :from AND date < :to " +
            "ORDER BY date, isAllDay DESC, startLocal"
    )
    suspend fun occurrencesBetween(from: String, to: String): List<CalendarOccurrenceEntity>

    /**
     * Empties one window before the refresh writes it again.
     *
     * The whole reconcile, in one statement. There is no `CalendarEvent/changes` and no delta on
     * this surface — the state is the constant `"fixed"` — so a refresh cannot be told what left;
     * it can only be told what is there now. Deleting the window and writing the answer is
     * therefore the *only* way an occurrence that has been moved, excluded or deleted stops being
     * drawn, and it is safe because these rows are derived from a query that has just been re-run.
     *
     * Deliberately not a `NOT IN (:keep)`: a month of a busy calendar is easily past SQLite's
     * 999-variable ceiling, and a delete that silently applies to the first 999 is worse than no
     * delete at all.
     */
    @Query("DELETE FROM calendar_occurrences WHERE date >= :from AND date < :to")
    suspend fun clearOccurrencesBetween(from: String, to: String)

    /**
     * One series' days, as they stand.
     *
     * Read before a local write so the write can be taken back if the server refuses it — this
     * surface has no `ifInState`, so a rejection is the only conflict signal there is.
     */
    @Query("SELECT * FROM calendar_occurrences WHERE eventKey = :eventKey ORDER BY date")
    suspend fun occurrencesOf(eventKey: String): List<CalendarOccurrenceEntity>

    @Query("DELETE FROM calendar_occurrences WHERE eventKey = :eventKey")
    suspend fun clearOccurrencesOf(eventKey: String)

    @Query("DELETE FROM calendar_events WHERE uid = :uid") suspend fun deleteEvent(uid: String)

    /**
     * Which cached series are which meeting: the row key, the calendar it is on, and its JMAP uid.
     *
     * Read once per refresh so the answer can be reconciled against the cache on **uid** rather
     * than on the server id alone — see `CalendarRepository`'s dedup. Three short columns rather
     * than `SELECT *`, because none of the rest is needed to decide identity and this is read on
     * every window change.
     *
     * The whole account rather than the refreshed window, and deliberately not an `IN (:uids)` over
     * what the query just answered: a busy month is easily past SQLite's 999-variable ceiling, and
     * the row a stale copy has to be recognised against may sit on a day outside the window that
     * exposed the duplicate.
     */
    @Query(
        "SELECT uid, calendarKey, eventUid FROM calendar_events " +
            "WHERE accountKey = :accountKey AND eventUid IS NOT NULL AND eventUid != ''"
    )
    suspend fun identities(accountKey: String): List<EventIdentity>

    /** Drops series rows by key. Chunk the argument; see [identities] for the ceiling. */
    @Query("DELETE FROM calendar_events WHERE uid IN (:uids)")
    suspend fun deleteEvents(uids: List<String>)

    /** Drops every day of several series at once. Chunk the argument. */
    @Query("DELETE FROM calendar_occurrences WHERE eventKey IN (:eventKeys)")
    suspend fun clearOccurrencesOfEvents(eventKeys: List<String>)

    /**
     * Drops series rows nothing places any more.
     *
     * This is where "left the window" and "was deleted on the server" are deliberately *not*
     * distinguished, because a windowed query cannot tell them apart and the day view is not asking
     * which it was: both mean the event is not on the days that were just refreshed. A series with
     * occurrences in some other cached window keeps its row; one with none left is unreachable —
     * nothing joins to it and nothing can ever delete it later.
     */
    @Query(
        "DELETE FROM calendar_events WHERE uid NOT IN (SELECT eventKey FROM calendar_occurrences)"
    )
    suspend fun deleteUnplacedEvents()
}

/**
 * A cached series' two identities and where it lives.
 *
 * [uid] is this cache's key and is built from the **server id**, which is local to one server and
 * is re-minted whenever a remote calendar's mirror re-imports an event. [eventUid] is JMAP's own
 * uid, which is not — it is the same string on both copies of one meeting, which is what makes it
 * the only key a duplicate can be recognised on.
 */
data class EventIdentity(val uid: String, val calendarKey: String, val eventUid: String?)

/**
 * One occurrence as a list draws it: the day, the time, and the series and calendar it came from.
 *
 * A projection rather than a relation, so a row costs one object and no lazy loads.
 */
data class AgendaRow(
    val date: String,
    val startLocal: String?,
    val endLocal: String?,
    val zoneId: String?,
    val isAllDay: Boolean,
    val eventKey: String,
    val eventId: String,
    val title: String,
    val location: String?,
    val description: String?,
    val status: String?,
    val isRecurring: Boolean,
    val calendarKey: String,
    /** Null only if the calendar row is missing, which a refresh repairs. */
    val calendarName: String?,
    val calendarColor: String?,
    val calendarIsVisible: Boolean?,
    /**
     * JMAP's own `uid` off the **series**, and what tells two rows they are one meeting.
     *
     * One meeting reaches plMail twice by two honest routes at once — extracted from its invitation
     * onto the account's own calendar, and mirrored from a provider onto a connected one — and both
     * rows are correct. `CalendarEvent/query` deliberately does not collapse them (a protocol
     * answers with the ids of the rows it holds, and collapsing would hand a client ids it cannot
     * then `get`), so the collapse is the app's, and this is the only key it may be done on. See
     * `EventCluster` in `:feature:calendar`, and `App\Service\Calendar\EventClusterer` on the
     * server, which is the same rule.
     *
     * Nullable because a `properties`-filtered get may never have asked for it, and because the
     * cache predates this column being read by anything. A row without one is never merged with
     * anything — see `EventCluster`.
     *
     * Last in the list rather than beside [eventId] so every existing construction site still
     * compiles positionally; the two are different identities and only this one is stable across
     * servers.
     */
    val eventUid: String? = null,
)

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identities WHERE accountKey = :accountKey ORDER BY sortIndex")
    suspend fun forAccount(accountKey: String): List<IdentityEntity>

    @Query("SELECT * FROM identities ORDER BY accountKey, sortIndex")
    fun observeAll(): Flow<List<IdentityEntity>>

    @Upsert suspend fun upsert(identities: List<IdentityEntity>)

    @Query("DELETE FROM identities WHERE accountKey = :accountKey")
    suspend fun deleteForAccount(accountKey: String)
}
