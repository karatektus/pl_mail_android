package de.plmail.jmap.client

import java.net.URI
import java.net.URISyntaxException

/**
 * What the user typed at onboarding, turned into a discovery URL.
 *
 * This is the *only* URL the app ever constructs. Everything else — `apiUrl`, `uploadUrl`,
 * `downloadUrl`, `eventSourceUrl` — is read back from the Session, because the server generates
 * those from the request's `Host` header and a client that derives them instead silently talks to
 * the wrong place the moment someone puts a reverse proxy in front of their NAS.
 *
 * The audience types things like `nas.local`, `192.168.1.40:8080`, `https://mail.example.com/` and
 * `http://10.0.2.2:8002`. All four are meant to work, and the failures have to be legible: someone
 * setting up a self-hosted server at 11pm is already unsure whether the problem is the address, the
 * certificate or the box being down, and "invalid URL" helps with none of that.
 */
data class ServerAddress(
    val scheme: String,
    val host: String,
    /** Null when the scheme's default applies, so the display form stays free of `:443`. */
    val port: Int?,
    /**
     * Any path the user typed, normalised to either empty or `/segments` with no trailing slash.
     *
     * Kept rather than discarded because plMail behind a reverse proxy at `example.com/mail` is a
     * normal deployment for this audience, and throwing the prefix away would send discovery to the
     * proxy's root — which usually answers with somebody's unrelated homepage and a 200, so the
     * failure arrives as a JSON parse error rather than as "not found".
     */
    val pathPrefix: String,
) {
    /**
     * Where `/.well-known/jmap` lives for this address.
     *
     * Note this is the whole reason the type exists, and it is deliberately a `val` rather than
     * something a caller assembles: every place that built such a URL by hand is a place that could
     * disagree about the trailing slash.
     */
    val discoveryUrl: String
        get() = "$origin$pathPrefix$WELL_KNOWN"

    /** Scheme, host and port — what the pin and the trust prompt are about. */
    val origin: String
        get() = if (port == null) "$scheme://$host" else "$scheme://$host:$port"

    /**
     * True when this connection carries the app password in clear.
     *
     * Onboarding must say so explicitly rather than quietly allowing it. `http://` is supported on
     * purpose — the emulator reaches the test stack that way, and plenty of people run plMail on a
     * LAN box with no certificate at all — but a credential travelling in clear over someone's café
     * wifi is a different bargain from one that does not, and only the user can make it.
     */
    val isCleartext: Boolean
        get() = scheme == SCHEME_HTTP

    /** What to show back to the user; never the discovery path, which they did not type. */
    val display: String
        get() = "$origin$pathPrefix"

    override fun toString(): String = display

    companion object {
        const val WELL_KNOWN = "/.well-known/jmap"

        private const val SCHEME_HTTP = "http"
        private const val SCHEME_HTTPS = "https"

        private val DEFAULT_PORTS = mapOf(SCHEME_HTTP to 80, SCHEME_HTTPS to 443)

        /**
         * Only `scheme://` counts as a scheme.
         *
         * `URI` would read `nas.local:8002` as scheme `nas.local`, which is technically correct and
         * completely wrong here — that string is a host and a port, and it is exactly what someone
         * copies out of their router's admin page.
         */
        private val EXPLICIT_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")

        /**
         * Parses user input, defaulting to HTTPS.
         *
         * HTTPS rather than HTTP for a bare host because the safe reading of an ambiguous address
         * is the encrypted one; someone who genuinely means cleartext can say `http://` and will
         * then be warned about it.
         */
        fun parse(input: String): ParsedAddress {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return ParsedAddress.Blank

            val withScheme =
                if (EXPLICIT_SCHEME.containsMatchIn(trimmed)) trimmed
                else "$SCHEME_HTTPS://$trimmed"

            val uri =
                try {
                    URI(withScheme)
                } catch (malformed: URISyntaxException) {
                    return ParsedAddress.Malformed(malformed.reason ?: "not a valid address")
                }

            val scheme = uri.scheme?.lowercase() ?: return ParsedAddress.Malformed("no scheme")
            if (scheme !in DEFAULT_PORTS) return ParsedAddress.UnsupportedScheme(scheme)

            // getHost is null for things URI accepted but that have no
            // authority — "https:///jmap", "https://:8002" — and for hosts with
            // characters URI will not vouch for. Treating that as malformed
            // rather than falling back to getAuthority avoids constructing a URL
            // that looks fine and resolves to nothing.
            val host = uri.host ?: return ParsedAddress.Malformed("no host in “$trimmed”")

            if (uri.userInfo != null) return ParsedAddress.CredentialsInAddress

            val port = uri.port.takeIf { it != -1 && it != DEFAULT_PORTS.getValue(scheme) }

            return ParsedAddress.Valid(
                ServerAddress(
                    scheme = scheme,
                    host = host.lowercase(),
                    port = port,
                    pathPrefix = normalisePath(uri.path),
                )
            )
        }

        /**
         * Trims a path to `""` or `/a/b`.
         *
         * Strips a `/.well-known/jmap` the user pasted, because the address people have to hand is
         * often the one from the server's setup page — and appending a second copy produces a 404
         * whose message says nothing about the duplication.
         */
        private fun normalisePath(raw: String?): String {
            var path = raw.orEmpty().trim().trimEnd('/')
            if (path.equals(WELL_KNOWN, ignoreCase = true)) return ""
            if (path.endsWith(WELL_KNOWN, ignoreCase = true)) {
                path = path.dropLast(WELL_KNOWN.length)
            }

            return if (path.isEmpty() || path == "/") "" else path.ensureLeadingSlash()
        }

        private fun String.ensureLeadingSlash(): String = if (startsWith("/")) this else "/$this"
    }
}

/**
 * The outcome of reading an address, as named cases rather than a nullable.
 *
 * Onboarding has to say *what* is wrong. A null would collapse "you left it empty", "that is an
 * ftp:// address" and "you pasted a URL with a password in it" into one message, and the last of
 * those in particular deserves its own answer.
 */
sealed interface ParsedAddress {

    data class Valid(val address: ServerAddress) : ParsedAddress

    /** Nothing typed yet. Not an error to show in red — the field is simply still empty. */
    data object Blank : ParsedAddress

    data class Malformed(val reason: String) : ParsedAddress

    data class UnsupportedScheme(val scheme: String) : ParsedAddress

    /**
     * `https://user:password@host`.
     *
     * Refused rather than stripped. The credential belongs in the Keystore, and a URL carrying one
     * ends up in logs, crash reports and the recents list; silently dropping it would also leave
     * the user believing they had supplied a credential when they had not.
     */
    data object CredentialsInAddress : ParsedAddress
}
