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

    /**
     * One day of one occurrence of one event series.
     *
     * `@` rather than a second `#`, because [objectKey] has already spent that character and a key
     * reading `.../13#10867#2026-08-07` cannot be split back apart by anything that does not
     * already know how many parts to expect. Nothing parses these — the separator is chosen so that
     * a human reading a row in a database browser can see where the series id ends.
     *
     * [at] is the occurrence's own start, and it is part of the key rather than a detail: one
     * series can land twice on one day — an hourly meeting, or an override moved onto a day that
     * already had one — and a key of series-and-date would keep whichever row was written last and
     * lose the other without any error. Not the server's occurrence id, deliberately: that id is
     * opaque, it only exists for a *recurring* series, and this key also has to name the days of a
     * one-off the phone has just created and not yet re-read.
     */
    fun occurrence(eventKey: String, date: String, at: String): String = "$eventKey@$date/$at"
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
     * Whether this conversation is still **new**: the server's own marker, not a local guess.
     *
     * Never put in front of the user, and arrived inside plMail's newness window — see
     * [de.plmail.jmap.mail.MailThread.isNew]. Deliberately not the same axis as [isUnread], and the
     * two are allowed to disagree: mail read on a laptop is still new to a device that has never
     * drawn its row.
     *
     * Denormalised here for the reason every other column on this row is: the digest above Primary
     * and the sidebar's dots both read it for every cached conversation, and a join per row is what
     * this table exists to avoid.
     *
     * This replaced a local approximation — a per-category "last opened" timestamp — which was the
     * best a client could do while the server published nothing. The approximation could only ever
     * agree with the browser by coincidence; this is the same fact both surfaces read.
     */
    val isNew: Boolean = false,
    /**
     * Whether this conversation is in the inbox.
     *
     * Denormalised for the same reason [category] is, and it replaced a join that quietly stopped
     * being true: the digest and the category list used to ask "is there a row for this
     * conversation in the unified-inbox feed", which held right up until the unified inbox stopped
     * being a destination anybody pages. `FeedProjection` only maintains feeds that are *live* — a
     * list somebody has opened — so a feed with no pager is a feed with no rows, and both queries
     * silently answered nothing at all.
     *
     * A column on the conversation cannot go stale that way. It is written by `storeEmails`, which
     * is on **both** paths that put a conversation on the device — the pagers and the delta sync —
     * so it is true from the first page rather than from the first sync after it.
     *
     * Membership, not category. A conversation the server has classified keeps its category
     * wherever it is filed, so the bin's promotions would be announced as inbox mail without this.
     */
    val isInInbox: Boolean = false,
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

/**
 * One calendar, from plMail's vendor calendar surface.
 *
 * Keyed like every other row even though calendars come from exactly **one** account — the server
 * answers `accountNotSupportedByMethod` on any other. The composite key is kept anyway because the
 * account it comes from is discovered from the session's own `primaryAccounts` and an instance is
 * free to nominate a different one than it nominates for mail; a table keyed on the calendar id
 * alone would silently merge two servers' calendars the day this app holds two connections.
 */
@Entity(tableName = "calendars", indices = [Index("accountKey")])
data class CalendarEntity(
    @PrimaryKey val uid: String,
    val accountKey: String,
    val calendarId: String,
    val name: String = "",
    /**
     * A literal `#rrggbb`, and deliberately **not** the token vocabulary [MailboxEntity.color]
     * carries.
     *
     * The two surfaces genuinely disagree: a label's colour is a token that resolves per theme, a
     * calendar's is a hex value the user picked in the web UI. Normalising one into the other here
     * would mean choosing which of the two products' answers is the real one.
     */
    val color: String? = null,
    val sortOrder: Int = 0,
    /**
     * The web sidebar's tick, and a display preference rather than a filter.
     *
     * `CalendarEvent/query` returns events from invisible calendars just the same, so the rows are
     * cached either way and this is what the UI honours when it draws them. Filtering at sync time
     * would mean a calendar ticked back on showing nothing until the next refresh.
     */
    val isVisible: Boolean = true,
    val isDefault: Boolean = false,
    /** An IANA zone. Events carrying no zone of their own are read in this one. */
    val timeZone: String? = null,
    val role: String? = null,
    val isSynced: Boolean = false,
    // myRights, flattened. The only thing that decides whether this calendar is
    // writable -- not isDefault, not role. A picker that guessed from either
    // offers a create the server then refuses with `forbidden`, after the user
    // has typed the whole event.
    val mayReadItems: Boolean = true,
    val mayAddItems: Boolean = false,
    val mayUpdateAll: Boolean = false,
    val mayRemoveItems: Boolean = false,
)

/**
 * One event **series**, projected to what a calendar draws.
 *
 * A series, not an occurrence: a weekly standup is one row here however many times it appears in a
 * month. Which days it lands on is [CalendarOccurrenceEntity], because the server is the only thing
 * allowed to answer that question — expanding a recurrence rule on the device is what makes a phone
 * and the web UI disagree at a DST boundary.
 *
 * A deliberate subset of what the server stores. Events carry arbitrary JSCalendar and this keeps
 * the fields a list and a detail sheet render; nothing here may be written back as though it were
 * the whole object.
 */
