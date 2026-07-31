package de.plmail.jmap.mail

import kotlinx.serialization.Serializable

/**
 * A `{name, email}` pair.
 *
 * Note the wire spelling: plMail stores `{name, address}` internally and translates at the JMAP
 * boundary, so `email` is correct here and `address` would silently decode to null.
 */
@Serializable
data class EmailAddress(val name: String? = null, val email: String? = null) {

    /** What to show when there is room for one line. */
    val display: String
        get() = name?.takeIf { it.isNotBlank() } ?: email.orEmpty()

    /**
     * What to colour an avatar from.
     *
     * The address, never the display name: hashing the name recolours the same person the moment
     * they change how their mail client spells it.
     */
    val identity: String
        get() = email?.lowercase() ?: name.orEmpty().lowercase()
}
