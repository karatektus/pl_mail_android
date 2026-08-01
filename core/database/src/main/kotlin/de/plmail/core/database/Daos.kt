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

    /** Frees space without losing the message; bodies re-fetch on demand. */
    @Query("DELETE FROM email_bodies WHERE fetchedAt < :before")
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

    @Query("DELETE FROM feed_cursors WHERE accountKey = :accountKey")
    suspend fun clearCursors(accountKey: String)
}

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
