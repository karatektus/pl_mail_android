package de.plmail.jmap.protocol

/**
 * Capability URNs.
 *
 * [PUSH] is a plMail vendor extension carrying the VAPID public key, because RFC 8620 defines no
 * standard place for one and a client cannot create a Web Push subscription without it.
 *
 * **It must never appear in a request's `using` list.** The server advertises it in the session but
 * does not accept it as a declared capability, and sending it fails the whole request with
 * `unknownCapability` — so the advertised set and the usable set are deliberately different things.
 * [USING_MAIL] is what a mail request actually declares.
 */
object Capability {
    const val CORE = "urn:ietf:params:jmap:core"
    const val MAIL = "urn:ietf:params:jmap:mail"
    const val SUBMISSION = "urn:ietf:params:jmap:submission"
    const val PUSH = "urn:plmail:params:jmap:push"

    /** The usual declaration for a mail request. */
    val USING_MAIL = listOf(CORE, MAIL)

    /** For requests that also submit mail. */
    val USING_MAIL_SUBMISSION = listOf(CORE, MAIL, SUBMISSION)
}
