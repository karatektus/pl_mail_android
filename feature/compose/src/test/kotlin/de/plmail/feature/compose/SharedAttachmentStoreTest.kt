package de.plmail.feature.compose

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import de.plmail.core.data.StagedAttachment
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
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
 * The attachment trap, and whether it is actually shut.
 *
 * The bug this file exists for cannot be seen at the moment it is created. A shared `content://`
 * arrives with a read grant scoped to the receiving task; a draft that stores the URI works
 * perfectly until the grant is revoked or the file is deleted, and then fails at *send*, hours
 * later, with a message about a file the user has stopped thinking about. So the assertion that
 * matters is [a staged file outlives the provider that supplied it]: the source is destroyed after
 * staging, exactly as a real revocation would destroy access to it, and the attachment still reads.
 *
 * **A real `ContentProvider`, not a shadow.** Robolectric will happily let a test register a canned
 * `InputStream` against a URI, and a test written that way asserts that the fake returns what the
 * fake was given. The provider below is the genuine article — `openFile` hands back a real file
 * descriptor, `query` answers `OpenableColumns` out of a real cursor, `getType` answers a type — so
 * the code under test goes through `ContentResolver` the way it does on a device, and the one case
 * that is easy to get wrong on a device (a provider that has no `DISPLAY_NAME` column at all) can
 * be set up by simply not offering one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SharedAttachmentStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: SharedAttachmentStore
    private lateinit var provider: FakeDocuments

    @Before
    fun setUp() {
        store = SharedAttachmentStore(context)
        provider =
            Robolectric.buildContentProvider(FakeDocuments::class.java).create(AUTHORITY).get()

        File(context.cacheDir, "shared").deleteRecursively()
    }

    // ------------------------------------------------------------- the point

    @Test
    fun `a staged file outlives the provider that supplied it`() = runTest {
        val source = provider.offer("Rechnung.pdf", "application/pdf", "invoice bytes")

        val staged = store.stage(SharedMessage(streams = listOf(source.toString())))

        // The grant going away, played out: the file behind the content URI is
        // gone and the provider would now refuse to open it. On a device this is
        // the task ending, or the photo being deleted from the gallery.
        provider.revokeEverything()

        val attachment = checkNotNull(stagedAttachment(staged.attachments.single()))

        assertEquals("Rechnung.pdf", attachment.name)
        assertEquals("application/pdf", attachment.type)
        // And, crucially, the bytes are still there to be read.
        assertEquals("invoice bytes", attachment.bytes())
    }

    @Test
    fun `the staged attachment points at our own storage rather than at the share`() {
        // The whole difference between a draft that survives an hour and one
        // that does not. A `content://` here would mean the copy was pointless.
        val staged = stageOne("note.txt", "text/plain", "hello")

        assertTrue(
            "expected a file:// uri under our cache, got ${staged.uri}",
            staged.uri.orEmpty().startsWith(URI_PREFIX + context.cacheDir.absolutePath),
        )
    }

    @Test
    fun `the size is the file's own rather than the provider's claim`() {
        val staged = stageOne("note.txt", "text/plain", "twelve chars")

        assertEquals("twelve chars".length.toLong(), staged.size)
    }

    // ---------------------------------------------------------------- naming

    @Test
    fun `a provider with no display name still yields something sendable`() {
        provider.offersName = false

        val staged = stageOne("ignored", "image/png", "png bytes")

        assertEquals("attachment", staged.name)
        assertEquals("image/png", staged.type)
    }

    @Test
    fun `a display name with a newline in it cannot tear the sidecar in half`() {
        // The name comes from another app. Written straight into a two-line
        // file, a newline in it would make the type come back as half a name.
        val staged = stageOne("two\nlines.pdf", "application/pdf", "bytes")

        assertEquals("two lines.pdf", staged.name)
        assertEquals("application/pdf", staged.type)
    }

    @Test
    fun `the display name never reaches the filesystem`() {
        // A name is another app's string and can be a path. It belongs in the
        // sidecar, which is data, and not in a path, which is not.
        val staged = stageOne("../../../databases/plmail.db", "application/octet-stream", "x")

        assertEquals("../../../databases/plmail.db", staged.name)
        assertTrue(staged.uri.orEmpty().endsWith("/content"))
        assertTrue(File(context.cacheDir, "shared").isDirectory)
    }

    // -------------------------------------------------------- what is refused

    @Test
    fun `a file over the ceiling is refused by name and never trimmed`() = runTest {
        val source = provider.offer("holiday.mp4", "video/mp4", "x".repeat(64), oversize = true)

        val staged = store.stage(SharedMessage(streams = listOf(source.toString())))

        assertEquals(emptyList<String>(), staged.attachments)
        assertEquals(listOf("holiday.mp4"), staged.tooLarge)
        assertEquals(emptyList<String>(), staged.unreadable)
        // Nothing half-written left behind.
        assertEquals(0, File(context.cacheDir, "shared").listFiles().orEmpty().size)
    }

    @Test
    fun `a file the provider describes but will not open is refused by name`() = runTest {
        // A cloud document with no local copy, or a grant that has already gone.
        val source = provider.offer("locked.pdf", "application/pdf", "bytes")
        provider.deny(source)

        val staged = store.stage(SharedMessage(streams = listOf(source.toString())))

        assertEquals(emptyList<String>(), staged.attachments)
        assertEquals(listOf("locked.pdf"), staged.unreadable)
    }

    @Test
    fun `one bad file does not cost the rest of the share`() = runTest {
        val good = provider.offer("good.txt", "text/plain", "fine")
        val huge = provider.offer("huge.mp4", "video/mp4", "x", oversize = true)

        val staged =
            store.stage(
                SharedMessage(
                    subject = "Photos",
                    text = "Here they are",
                    streams = listOf(good.toString(), huge.toString()),
                )
            )

        assertEquals(1, staged.attachments.size)
        assertEquals(listOf("huge.mp4"), staged.tooLarge)
        // The message itself is untouched. Losing an attachment is bad; losing
        // what was being written around it is worse.
        assertEquals("Photos", staged.subject)
        assertEquals("Here they are", staged.text)
    }

    @Test
    fun `size is reported before readability when both went wrong`() = runTest {
        val huge = provider.offer("huge.mp4", "video/mp4", "x", oversize = true)
        val gone = provider.offer("gone.pdf", "application/pdf", "bytes")
        provider.deny(gone)

        val staged = store.stage(SharedMessage(streams = listOf(huge.toString(), gone.toString())))

        val refusal = staged.refusal()

        assertEquals(ComposeError.AttachmentsTooLarge(listOf("huge.mp4"), MAX_MEGABYTES), refusal)
    }

    @Test
    fun `a share with no files at all is not an error`() = runTest {
        val staged = store.stage(SharedMessage(subject = "Just text", text = "no files"))

        assertEquals(emptyList<String>(), staged.attachments)
        assertNull(staged.refusal())
    }

    // ----------------------------------------------------------------- sweep

    @Test
    fun `a stale staging directory is reclaimed on the next share`() = runTest {
        val old = File(context.cacheDir, "shared/older-than-a-week").apply { mkdirs() }
        File(old, "content").writeText("forgotten")
        old.setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1_000)

        val fresh = File(context.cacheDir, "shared/from-this-morning").apply { mkdirs() }
        File(fresh, "content").writeText("still wanted")

        store.stage(SharedMessage())

        assertTrue("a week-old staging directory should be gone", !old.exists())
        assertTrue("this morning's should not be", fresh.exists())
    }

    // ------------------------------------------------------------- read back

    @Test
    fun `a directory that has been reclaimed reads back as nothing`() {
        // The composer then opens without that attachment, rather than with a
        // row pointing at a file that is not there.
        assertNull(stagedAttachment(File(context.cacheDir, "shared/never-existed").absolutePath))
    }

    @Test
    fun `a staging directory with no sidecar still yields a sendable file`() {
        val directory = File(context.cacheDir, "shared/half-written").apply { mkdirs() }
        File(directory, "content").writeText("bytes")

        val attachment = stagedAttachment(directory.absolutePath)

        assertEquals("attachment", attachment?.name)
        assertEquals("application/octet-stream", attachment?.type)
    }

    // ----------------------------------------------------------------- helpers

    private fun stageOne(name: String, type: String, body: String): StagedAttachment {
        val source = provider.offer(name, type, body)
        val staged = runBlocking {
            store.stage(SharedMessage(streams = listOf(source.toString())))
        }

        return checkNotNull(stagedAttachment(staged.attachments.single()))
    }

    /** What would actually be uploaded, read the way `ComposeRepository.upload` reads it. */
    private fun StagedAttachment.bytes(): String =
        checkNotNull(context.contentResolver.openInputStream(Uri.parse(uri)))
            .use { it.readBytes() }
            .decodeToString()

    private companion object {
        const val AUTHORITY = "de.plmail.test.documents"
        const val URI_PREFIX = "file://"
    }
}

