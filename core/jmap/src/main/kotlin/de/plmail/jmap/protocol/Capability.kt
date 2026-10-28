package de.plmail.jmap.protocol

/**
 * Capability URNs.
 *
 * [PUSH] and the calendars URNs behave **oppositely** in the one place it matters. [PUSH] must
 * never appear in a request's `using` list — the server advertises it in the session but rejects it
 * as a declared capability, failing the whole request with `unknownCapability`. [CALENDARS] is the
 * reverse: a calendar method call that omits it is refused.
 *
 * So the advertised set and the usable set are deliberately different things, and which side a
 * vendor URN falls on cannot be guessed from the URN. [USING_MAIL] and
 * [de.plmail.jmap.calendar.CalendarDialect.using] are what requests actually declare.
 */
object Capability {
    const val CORE = "urn:ietf:params:jmap:core"
    const val MAIL = "urn:ietf:params:jmap:mail"
    const val SUBMISSION = "urn:ietf:params:jmap:submission"
    const val PUSH = "urn:plmail:params:jmap:push"

    /**
     * Calendars, under the IETF draft's URN (draft-ietf-jmap-calendars) and in the draft's shape:
     * an event's `calendarIds` map, one `recurrenceRule`, `baseEventId` on an occurrence.
     *
     * What the app reads whenever the session advertises it. See
     * [de.plmail.jmap.calendar.CalendarDialect] for the server that does not.
     */
    const val CALENDARS = "urn:ietf:params:jmap:calendars"

    /**
     * plMail's own calendars URN, from before the server spoke the IETF one, and on its way out
     * (pl_mail#409).
     *
     * Read only from a server that does not advertise [CALENDARS] — every plMail released before
     * the IETF URN arrived. It is the same methods in plMail's earlier shape, translated into the
     * draft's at the edge by [de.plmail.jmap.calendar.CalendarDialect.PLMAIL], so deleting this
     * constant and that one enum entry is the whole of retiring it.
     */
    const val PLMAIL_CALENDARS = "urn:plmail:params:jmap:calendars"
    const val CONTACTS = "urn:plmail:params:jmap:contacts"
    const val APPEARANCE = "urn:plmail:params:jmap:appearance"

    /**
     * Backfill progress, and the one URN here that is never declared.
     *
     * It advertises numbers rather than methods — there is nothing to call under it — so it appears
     * in the session's `accountCapabilities` and in no request's `using` list. The name is older
     * than what it now carries: it published a retention window too, until the server stopped
     * having one. See [Session.syncWindow].
     */
    const val SYNC = "urn:plmail:params:jmap:sync"

    /**
     * The inbox tabs as the reader arranged them on the web: `InboxTab/get` and `/changes`,
     * `Thread.inboxTab`, the `threadInboxTab` filter and `Thread/set { inboxTab }`.
     *
     * Advertised on **every account** rather than only at the top level, though the tabs are the
     * user's: `Thread.inboxTab` is account-scoped and so is the change log a push is sent from. Its
     * absence is the signal that the server predates the extension, and the app then draws the five
     * built-in categories off `Thread.category` exactly as it did before.
     */
    const val INBOX_TABS = "urn:plmail:params:jmap:inboxtabs"

    /**
     * End-to-end encryption (#9): `Keyring/get`, the `ContactKey` and `DecryptedMail` methods, and
     * the constants an app needs to open what the browser wrapped — the KDF's info string and the
     * purpose of every wrapping. Per user at the top level; each account's entry says only whether
     * the account keeps decrypted copies of encrypted mail.
     */
    const val KEYS = "urn:plmail:params:jmap:keys"

    /** The usual declaration for a mail request. */
    val USING_MAIL = listOf(CORE, MAIL)

    /** For requests that also submit mail. */
    val USING_MAIL_SUBMISSION = listOf(CORE, MAIL, SUBMISSION)

    /**
     * For `Contact/autocomplete`.
     *
     * Mail is absent for the same reason it is absent from a calendar request: the method reads the
     * harvested address book and nothing from the mail capability. The server happens to answer
     * without this declared — verified against the 8002 stack — which is exactly the kind of
     * leniency that stops being true on the instance where the extension is switched off.
     */
    val USING_CONTACTS = listOf(CORE, CONTACTS)

    /**
     * For the `InboxTab` methods and a `Thread/set` that files a conversation in a tab.
     *
     * Only ever sent to an account that advertises [INBOX_TABS]: a server older than the extension
     * would refuse the whole request with `unknownCapability`, so a request that might reach one
     * declares [USING_MAIL] and does not call these methods at all.
     */
    val USING_INBOX_TABS = listOf(CORE, MAIL, INBOX_TABS)

    /** For `Keyring/get`. Per user like appearance, so no mail capability is involved. */
    val USING_KEYS = listOf(CORE, KEYS)

    /** For `Appearance/get` and `Appearance/set`. Per user, so no mail capability is involved. */
    val USING_APPEARANCE = listOf(CORE, APPEARANCE)
}
