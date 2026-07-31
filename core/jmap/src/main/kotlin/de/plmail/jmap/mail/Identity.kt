package de.plmail.jmap.mail

import de.plmail.jmap.protocol.IdentityId
import kotlinx.serialization.Serializable

/**
 * An address the user can send from.
 *
 * These come from the same list the web composer's From dropdown shows — the account's sendable
 * aliases, primary first. An account with no alias rows yields one synthetic identity for the
 * account address itself.
 *
 * **Always show the picker and default to the first.** Multiple sending aliases per account is a
 * normal configuration, not an edge case, and silently picking one sends mail from an address the
 * user did not choose.
 *
 * Do not assume [email] parses as an address: it is whatever the account's email column holds,
 * which a misconfigured account can leave as a display name.
 */
@Serializable
data class Identity(
    val id: IdentityId,
    val name: String? = null,
    val email: String = "",
    val replyTo: List<EmailAddress>? = null,
    val bcc: List<EmailAddress>? = null,
    val textSignature: String = "",
    val htmlSignature: String = "",
    val mayDelete: Boolean = false,
) {
    val display: String
        get() = name?.takeIf { it.isNotBlank() }?.let { "$it <$email>" } ?: email
}
