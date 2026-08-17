package de.plmail.jmap.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sign-off block, and the one promise it has to keep.
 *
 * Every case here is really the same case: **changing the From address must not cost somebody what
 * they have written.** That is the only reason the block carries a marker, and a splice computed
 * one character out is a bug that eats a paragraph silently, on the one screen where losing text is
 * least forgivable.
 */
class SignaturesTest {

    private val ada = "<p>Ada Lovelace</p>"
    private val grace = "<p>Grace Hopper</p>"

    @Test
    fun `a block is the markup the web swaps on, byte for byte`() {
        assertEquals(
            "<div class=\"pl-signature\" data-pl-signature>$ada</div>",
            Signatures.block(ada),
        )
    }

    /**
     * An address that signs with nothing is a real state, not a missing one — plMail tells an alias
     * that inherits apart from one explicitly set to none. An empty marked block would be a stray
     * `div` on every message sent from that address.
     */
    @Test
    fun `an address that signs with nothing produces no block at all`() {
        assertEquals("", Signatures.block(null))
        assertEquals("", Signatures.block(""))
        assertEquals("", Signatures.block("   "))
    }

    @Test
    fun `a signature is appended to a body that has none`() {
        val body = Signatures.replaceSignature("<p>Dear Grace,</p>", ada)

        assertTrue(body.startsWith("<p>Dear Grace,</p>"), body)
        assertTrue(body.endsWith(Signatures.block(ada)), body)
    }

    @Test
    fun `an empty composer gets the signature and no separator`() {
        assertEquals(Signatures.block(ada), Signatures.replaceSignature("", ada))
    }

    /** **The promise.** Everything outside the block comes back untouched. */
    @Test
    fun `swapping a signature leaves the typed text exactly as it was`() {
        val typed = "<p>Dear Grace,</p><p>The engine works.</p>"
        val first = Signatures.replaceSignature(typed, ada)

        val second = Signatures.replaceSignature(first, grace)

        assertTrue(second.startsWith(typed), second)
        assertEquals(grace, Signatures.signatureIn(second))
    }

    /** Swapping twice is swapping once. Two sign-offs is what the marker exists to prevent. */
    @Test
    fun `swapping never leaves two blocks behind`() {
        val once = Signatures.replaceSignature("<p>Hello</p>", ada)
        val twice = Signatures.replaceSignature(once, grace)
        val thrice = Signatures.replaceSignature(twice, ada)

        assertEquals(1, thrice.split("data-pl-signature").size - 1, thrice)
    }

    /**
     * **The reason this is a scanner rather than a regular expression.**
     *
     * A signature is arbitrary user HTML and routinely contains `div`s — a contact table, a logo
     * wrapped for alignment. Ending the block at the *first* `</div>` cuts it in half and welds the
     * remainder onto the front of the next one, which is the failure that makes people stop
     * trusting the From menu.
     */
    @Test
    fun `a signature containing divs is replaced whole`() {
        val nested = "<div><div>Ada</div><div>Analytical Engines Ltd</div></div>"

        val first = Signatures.replaceSignature("<p>Hello</p>", nested)
        val second = Signatures.replaceSignature(first, grace)

        assertEquals(grace, Signatures.signatureIn(second))
        assertTrue("Analytical Engines" !in second, second)
        assertTrue(second.startsWith("<p>Hello</p>"), second)
    }

    /** Moving to an address that signs with nothing takes the old block away with it. */
    @Test
    fun `switching to an address with no signature removes the block`() {
        val signed = Signatures.replaceSignature("<p>Hello</p>", ada)

        val bare = Signatures.replaceSignature(signed, "")

        assertNull(Signatures.signatureIn(bare))
        assertTrue("Ada" !in bare, bare)
    }

    /** Safe to call on every From change, including the case where nothing happens at all. */
    @Test
    fun `no signature either side leaves the body alone`() {
        assertEquals("<p>Hello</p>", Signatures.replaceSignature("<p>Hello</p>", null))
    }

    @Test
    fun `a body with no signature reports none`() {
        assertNull(Signatures.signatureIn("<p>Hello</p>"))
    }

    /**
     * Markup that opens the block and never closes it.
     *
     * Read as "no block" rather than spliced on an end offset that does not exist — the cost is one
     * duplicated sign-off, and the alternative is a `substring` on a computed index.
     */
    @Test
    fun `an unclosed block is not spliced on a guessed offset`() {
        val broken = "<p>Hi</p><div class=\"pl-signature\" data-pl-signature><p>Ada</p>"

        assertNull(Signatures.signatureIn(broken))

        // Appended rather than replaced, and nothing thrown.
        val fixed = Signatures.replaceSignature(broken, grace)

        assertTrue(fixed.startsWith(broken), fixed)
    }
}
