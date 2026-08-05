package de.plmail.jmap.calendar

import de.plmail.jmap.protocol.CalendarId
import kotlinx.serialization.Serializable

/**
 * A calendar, from plMail's vendor `urn:plmail:params:jmap:calendars` surface.
 *
 * There is no `Calendar/query`, no `Calendar/changes` and no `Calendar/set`: the whole list arrives
 * from one `Calendar/get` and the server reports its state as the literal `"fixed"`. A client that
 * waited for a changes call to tell it a calendar appeared would wait forever — re-fetch instead.
 */
@Serializable
data class Calendar(
    val id: CalendarId,
    val name: String = "",
    /**
     * `#rrggbb`, and deliberately **not** the token vocabulary [de.plmail.jmap.mail.Mailbox.color]
     * uses.
     *
     * The two surfaces disagree and that is the server's shape, not a mistake to normalise here: a
     * label's colour is a token that resolves per theme, a calendar's is a literal hex value the
     * user picked. Left uninterpreted for the same reason as the label token — `:core:jmap` is
     * Android-free and cannot see a colour.
     */
    val color: String? = null,
    val sortOrder: Int = 0,
    /**
     * The web sidebar's tick, published and **not acted on** by the server.
     *
     * `CalendarEvent/query` returns events from invisible calendars just the same, so this is a
     * display preference to honour client-side rather than a filter the server has already applied.
     */
    val isVisible: Boolean = true,
    val isDefault: Boolean = false,
    /** An IANA zone name. Events with no `timeZone` of their own float against this one. */
    val timeZone: String? = null,
    /**
     * plMail's extension. Seen values are `default` and `account`, and the vocabulary is **open** —
     * an unrecognised role must still draw, so this stays a string rather than an enum that would
     * drop a role added on the next server release.
     */
    val role: String? = null,
    /** plMail's extension: whether a remote calendar is being synced into this one. */
    val isSynced: Boolean = false,
    /**
     * The only thing that decides whether this calendar is writable.
     *
     * Not [isDefault], not [role], and not `isSynced` — a client that guessed from any of those
     * would offer a create on a calendar the server then refuses with `forbidden`, after the user
     * has typed the whole event.
     */
    val myRights: CalendarRights = CalendarRights(),
)

/**
 * Note the shape differs from [de.plmail.jmap.mail.MailboxRights]: there is a `mayUpdateAll` and no
 * per-keyword permissions, because an event has no flags to set.
 */
@Serializable
data class CalendarRights(
    val mayReadItems: Boolean = true,
    val mayAddItems: Boolean = false,
    val mayUpdateAll: Boolean = false,
    val mayRemoveItems: Boolean = false,
    /** False even on the default calendar — the server owns which calendars exist. */
    val mayDelete: Boolean = false,
)
