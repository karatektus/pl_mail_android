package de.plmail.feature.mail.reader

import de.plmail.core.database.AttachmentEntity
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one line of text under an attachment's name.
 *
 * Small, and worth pinning, because every way it goes wrong goes wrong quietly: a size in the wrong
 * units is a number nobody checks against anything, and a type label that disagrees with the
 * filename above it is read as the app being confused rather than as a bug.
 */
class AttachmentLabelTest {

    /**
     * The size formatter uses the platform locale for its decimal separator, so a machine set to
     * German would otherwise produce "1,4 MB" and fail against a literal written with a point.
     * Pinned rather than made locale-independent: a comma is the *correct* output in German and the
     * formatter should keep producing it.
     */
    private lateinit var original: Locale

    @BeforeTest
    fun pinTheLocale() {
        original = Locale.getDefault()
        Locale.setDefault(Locale.UK)
    }

    @AfterTest
    fun restoreTheLocale() {
        Locale.setDefault(original)
    }

    /**
     * plMail reports `size` as 0 for some parts, and a seeded message reports it for all of them.
     * "0 B" reads as a file that failed to transfer; a dash reads as "not known", which is true.
     */
    @Test
    fun `an unknown size is a dash rather than zero bytes`() {
        assertEquals("—", formatSize(0))
        assertEquals("—", formatSize(-1))
    }

    @Test
    fun `sizes are in the units the file picker uses`() {
        // Powers of ten, matching Android's own storage UI. Reporting 64000
        // bytes as "62.5 kB" would disagree with the number the same file shows
        // the moment it is saved.
        assertEquals("999 B", formatSize(999))
        assertEquals("1.0 kB", formatSize(1_000))
        assertEquals("64 kB", formatSize(64_000))
        assertEquals("1.4 MB", formatSize(1_400_000))
        assertEquals("412 MB", formatSize(412_000_000))
    }

    @Test
    fun `a filename that already says what it is does not get a type as well`() {
        assertEquals("64 kB", describe(attachment("Rechnung.txt", "text/plain", 64_000)))
    }

    @Test
    fun `a filename with no extension keeps its type`() {
        assertEquals("PDF · 1.4 MB", describe(attachment("scan", "application/pdf", 1_400_000)))
    }

    /**
     * The case the extension check exists for. `Rechnung_2025.11` ends in a dot and two characters
     * and is not a PDF called `11` — treating it as one would hide the type on the files that most
     * need it, because a name with no extension is exactly where the type is the only clue.
     */
    @Test
    fun `a date in a filename is not an extension`() {
        assertFalse(hasExtension("Rechnung_2025.11"))
        assertFalse(hasExtension("Scan 12.03.2026"))
        assertFalse(hasExtension("noextension"))
        assertFalse(hasExtension(null))

        assertTrue(hasExtension("a.txt"))
        assertTrue(hasExtension("archive.tar.gz"))
        assertTrue(hasExtension("song.mp3"))
        assertTrue(hasExtension("bundle.7z"))
    }

    /**
     * An Office media type is 66 characters of URN. Printed in full it pushes the size off the row;
     * printed truncated it is a lie. Dropped, and the `.docx` in the name says it better.
     */
    @Test
    fun `an unreadable media type is dropped rather than truncated`() {
        assertNull(
            shortType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        )
        assertEquals("PDF", shortType("application/pdf"))
        assertEquals("PLAIN", shortType("text/plain"))
        // Parameters are not part of the type, and a charset in the label would
        // be the longest thing on the row.
        assertEquals("PLAIN", shortType("text/plain; charset=utf-8"))
        assertNull(shortType("application/octet-stream"))
        assertNull(shortType("nonsense"))
    }

    private fun attachment(name: String?, type: String, size: Long) =
        AttachmentEntity(
            uid = "u",
            emailUid = "e",
            accountKey = "a",
            partId = "1",
            blobId = "p-1",
            name = name,
            type = type,
            size = size,
        )
}
