package de.plmail.feature.mail.reader

import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which type a tapped attachment is offered to other apps under.
 *
 * The case that was reported: a PDF from a sender that labelled it `application/octet-stream`
 * opened an empty "Open with" chooser, because no PDF reader registers for that type.
 *
 * Robolectric because [android.webkit.MimeTypeMap] is a framework class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentViewTypeTest {

    @Test
    fun `a pdf labelled octet-stream is offered as a pdf`() {
        assertEquals("application/pdf", viewType("application/octet-stream", "invoice.pdf"))
    }

    @Test
    fun `the extension is read case-insensitively and from the last dot`() {
        assertEquals("application/pdf", viewType("application/octet-stream", "Scan.2026.PDF"))
    }

    @Test
    fun `the old pdf alias becomes application pdf`() {
        assertEquals("application/pdf", viewType("application/x-pdf", "invoice.pdf"))
        assertEquals("application/pdf", viewType("application/x-pdf", null))
    }

    @Test
    fun `other generic binary types and a blank type fall back the same way`() {
        assertEquals("application/pdf", viewType("binary/octet-stream", "a.pdf"))
        assertEquals("application/pdf", viewType("", "a.pdf"))
        assertEquals("application/pdf", viewType(null, "a.pdf"))
    }

    @Test
    fun `parameters on the declared type do not hide that it is generic`() {
        assertEquals("application/pdf", viewType("application/octet-stream; name=a.pdf", "a.pdf"))
    }

    @Test
    fun `a specific declared type is the sender's claim and is kept`() {
        assertEquals("application/pdf", viewType("application/pdf", "invoice.pdf"))
        assertEquals("image/png", viewType("image/png", "weird.pdf"))
    }

    @Test
    fun `a generic type with an unknown or missing extension stays generic`() {
        assertEquals("application/octet-stream", viewType("application/octet-stream", "data.zzzz"))
        assertEquals(
            "application/octet-stream",
            viewType("application/octet-stream", "noextension"),
        )
        assertEquals("application/octet-stream", viewType("application/octet-stream", null))
        assertEquals("application/octet-stream", viewType(null, null))
    }
}
