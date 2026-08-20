package de.plmail.feature.compose

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import de.plmail.core.data.SendIdentity
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A real intent all the way to the message it becomes.
 *
 * The three steps — [ShareIntake.read], [SharedAttachmentStore.stage], [asDraft] — are each covered
 * on their own elsewhere. This is the join, because the join is where a share loses things: a
 * subject that arrives and is never assigned, an address list parsed twice, a body inserted as
 * markup. Every case starts from an `Intent` built the way the sharing app builds it and ends at
 * the `ComposeDraft` the composer would open on, with nothing stubbed in between.
 *
 * The one thing not exercised here is `ComposeViewModel` itself, which cannot be constructed
 * without a database, a client pool and a send queue. What it does with a share is assign the draft
 * below to its state, which is why the mapping was pulled out into [asDraft] and not left inside
 * it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareToDraftTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: SharedAttachmentStore
    private lateinit var provider: FakeDocuments

    private val identity =
        SendIdentity(
            accountKey = "server/1",
            accountName = "Home",
            identityId = "i1",
            name = "Anna",
            email = "anna@example.org",
            htmlSignature = "<p>Anna</p>",
        )

    @Before
    fun setUp() {
        store = SharedAttachmentStore(context)
        provider =
            Robolectric.buildContentProvider(FakeDocuments::class.java).create(AUTHORITY).get()

        File(context.cacheDir, "shared").deleteRecursively()
    }

    @Test
    fun `a shared link opens a message with the link in the body`() = runTest {
        val draft =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "plMail")
                .putExtra(Intent.EXTRA_TEXT, "https://plmail.example.org")
                .asDraft()

        assertEquals("plMail", draft.subject)
        assertTrue(draft.bodyHtml.startsWith("<p>https://plmail.example.org</p>"))
        // The signature is below what was shared, not above it.
        assertTrue(draft.bodyHtml.endsWith("</div>") || draft.bodyHtml.contains("Anna"))
    }

    @Test
    fun `shared text is escaped rather than inserted`() {
        // A page title with markup in it must not start rendering as markup. The
        // editor would round-trip whatever it is handed.
        val draft =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "5 < 6 & <b>bold</b>")
                .asDraft()

        assertTrue(draft.bodyHtml.contains("5 &lt; 6 &amp; &lt;b&gt;bold&lt;/b&gt;"))
        assertTrue(!draft.bodyHtml.contains("<b>bold</b>"))
    }

    @Test
    fun `blank lines in shared text become paragraphs`() {
        val draft =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "One\ntwo\n\nThree")
                .asDraft()

        assertTrue(draft.bodyHtml.startsWith("<p>One<br>two</p><p>Three</p>"))
    }

    @Test
    fun `a single address becomes a single recipient`() {
        val draft =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_EMAIL, arrayOf("anna@example.org"))
                .asDraft()

        assertEquals(listOf("anna@example.org"), draft.to.map { it.email })
    }

    @Test
    fun `several addresses across to cc and bcc all land`() {
        val draft =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(
                    Intent.EXTRA_EMAIL,
                    arrayOf("anna@example.org", "Bruno Meyer <bruno@example.org>"),
                )
                .putExtra(Intent.EXTRA_CC, arrayOf("carla@example.net"))
                .putExtra(Intent.EXTRA_BCC, arrayOf("dana@example.net"))
                .asDraft()

        assertEquals(listOf("anna@example.org", "bruno@example.org"), draft.to.map { it.email })
        // The display name survives the trip, because parseAddresses is the same
        // parser the recipient field uses on pasted text.
        assertEquals(listOf(null, "Bruno Meyer"), draft.to.map { it.name })
        assertEquals(listOf("carla@example.net"), draft.cc.map { it.email })
        assertEquals(listOf("dana@example.net"), draft.bcc.map { it.email })
    }

    @Test
    fun `an outlook address with a comma in the name is one recipient`() {
        val draft =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_EMAIL, "\"Meyer, Anna\" <anna@example.org>")
                .asDraft()

        assertEquals(listOf("anna@example.org"), draft.to.map { it.email })
        assertEquals("Meyer, Anna", draft.to.single().name)
    }

    @Test
    fun `one shared photo becomes one attachment`() {
        val photo = provider.offer("IMG_20260820.jpg", "image/jpeg", "jpeg bytes")

        val draft =
            Intent(Intent.ACTION_SEND)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, photo)
                .asDraft()

        val attachment = draft.attachments.single()

        assertEquals("IMG_20260820.jpg", attachment.name)
        assertEquals("image/jpeg", attachment.type)
        assertEquals("jpeg bytes".length.toLong(), attachment.size)
        // Not the content URI it arrived as, and no blob id yet: it is a local
        // file waiting for the first autosave to upload it.
        assertTrue(attachment.uri.orEmpty().startsWith("file://"))
        assertNull(attachment.blobId)
    }

    @Test
    fun `several shared files become several attachments in order`() {
        val first = provider.offer("one.pdf", "application/pdf", "a")
        val second = provider.offer("two.pdf", "application/pdf", "bb")
        val third = provider.offer("three.png", "image/png", "ccc")

        val draft =
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_SUBJECT, "Three files")
                .putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    arrayListOf<Uri>(first, second, third),
                )
                .asDraft()

        assertEquals(listOf("one.pdf", "two.pdf", "three.png"), draft.attachments.map { it.name })
        assertEquals(listOf(1L, 2L, 3L), draft.attachments.map { it.size })
        assertEquals("Three files", draft.subject)
    }

    @Test
    fun `a mailto link opens the same kind of message`() {
        val draft =
            Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "mailto:anna@example.org,bruno@example.org" +
                            "?cc=carla@example.net&subject=Gr%C3%BC%C3%9Fe&body=Hallo%0AAnna"
                    ),
                )
                .asDraft()

        assertEquals(listOf("anna@example.org", "bruno@example.org"), draft.to.map { it.email })
        assertEquals(listOf("carla@example.net"), draft.cc.map { it.email })
        assertEquals("Grüße", draft.subject)
        assertTrue(draft.bodyHtml.startsWith("<p>Hallo<br>Anna</p>"))
    }

    @Test
    fun `a share the composer would not save is still worth opening`() {
        // An empty SEND: nothing to assign, but the user asked for this app by
        // name from the sheet, so a blank composer beats a tap that did nothing.
        val draft = Intent(Intent.ACTION_SEND).setType("text/plain").asDraft()

        assertEquals("", draft.subject)
        assertEquals(emptyList<String>(), draft.to.map { it.email })
        assertEquals(emptyList<String>(), draft.attachments.map { it.name })
        // The signature is there, which is what makes it a message rather than
        // an empty box.
        assertTrue(draft.bodyHtml.contains("Anna"))
    }

    /** Intent in, message out, through every step the app takes. */
    private fun Intent.asDraft() =
        kotlinx.coroutines.runBlocking {
            store.stage(checkNotNull(ShareIntake.read(this@asDraft))).asDraft(identity)
        }

    private companion object {
        const val AUTHORITY = "de.plmail.test.documents"
    }
}
