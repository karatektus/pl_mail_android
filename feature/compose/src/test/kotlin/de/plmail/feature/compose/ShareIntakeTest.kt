package de.plmail.feature.compose

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real intents, built the way the apps that send them build them.
 *
 * Under Robolectric because an `Intent` is an Android class all the way down — its extras live in a
 * `Bundle`, and a `Bundle`'s type-tolerant getters are the entire subject of half these cases.
 * Faking the intent would test the fake: the reason `getCharSequenceExtra` is used instead of
 * `getStringExtra` is that `Bundle.getCharSequence` returns a `Spanned` where `getString` returns
 * null, and only a real `Bundle` behaves that way.
 *
 * The shapes below are not hypothetical. A `Spanned` subject is what a browser sharing a page
 * sends; an `ArrayList<String>` in `EXTRA_EMAIL` is what everything built on `ShareCompat` sends;
 * `ClipData` with no `EXTRA_STREAM` is what parts of the system send.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareIntakeTest {

    // ------------------------------------------------------------------- text

    @Test
    fun `a shared link becomes the body`() {
        val shared =
            ShareIntake.read(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "plMail")
                    .putExtra(Intent.EXTRA_TEXT, "https://plmail.example.org")
            )

        assertEquals("plMail", shared?.subject)
        assertEquals("https://plmail.example.org", shared?.text)
        assertEquals(emptyList<String>(), shared?.streams)
    }

    @Test
    fun `a subject sent as styled text is not lost`() {
        // getStringExtra returns null for this and the subject line silently
        // does not arrive. It is what a browser sharing a page title sends.
        val styled: CharSequence = android.text.SpannableString("Quarterly report")

        val shared =
            ShareIntake.read(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, styled)
            )

        assertEquals("Quarterly report", shared?.subject)
    }

    @Test
    fun `the share sheet title stands in for a missing subject`() {
        val shared =
            ShareIntake.read(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, "Notes")
            )

        assertEquals("Notes", shared?.subject)
    }

    @Test
    fun `several shared texts are separate paragraphs`() {
        val shared =
            ShareIntake.read(
                Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("text/plain")
                    .putCharSequenceArrayListExtra(
                        Intent.EXTRA_TEXT,
                        arrayListOf<CharSequence>("first", "second"),
                    )
            )

        assertEquals("first\n\nsecond", shared?.text)
    }

    // -------------------------------------------------------------- addresses

    @Test
    fun `to cc and bcc all arrive`() {
        val shared =
            ShareIntake.read(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_EMAIL, arrayOf("anna@example.org"))
                    .putExtra(Intent.EXTRA_CC, arrayOf("bruno@example.org"))
                    .putExtra(Intent.EXTRA_BCC, arrayOf("carla@example.net"))
            )

        assertEquals(listOf("anna@example.org"), shared?.to)
        assertEquals(listOf("bruno@example.org"), shared?.cc)
        assertEquals(listOf("carla@example.net"), shared?.bcc)
    }

    @Test
    fun `an address list sent as an ArrayList arrives too`() {
        // What ShareCompat.IntentBuilder produces, and the shape a String[]-only
        // reader loses every recipient of.
        val shared =
            ShareIntake.read(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putStringArrayListExtra(
                        Intent.EXTRA_EMAIL,
                        arrayListOf("anna@example.org", "bruno@example.org"),
                    )
            )

        assertEquals(listOf("anna@example.org", "bruno@example.org"), shared?.to)
    }

    @Test
    fun `a single address sent as a bare string arrives too`() {
        val shared =
            ShareIntake.read(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_EMAIL, "anna@example.org")
            )

        assertEquals(listOf("anna@example.org"), shared?.to)
    }

    // ------------------------------------------------------------ attachments

    @Test
    fun `one shared file is one stream`() {
        val photo = Uri.parse("content://media/external/images/1")

        val shared =
            ShareIntake.read(
                Intent(Intent.ACTION_SEND)
                    .setType("image/jpeg")
                    .putExtra(Intent.EXTRA_STREAM, photo)
            )

        assertEquals(listOf(photo.toString()), shared?.streams)
    }

    @Test
    fun `several shared files are several streams`() {
        val uris =
            arrayListOf(
                Uri.parse("content://media/external/images/1"),
                Uri.parse("content://media/external/images/2"),
            )

        val shared =
            ShareIntake.read(
                Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("image/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            )

        assertEquals(uris.map { it.toString() }, shared?.streams)
    }

    @Test
    fun `a file offered only through the clip is still a file`() {
        val document = Uri.parse("content://docs/42")
        val intent =
            Intent(Intent.ACTION_SEND).setType("application/pdf").apply {
                clipData =
                    ClipData(
                        ClipDescription("", arrayOf("application/pdf")),
                        ClipData.Item(document),
                    )
            }

        assertEquals(listOf(document.toString()), ShareIntake.read(intent)?.streams)
    }

    @Test
    fun `the clip is not counted a second time when the extra already said so`() {
        val photo = Uri.parse("content://media/external/images/1")
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, photo)
                .apply {
                    // ShareCompat sets both: the extra for the receiver, the clip to
                    // carry the grant. Reading both would attach the photo twice.
                    clipData =
                        ClipData(ClipDescription("", arrayOf("image/jpeg")), ClipData.Item(photo))
                }

        assertEquals(listOf(photo.toString()), ShareIntake.read(intent)?.streams)
    }

    // ------------------------------------------------------------------ mailto

    @Test
    fun `a sendto mailto is a composer on that address`() {
        val shared =
            ShareIntake.read(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:anna@example.org?subject=Hallo"))
            )

        assertEquals(listOf("anna@example.org"), shared?.to)
        assertEquals("Hallo", shared?.subject)
    }

    @Test
    fun `a tapped mailto link is the same thing`() {
        val shared =
            ShareIntake.read(Intent(Intent.ACTION_VIEW, Uri.parse("mailto:anna@example.org")))

        assertEquals(listOf("anna@example.org"), shared?.to)
    }

    @Test
    fun `extras fill in what the link did not say`() {
        val shared =
            ShareIntake.read(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:anna@example.org"))
                    .putExtra(Intent.EXTRA_SUBJECT, "From the extra")
                    .putExtra(Intent.EXTRA_TEXT, "Body from the extra")
            )

        assertEquals("From the extra", shared?.subject)
        assertEquals("Body from the extra", shared?.text)
    }

    @Test
    fun `the link wins where the two disagree`() {
        // It is the more specific of the two and the one the user tapped.
        val shared =
            ShareIntake.read(
                Intent(
                        Intent.ACTION_SENDTO,
                        Uri.parse("mailto:anna@example.org?subject=From%20the%20link"),
                    )
                    .putExtra(Intent.EXTRA_SUBJECT, "From the extra")
            )

        assertEquals("From the link", shared?.subject)
    }

    // -------------------------------------------------------- what is not ours

    @Test
    fun `the pairing link is still the pairing link`() {
        // The pairing filter and the mailto filter are both VIEW on the same
        // activity, so this is the assertion that keeps the composer from
        // opening on a QR code.
        assertNull(ShareIntake.read(Intent(Intent.ACTION_VIEW, Uri.parse("plmail://pair?code=1"))))
    }

    @Test
    fun `an unrelated intent is not a share`() {
        assertNull(ShareIntake.read(Intent(Intent.ACTION_MAIN)))
        assertNull(ShareIntake.read(null))
    }

    @Test
    fun `an empty share still opens a composer`() {
        // A malformed SEND is worth an empty composer rather than a dropped tap:
        // the user asked for this app by name from the sheet.
        val shared = ShareIntake.read(Intent(Intent.ACTION_SEND).setType("text/plain"))

        assertEquals(SharedMessage(), shared)
        assertTrue(shared?.streams.orEmpty().isEmpty())
    }
}