@Entity(tableName = "calendar_events", indices = [Index("accountKey"), Index("calendarKey")])
data class CalendarEventEntity(
    @PrimaryKey val uid: String,
    val accountKey: String,
    val eventId: String,
    /** The `calendars` row this belongs to, as its uid — what the agenda joins on for a colour. */
    val calendarKey: String,
    val calendarId: String,
    /**
     * JMAP's own `uid`, which is stable across servers where the id is not.
     *
     * Named `eventUid` rather than `uid` because that name is already the primary key here; the two
     * are different identities and a row carrying both under one name would be unreadable.
     */
    val eventUid: String? = null,
    val title: String = "",
    val description: String? = null,
    /**
     * A JSCalendar **LocalDateTime** — `2026-08-03T10:00:00`, no offset and no trailing `Z`.
     *
     * Stored as the server spells it rather than as an epoch, because it is not an instant: it
     * means "10:00 in [timeZone]", and converting it to a millisecond at sync time bakes in
     * whichever zone the device was in when it synced.
     */
    val start: String? = null,
    /** ISO 8601, e.g. `PT15M`, `P1D`. */
    val duration: String? = null,
    /**
     * The event's own zone, or null.
     *
     * Null here means *either* "inherits the calendar's zone" *or* "floating" — the wire
     * distinguishes an absent key from an explicit JSON null and a `CalendarEvent/get` collapses
     * both to nothing, so the distinction does not survive a read and cannot be stored. It survives
     * on the write path, where `EventTimeZone` has both cases. Placement therefore falls back to
     * the calendar's zone and then to the device's, which is right for the common case and is the
     * reason a genuinely floating event is not yet distinguishable here.
     */
    val timeZone: String? = null,
    /** The wire's `showWithoutTime`. An all-day event carries no zone at all. */
    val isAllDay: Boolean = false,
    /**
     * A place, as a plain label.
     *
     * One string rather than a map: the server keeps only `@type` and `name` from a Location, and
     * the key it files it under is its own choice on the way back.
     */
    val location: String? = null,
    val status: String? = null,
    /**
     * Derived server-side, and **not** `recurrenceRules != null`.
     *
     * An imported rule plMail cannot convert is stored verbatim and expands to a single occurrence,
     * so an event can carry a rule and still not recur. What a screen does with it: an editor shows
     * a read-only repeat line rather than the dropdown, and a delete warns that it takes the
     * series.
     */
    val isRecurring: Boolean = false,
    val sequence: Int = 0,
    /**
     * `recurrenceOverrides` as the raw JSON object the server sent, or null.
     *
     * Kept whole rather than decoded for the same reason `:core:jmap` keeps it whole: an override
     * is a patch that may name any writable property, and a fixed shape would silently drop
     * whatever this build has not heard of. It is read at refresh time to answer "what time is this
     * occurrence, and is it cancelled" — which is reading published data, not re-deriving
     * recurrence.
     */
    val recurrenceOverrides: String? = null,
)

/**
 * That an event is on a day, and when on that day it is.
 *
 * The whole reason this table exists is that the client is forbidden from expanding recurrence
 * rules: day membership is learned from the server, one windowed query per day, and cached here.
 * Pure derived data — dropping it costs one refresh, which is what keeps it inside the schema's
 * "everything is a cache" rule.
 *
 * Local wall-clock times rather than instants, deliberately. An event with no zone is meant to
 * happen at the same clock time wherever the reader is, so resolving it to an instant at sync time
 * and storing that is precisely the bug that makes a birthday move when the user travels — and a
 * day view places occurrences by local time anyway, which is why [startLocal] is also the sort key.
 */
@Entity(
    tableName = "calendar_occurrences",
    indices = [Index("date"), Index("eventKey"), Index("accountKey")],
)
data class CalendarOccurrenceEntity(
    @PrimaryKey val uid: String,
    /** The [CalendarEventEntity] uid this is an occurrence of. */
    val eventKey: String,
    val accountKey: String,
    val calendarKey: String,
    /** ISO local date, `2026-08-07`. Sorts correctly as a string, which is why it is one. */
    val date: String,
    /** LocalDateTime, as the wire spells it. The occurrence's own start, after any override. */
    val startLocal: String? = null,
    /** [startLocal] plus the occurrence's duration, or null when the duration was unreadable. */
    val endLocal: String? = null,
    /**
     * The zone [startLocal] is read in — the event's, else the calendar's — or null for an event
     * that resolves against the device.
     */
    val zoneId: String? = null,
    val isAllDay: Boolean = false,
    /**
     * The title this one occurrence carries, when an override renamed it.
     *
     * Null means "the series' title", which is the ordinary case. Stored rather than resolved at
     * draw time because the override that carries it is keyed by the occurrence's original start,
     * and a list would otherwise have to parse the series' whole override map per row.
     */
    val titleOverride: String? = null,
)

@Entity(tableName = "identities", indices = [Index("accountKey")])
data class IdentityEntity(
    @PrimaryKey val uid: String,
    val accountKey: String,
    val identityId: String,
    val name: String? = null,
    val email: String,
    /**
     * The sign-off this address signs with, as the server's own HTML, or empty for one that signs
     * with nothing.
     *
     * Stored rather than fetched when the composer opens, because the composer has to insert it
     * into the body on the *first* frame: a signature that arrives after a round trip appears under
     * the cursor of somebody who has already started typing. `Identity/get` is refreshed beside
     * `Mailbox/get` on every directory refresh, so this is at most a minute stale.
     *
     * Empty is a real answer and not a missing one — plMail distinguishes an alias that inherits
     * the account's signature from one explicitly set to none, and resolves that *before* it
     * answers, so what arrives here is already the final value for this address.
     */
    val htmlSignature: String = "",
    val sortIndex: Int = 0,
)
