package de.plmail.jmap.client

import kotlin.io.encoding.Base64

/**
 * How the client proves who it is.
 *
 * plMail's JMAP firewall is stateless and takes an app password either way. The secret looks like
 * `plmail_<64 hex>` and only its SHA-256 digest is stored server-side, so it is unrecoverable once
 * shown — which is why the app pairs by QR rather than asking anyone to retype one.
 *
 * Tokens are **user-scoped, not account-scoped**: one credential enumerates every connected
 * mailbox.
 */
sealed interface Credential {

    /** The value for the `Authorization` header. */
    val authorizationHeader: String

    /**
     * `Authorization: Bearer plmail_…`
     *
     * The default. A bearer token starting with the `plmail_` prefix is routed to the app-password
     * authenticator; anything else falls through to JWT, which the server accepts but has no
     * endpoint to issue yet.
     */
    data class AppPassword(val secret: String) : Credential {
        override val authorizationHeader: String
            get() = "Bearer $secret"

        /** Never log or display the secret; this is what listings show. */
        override fun toString(): String = "AppPassword(${masked()})"

        fun masked(): String =
            if (secret.length > PREFIX.length + HINT_LENGTH) {
                secret.take(PREFIX.length + HINT_LENGTH) + "…"
            } else {
                "…"
            }

        companion object {
            const val PREFIX = "plmail_"
            private const val HINT_LENGTH = 6

            /** Whether a pasted string even looks like one, before a round trip. */
            fun looksValid(secret: String): Boolean =
                secret.startsWith(PREFIX) &&
                    secret.length > PREFIX.length &&
                    secret.drop(PREFIX.length).all {
                        it.isDigit() || it in 'a'..'f' || it in 'A'..'F'
                    }
        }
    }

    /**
     * `Authorization: Basic base64(address:secret)`
     *
     * Supported for parity with clients that only speak Basic. Note the server **verifies the
     * address against the token's owner** rather than ignoring it, so a wrong address is rejected
     * outright instead of silently operating as whoever owns the token.
     */
    data class Basic(val username: String, val secret: String) : Credential {
        override val authorizationHeader: String
            get() = "Basic " + Base64.Default.encode("$username:$secret".encodeToByteArray())

        override fun toString(): String = "Basic($username, …)"
    }
}
