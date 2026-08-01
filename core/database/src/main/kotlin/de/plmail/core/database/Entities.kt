package de.plmail.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ---------------------------------------------------------------------------
// THE RULE THIS WHOLE SCHEMA DEPENDS ON
//
// EVERYTHING HERE IS A CACHE. Every row is reconstructible from the server, and
// nothing may be stored here that is not. The only irreplaceable state the app
// holds is the server address and credential, which live behind the Keystore,
// plus local preferences in DataStore.
//
// That is a constraint, not an observation. It is what licenses the recovery
// strategy — on any migration or corruption failure, delete the store and
// re-sync — which in turn means no migration ever has to preserve data. It
// stays true only as long as nobody adds a column holding something the server
// does not know about. Wanting to is the signal to ask for a server change
// instead.
// ---------------------------------------------------------------------------

/**
 * Identity across the store.
 *
 * JMAP ids are unique only *within* an account, and one credential reaches several accounts, so
 * every row is keyed by a composite. Getting this wrong does not throw — it silently merges two
 * people's mail, because both accounts number their messages from 1.
 */
object StoreKey {
    fun account(server: String, accountId: String): String = "$server/$accountId"

    fun objectKey(accountKey: String, id: String): String = "$accountKey#$id"
}

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val uid: String,
    val serverId: String,
    /** The JMAP account id. Always a string on the wire. */
    val accountId: String,
    /** The address as the server reports it. */
    val name: String,
    val isPersonal: Boolean = true,
    val isReadOnly: Boolean = false,
    val sortIndex: Int = 0,
    // One sync cursor per JMAP object type.
    val emailState: String? = null,
    val threadState: String? = null,
    val mailboxState: String? = null,
    val lastSyncedAt: Long? = null,
    /**
     * The last failure, kept so the diagnostics screen can show it. Users self-host: when something
     * breaks, they are the one who has to fix it.
     */
    val lastSyncError: String? = null,
)

@Entity(tableName = "mailboxes", indices = [Index("accountKey"), Index("labelId")])
data class MailboxEntity(
    @PrimaryKey val uid: String,
    val accountKey: String,
    /** The JMAP Mailbox id — a per-account label *binding* id. */
    val mailboxId: String,
    /**
     * The user-scoped label id, from plMail's `labelId` extension.
     *
     * Indexed because it is the join key for collapsing one label across accounts into a single
     * sidebar row. Never accepted as input anywhere.
     */
    val labelId: String? = null,
    /** Leaf name only; hierarchy is [parentId]. */
    val name: String,
    val parentId: String? = null,
    val role: String? = null,
    /**
     * The label's colour as the **wire token** the server sent — `blue`, `amber` — or null.
     *
     * Stored uninterpreted, for the same reason `role` is: the token is resolved to an actual
     * colour by the design system, per theme, and a value this build does not recognise has to
     * survive the cache so that an app update can start drawing it without a resync. Storing a
     * resolved hex here would freeze one theme's answer into a table that outlives the theme.
     */
    val color: String? = null,
    val sortOrder: Int = 0,
    val totalEmails: Int = 0,
    val unreadEmails: Int = 0,
    val totalThreads: Int = 0,
    val unreadThreads: Int = 0,
    val isSubscribed: Boolean = true,
    val mayRename: Boolean = false,
    val mayDelete: Boolean = false,
)

@Entity(tableName = "threads", indices = [Index("accountKey"), Index("latestReceivedAt")])
data class ThreadEntity(
    @PrimaryKey val uid: String,
    val accountKey: String,
    val threadId: String,
    // Denormalised list-row fields, recomputed on every write.
    //
    // Load-bearing rather than an optimisation: the unified inbox sorts across
    // accounts on latestReceivedAt with no joins and no lazy loads. Recomputing
    // these on write is cheap; deriving them on read for fifty rows at 120fps
    // is not.
    val latestReceivedAt: Long = 0,
    val subject: String? = null,
    val participantsSummary: String = "",
    /**
     * The newest sender's address, kept beside the display name because the list draws an avatar
     * coloured by a hash of it. Hashing the display name would recolour the same person whenever
     * they changed how their client spells it.
     */
    val participantsAddress: String? = null,
    val snippet: String = "",
    val messageCount: Int = 1,
    val isUnread: Boolean = false,
    val isFlagged: Boolean = false,
    val hasAttachment: Boolean = false,
    /**
     * When a snoozed conversation is due back. The server clears an elapsed snooze, so a value here
     * is always still pending — nothing has to re-check the clock to know that.
     */
    val snoozedUntil: Long? = null,
    /**
     * Which Gmail-style inbox tab this conversation belongs to, as the server's own token, or null
     * when it has never been classified.
     *
     * The **resolved** value off `Thread/get`, not any one message's — a tab holds conversations,
     * and the server folds a thread's messages most-recent-wins. It arrives free: every page and
     * every delta sync already back-references a `Thread/get` off the message get, for snooze.
     *
     * Denormalised here even though the tabs are a *server-side* query and the feed table already
     * holds the answer, because the two are different questions. The feed says which conversations
     * are in a tab; this says which tab a conversation is in — which is what lets the sidebar know
     * whether this server classifies mail at all, and therefore whether to draw the category
     * entries. A server without the extension leaves this null everywhere and the group never
     * appears, which is the degradation that matters: this app has to keep working against a plMail
     * that predates the feature.
     *
     * **Null is not primary.** The server's own inbox query puts an unclassified conversation in no
     * tab, and treating null as primary here would put mail on the phone's Primary tab that the web
     * does not have.
     */
    val category: String? = null,
    /**
     * Which labels this conversation carries, as collapse *keys*, comma-separated.
     *
     * Denormalised onto the row for the same reason every other field here is: the list draws fifty
     * of these and a join per row to work out its labels is the one thing this table exists to
     * avoid. Written when the row is summarised, from the `mailboxIds` of the messages in it.
     *
     * **Keys, not names.** The key is plMail's `labelId`, which is the same value in every account
     * that binds the label, so a conversation labelled "Work" in two accounts stores one entry
     * rather than two — the same collapse the sidebar performs, and for the same reason. Storing
     * the *names* instead would have been fewer moving parts and wrong twice over: a renamed label
     * would keep its old name on every cached row until each was re-synced, and the row would have
     * no way to tell a system role from one the user made, which is what decides whether a chip is
     * drawn at all.
     *
     * Comma-separated, matching `EmailEntity.mailboxIds` and the feed cursors' boundary ids. The
     * separator is safe for the same reason it is there: these are server-issued ids.
     */
    val labelKeys: String = "",
)

