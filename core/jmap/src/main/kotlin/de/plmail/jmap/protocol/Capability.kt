package de.plmail.jmap.protocol

/**
 * Capability URNs.
 *
 * [PUSH] and [CALENDARS] are both plMail vendor extensions, and they behave **oppositely** in the
 * one place it matters. [PUSH] must never appear in a request's `using` list — the server
 * advertises it in the session but rejects it as a declared capability, failing the whole request
 * with `unknownCapability`. [CALENDARS] is the reverse: a calendar method call that omits it is
 * refused.
 *
 * So the advertised set and the usable set are deliberately different things, and which side a
 * vendor URN falls on cannot be guessed from the URN. [USING_MAIL] and [USING_CALENDARS] are what
 * requests actually declare.
 */
object Capability {
    const val CORE = "urn:ietf:params:jmap:core"
    const val MAIL = "urn:ietf:params:jmap:mail"
    const val SUBMISSION = "urn:ietf:params:jmap:submission"
    const val PUSH = "urn:plmail:params:jmap:push"
    const val CALENDARS = "urn:plmail:params:jmap:calendars"
    const val CONTACTS = "urn:plmail:params:jmap:contacts"
    const val APPEARANCE = "urn:plmail:params:jmap:appearance"

    /**
     * The sync window, and the one URN here that is never declared.
     *
     * It advertises numbers rather than methods — there is nothing to call under it — so it appears
     * in the session's `accountCapabilities` and in no request's `using` list. See
     * [Session.syncWindow].
     */
    const val SYNC = "urn:plmail:params:jmap:sync"

    /** The usual declaration for a mail request. */
    val USING_MAIL = listOf(CORE, MAIL)

    /** For requests that also submit mail. */
    val USING_MAIL_SUBMISSION = listOf(CORE, MAIL, SUBMISSION)

    /**
     * For `Contact/autocomplete`.
     *
     * Mail is absent for the same reason it is absent from [USING_CALENDARS]: the method reads the
     * harvested address book and nothing from the mail capability. The server happens to answer
     * without this declared — verified against the 8002 stack — which is exactly the kind of
     * leniency that stops being true on the instance where the extension is switched off.
     */
    val USING_CONTACTS = listOf(CORE, CONTACTS)

    /** For `Appearance/get` and `Appearance/set`. Per user, so no mail capability is involved. */
    val USING_APPEARANCE = listOf(CORE, APPEARANCE)

    /**
     * For `Calendar/get` and every `CalendarEvent` method.
     *
     * Mail is deliberately absent: a calendar request needs nothing from it, and declaring
     * capabilities a request does not use is how a client ends up broken on an instance where one
     * of them is switched off.
     */
    val USING_CALENDARS = listOf(CORE, CALENDARS)
}
