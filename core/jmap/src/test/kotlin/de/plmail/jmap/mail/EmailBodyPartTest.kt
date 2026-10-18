package de.plmail.jmap.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which parts the reader lists beneath a message and which it leaves inside it.
 *
 * The case that mattered: an accounting package's invoice, a PDF marked `attachment` that also
 * carries a Content-ID. Deciding by the cid alone made it "inline" and the reader showed a message
 * with no attachments at all.
 */
class EmailBodyPartTest {

    @Test
    fun `a part marked attachment is listed even when it has a content id`() {
        val part = EmailBodyPart(disposition = "attachment", cid = "1a0f27f51543350a681")

        assertFalse(part.isInline)
    }

    @Test
    fun `a part marked inline is inline with or without a content id`() {
        assertTrue(EmailBodyPart(disposition = "inline", cid = "logo").isInline)
        assertTrue(EmailBodyPart(disposition = "inline").isInline)
    }

    @Test
    fun `a part with no disposition but a content id is an inline image`() {
        assertTrue(EmailBodyPart(disposition = null, cid = "image001").isInline)
    }

    @Test
    fun `a part with neither is an ordinary attachment`() {
        assertFalse(EmailBodyPart(disposition = null, cid = null).isInline)
        assertEquals(false, EmailBodyPart(disposition = "attachment").isInline)
    }
}