/**
 * A provider that behaves like a real one, including in the ways that are inconvenient.
 *
 * It answers `OpenableColumns` from a cursor, hands out a genuine file descriptor, and can be told
 * to stop doing either — which is how the revoked-grant case is played out without needing a second
 * process. `oversize` marks a file that reports a modest size and then streams past the ceiling,
 * which is the case a store that trusted `OpenableColumns.SIZE` would wave through.
 */
class FakeDocuments : ContentProvider() {

    private class Entry(val name: String, val type: String, val file: File, val oversize: Boolean)

    private val entries = mutableMapOf<String, Entry>()
    private val denied = mutableSetOf<String>()

    var offersName: Boolean = true

    fun offer(name: String, type: String, body: String, oversize: Boolean = false): Uri {
        val id = (entries.size + 1).toString()
        val file = File.createTempFile("shared", null)

        if (oversize) {
            // Genuinely past the ceiling rather than merely claiming to be: the
            // store counts bytes as it copies and does not believe the provider.
            // Sparse, because 25MB of real zeroes per test is 25MB nobody reads.
            RandomAccessFile(file, "rw").use { it.setLength(MAX_BYTES + 1) }
        } else {
            file.writeText(body)
        }

        entries[id] = Entry(name, type, file, oversize)

        return Uri.parse("content://de.plmail.test.documents/$id")
    }

    /** Everything this provider handed out stops working, the way a revoked grant does. */
    fun revokeEverything() {
        denied += entries.keys
        entries.values.forEach { it.file.delete() }
    }

    fun deny(uri: Uri) {
        denied += uri.lastPathSegment.orEmpty()
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = uri.lastPathSegment.orEmpty()
        if (id in denied) throw SecurityException("no grant for $uri")

        val entry = entries[id] ?: throw java.io.FileNotFoundException(uri.toString())

        return ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val entry = entries[uri.lastPathSegment.orEmpty()] ?: return null

        // A provider with no DISPLAY_NAME column is a real thing, and the column
        // being absent rather than null is the shape that catches a naive read.
        if (!offersName) return MatrixCursor(arrayOf("_id")).apply { addRow(arrayOf(1)) }

        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            // A modest claim even for the oversize entry: the ceiling has to be
            // enforced over the bytes, not over what the provider says.
            addRow(arrayOf<Any>(entry.name, if (entry.oversize) 12L else entry.file.length()))
        }
    }

    override fun getType(uri: Uri): String? = entries[uri.lastPathSegment.orEmpty()]?.type

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