@Entity(
    tableName = "emails",
    indices = [Index("accountKey"), Index("threadId"), Index("receivedAt")],
)
data class EmailEntity(
    @PrimaryKey val uid: String,
    val accountKey: String,
    val emailId: String,
    val threadId: String? = null,
    val blobId: String? = null,
    /**
     * Nullable to match the wire type. The server falls back through receivedAt, sentAt, createdAt
     * so it is in practice always set — but any sort must put nulls last rather than assume.
     */
    val receivedAt: Long? = null,
    val sentAt: Long? = null,
    val subject: String? = null,
    /** Bare header ids, brackets stripped. A reply that omits them starts a new conversation. */
    val messageId: String? = null,
    val references: String? = null,
    val fromName: String? = null,
    val fromAddress: String? = null,
    /** Recipients as JSON. Shown but never queried on, so a blob costs nothing. */
    val toJson: String? = null,
    val ccJson: String? = null,
    val bccJson: String? = null,
    val preview: String = "",
    val size: Long = 0,
    val isSeen: Boolean = false,
    val isFlagged: Boolean = false,
    val isDraft: Boolean = false,
    val isAnswered: Boolean = false,
    val hasAttachment: Boolean = false,
    /** Mailbox (binding) ids, comma-separated. */
    val mailboxIds: String = "",
)

/**
 * Bodies live in their own table so a list query never loads a 400KB HTML string into memory, and
 * so bodies can be evicted without touching the message row that names them.
 */
@Entity(tableName = "email_bodies")
data class EmailBodyEntity(
    @PrimaryKey val uid: String,
    val textBody: String? = null,
    /** Always the server's sanitised HTML, never a raw column. */
    val htmlBody: String? = null,
    val fetchedAt: Long = 0,
)

@Entity(tableName = "attachments", indices = [Index("emailUid")])
data class AttachmentEntity(
    @PrimaryKey val uid: String,
    val emailUid: String,
    val accountKey: String,
    val partId: String,
    /** Opaque and namespaced server-side. Never parsed. */
    val blobId: String,
    val name: String? = null,
    val type: String = "application/octet-stream",
    val size: Long = 0,
    val cid: String? = null,
    val isInline: Boolean = false,
)

/**
 * One row of a materialised, ordered list.
 *
 * Not the same thing as "every thread I have cached". Sorting all threads by date would show every
 * thread from every mailbox; this table is the persisted answer to *this particular list, as far
 * down as I have paged*, which is what makes the list instant on cold launch and keeps scroll
 * position stable across a refresh.
 */
@Entity(tableName = "feed_entries", indices = [Index("feedId", "sortDate")])
data class FeedEntryEntity(
    @PrimaryKey val uid: String,
    val feedId: String,
    val sortDate: Long,
    val accountKey: String,
    val threadId: String,
    /** The newest email in the thread — the one the row renders from. */
    val emailId: String,
)

/** How deep one account has been paged within one feed. */
@Entity(tableName = "feed_cursors")
data class FeedCursorEntity(
    @PrimaryKey val uid: String,
    val feedId: String,
    val accountKey: String,
    /** The receivedAt of the last row emitted. The next page asks for everything before it. */
    val lastSortDate: Long? = null,
    /**
     * Ids already emitted at exactly [lastSortDate], comma-separated.
     *
     * `before` is a strict `<`, so messages sharing one second across a page boundary would be
     * dropped entirely. The next page asks for `before: lastSortDate + 1s` and filters these out.
     */
    val boundaryIds: String = "",
    val isExhausted: Boolean = false,
)

@Entity(tableName = "identities", indices = [Index("accountKey")])
data class IdentityEntity(
    @PrimaryKey val uid: String,
    val accountKey: String,
    val identityId: String,
    val name: String? = null,
    val email: String,
    val sortIndex: Int = 0,
)
