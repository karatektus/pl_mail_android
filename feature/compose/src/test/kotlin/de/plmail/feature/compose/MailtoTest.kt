package de.plmail.feature.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `mailto:` links, which are the half of this feature that is somebody else's input.
 *
 * A share sheet is built by an app that means well. A `mailto:` is a string in a web page, and the
 * page was written by hand, or by a CMS, or by a form builder from 2009 — so every case below is
 * one that was found in the wild rather than one derived from the grammar. The two that matter most
 * are the two that fail silently: a recipient dropped because it was split in the wrong place, and
 * a link that throws, which in this app means an address on a page that simply cannot be tapped.
 *
 * No Robolectric, because [Mailto] takes a string. That is the whole reason it takes a string.
 */
class MailtoTest {

    @Test
    fun `a bare address is a recipient`() {
        val parsed = Mailto.parse("mailto:anna@example.org")

        assertEquals(listOf("anna@example.org"), parsed?.to)
        assertEquals("", parsed?.subject)
        assertEquals("", parsed?.text)
    }

    @Test
    fun `several recipients are separated by commas`() {
        val parsed = Mailto.parse("mailto:anna@example.org,bruno@example.org,carla@example.net")

        assertEquals(
            listOf("anna@example.org", "bruno@example.org", "carla@example.net"),
            parsed?.to,
        )
    }

    @Test
    fun `subject and body come out of the query`() {
        val parsed = Mailto.parse("mailto:anna@example.org?subject=Rechnung&body=Anbei")

        assertEquals("Rechnung", parsed?.subject)
        assertEquals("Anbei", parsed?.text)
    }

    @Test
    fun `percent-encoded spaces and newlines survive`() {
        val parsed =
            Mailto.parse(
                "mailto:anna@example.org?subject=Two%20words&body=First%20line%0D%0ASecond%20line"
            )

        assertEquals("Two words", parsed?.subject)
        assertEquals("First line\r\nSecond line", parsed?.text)
    }

    @Test
    fun `a multi-byte character is decoded as one character`() {
        // The escapes have to be gathered before any of them is decoded: taken
        // one at a time this is two replacement marks, and every German subject
        // line in a mailto goes through it.
        val parsed = Mailto.parse("mailto:anna@example.org?subject=Gr%C3%BC%C3%9Fe")

        assertEquals("Grüße", parsed?.subject)
    }

    @Test
    fun `cc and bcc are their own lists`() {
        val parsed =
            Mailto.parse("mailto:anna@example.org?cc=bruno@example.org&bcc=carla@example.net")

        assertEquals(listOf("anna@example.org"), parsed?.to)
        assertEquals(listOf("bruno@example.org"), parsed?.cc)
        assertEquals(listOf("carla@example.net"), parsed?.bcc)
    }

    @Test
    fun `a to field adds to the addresses before the question mark`() {
        // RFC 6068 allows both spellings and a link may use both. Replacing
        // rather than adding would drop whoever was named first.
        val parsed = Mailto.parse("mailto:anna@example.org?to=bruno@example.org")

        assertEquals(listOf("anna@example.org", "bruno@example.org"), parsed?.to)
    }

    @Test
    fun `header names are matched whatever their case`() {
        val parsed =
            Mailto.parse("mailto:anna@example.org?Subject=Hallo&BODY=Text&Cc=b@example.org")

        assertEquals("Hallo", parsed?.subject)
        assertEquals("Text", parsed?.text)
        assertEquals(listOf("b@example.org"), parsed?.cc)
    }

    @Test
    fun `a plus in an address stays a plus`() {
        // The case that decides against form-decoding. URLDecoder would hand
        // back "anna lists@example.org", which parseAddresses then drops as an
        // address with a space in it -- a recipient gone, silently.
        val parsed = Mailto.parse("mailto:anna+lists@example.org")

        assertEquals(listOf("anna+lists@example.org"), parsed?.to)
        assertEquals(
            listOf("anna+lists@example.org"),
            parsed?.to?.flatMap { it.parseAddresses() }?.map { it.email },
        )
    }

    @Test
    fun `an encoded comma stays inside the address it belongs to`() {
        // Splitting after decoding would cut this into `"Meyer` and `Anna"
        // <anna@example.org>`, and the first half has no @ at all.
        val parsed = Mailto.parse("mailto:%22Meyer%2C%20Anna%22%20%3Canna@example.org%3E")

        assertEquals(listOf("\"Meyer, Anna\" <anna@example.org>"), parsed?.to)
        assertEquals(
            listOf("anna@example.org"),
            parsed?.to?.flatMap { it.parseAddresses() }?.map { it.email },
        )
    }

    @Test
    fun `a link with no address at all is still a composer`() {
        val parsed = Mailto.parse("mailto:?subject=Feedback")

        assertTrue(parsed?.to.orEmpty().isEmpty())
        assertEquals("Feedback", parsed?.subject)
    }

    @Test
    fun `a bare scheme is a blank message rather than nothing`() {
        assertEquals(SharedMessage(), Mailto.parse("mailto:"))
    }

    @Test
    fun `headers nobody may set are ignored`() {
        // A page that could set From or Reply-To could compose mail in somebody
        // else's name from a link the user merely tapped.
        val parsed = Mailto.parse("mailto:anna@example.org?from=ceo@example.com&reply-to=x@y.z")

        assertEquals(listOf("anna@example.org"), parsed?.to)
        assertEquals("", parsed?.subject)
    }

    @Test
    fun `a malformed link does not throw`() {
        // Every one of these has been seen in a real page: a truncated escape, a
        // stray per cent sign, an empty field, a field with no value, a value
        // with an equals in it.
        val parsed = Mailto.parse("mailto:anna@example.org?subject=100%%20&%&body=&x=1=2&%GG")

        assertEquals("100% ", parsed?.subject)
        assertEquals("", parsed?.text)
    }

    @Test
    fun `a truncated escape at the very end is kept as a per cent sign`() {
        assertEquals("50%", Mailto.parse("mailto:a@b.c?subject=50%")?.subject)
        assertEquals("50%A", Mailto.parse("mailto:a@b.c?subject=50%A")?.subject)
    }

    @Test
    fun `the scheme is matched whatever its case`() {
        assertEquals(listOf("anna@example.org"), Mailto.parse("MAILTO:anna@example.org")?.to)
    }

    @Test
    fun `anything that is not a mailto is not a message`() {
        assertNull(Mailto.parse("plmail://pair?code=123456"))
        assertNull(Mailto.parse("https://example.org/mailto:a@b.c"))
        assertNull(Mailto.parse("anna@example.org"))
        assertNull(Mailto.parse(""))
        assertNull(Mailto.parse(null))
    }

    @Test
    fun `percent-decoding leaves a string with no escapes alone`() {
        assertEquals("nothing to do here", "nothing to do here".decodePercent())
    }
}
