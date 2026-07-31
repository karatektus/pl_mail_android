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

    @Query("UPDATE accounts SET lastSyncedAt = :at, lastSyncError = :error WHERE uid = :uid")
    suspend fun recordSync(uid: String, at: Long?, error: String?)

    @Query("DELETE FROM accounts WHERE uid NOT IN (:keep)")
    suspend fun deleteMissing(keep: List<String>)
}

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

    /** Frees space without losing the message; bodies re-fetch on demand. */
    @Query("DELETE FROM email_bodies WHERE fetchedAt < :before")
    suspend fun evictBodiesOlderThan(before: Long)
}

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

    @Query("DELETE FROM feed_entries WHERE feedId = :feedId") suspend fun clearFeed(feedId: String)

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
