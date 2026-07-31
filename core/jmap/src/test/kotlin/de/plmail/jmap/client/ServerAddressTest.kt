package de.plmail.jmap.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The address field is the first thing anyone touches and the easiest place to strand them.
 *
 * These cases are drawn from what this audience actually types — a bare `.local` name, a router's
 * `host:port`, a pasted URL with the well-known path already on the end, an emulator's `10.0.2.2` —
 * rather than from what a URL parser finds interesting. The `nas.local:8002` case in particular is
 * the one a naive implementation gets wrong while looking correct, because `URI` reads `nas.local`
 * as a scheme.
 */
class ServerAddressTest {

    private fun parsed(input: String): ServerAddress {
        val result = ServerAddress.parse(input)
        assertIs<ParsedAddress.Valid>(result, "expected “$input” to parse")

        return result.address
    }

    @Test
    fun `a bare host defaults to https`() {
        val address = parsed("nas.local")

        assertEquals("https", address.scheme)
        assertEquals("nas.local", address.host)
        assertEquals(null, address.port)
        assertEquals("https://nas.local/.well-known/jmap", address.discoveryUrl)
        assertFalse(address.isCleartext)
    }

    @Test
    fun `a bare host and port is a host and a port, not a scheme`() {
        val address = parsed("nas.local:8002")

        assertEquals("https", address.scheme)
        assertEquals("nas.local", address.host)
        assertEquals(8002, address.port)
        assertEquals("https://nas.local:8002/.well-known/jmap", address.discoveryUrl)
    }

    @Test
    fun `an explicit http address is preserved and reported as cleartext`() {
        val address = parsed("http://10.0.2.2:8002")

        assertEquals("http", address.scheme)
        assertEquals(8002, address.port)
        assertTrue(address.isCleartext)
        assertEquals("http://10.0.2.2:8002/.well-known/jmap", address.discoveryUrl)
    }

    @Test
    fun `a default port is dropped so it never shows up in the display form`() {
        assertEquals("https://mail.example.com", parsed("https://mail.example.com:443").display)
        assertEquals("http://mail.example.com", parsed("http://mail.example.com:80").display)
    }

    @Test
    fun `a non-default port survives`() {
        assertEquals(
            "https://mail.example.com:8443",
            parsed("https://mail.example.com:8443").display,
        )
    }

    @Test
    fun `surrounding whitespace and a trailing slash are ignored`() {
        assertEquals(
            "https://nas.local/.well-known/jmap",
            parsed("  https://nas.local/  ").discoveryUrl,
        )
    }

    @Test
    fun `scheme and host are lowercased`() {
        val address = parsed("HTTPS://NAS.Local")

        assertEquals("https", address.scheme)
        assertEquals("nas.local", address.host)
    }

    @Test
    fun `a reverse-proxy path prefix is kept`() {
        val address = parsed("https://example.com/mail")

        assertEquals("/mail", address.pathPrefix)
        assertEquals("https://example.com/mail/.well-known/jmap", address.discoveryUrl)
    }

    @Test
    fun `an already-complete discovery url is not doubled`() {
        assertEquals(
            "https://nas.local/.well-known/jmap",
            parsed("https://nas.local/.well-known/jmap").discoveryUrl,
        )
        assertEquals(
            "https://example.com/mail/.well-known/jmap",
            parsed("https://example.com/mail/.well-known/jmap").discoveryUrl,
        )
    }

    @Test
    fun `blank input is its own case rather than an error`() {
        assertIs<ParsedAddress.Blank>(ServerAddress.parse(""))
        assertIs<ParsedAddress.Blank>(ServerAddress.parse("   "))
    }

    @Test
    fun `a non-http scheme is named in the refusal`() {
        val result = ServerAddress.parse("ftp://nas.local")

        assertIs<ParsedAddress.UnsupportedScheme>(result)
        assertEquals("ftp", result.scheme)
    }

    @Test
    fun `an address carrying a password is refused rather than stripped`() {
        assertIs<ParsedAddress.CredentialsInAddress>(
            ServerAddress.parse("https://someone:hunter2@nas.local")
        )
    }

    @Test
    fun `an address with no host is malformed`() {
        assertIs<ParsedAddress.Malformed>(ServerAddress.parse("https://"))
        assertIs<ParsedAddress.Malformed>(ServerAddress.parse("https:///jmap"))
    }

    @Test
    fun `an ipv6 literal keeps its brackets`() {
        val address = parsed("https://[2001:db8::1]:8443")

        assertEquals("[2001:db8::1]", address.host)
        assertEquals(8443, address.port)
        assertEquals("https://[2001:db8::1]:8443/.well-known/jmap", address.discoveryUrl)
    }
}
