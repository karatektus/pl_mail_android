package de.plmail.feature.compose

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.plmail.core.data.StagedAttachment
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Takes a copy of everything a share brought with it, while it is still legal to read it.
 *
 * **The grant is the whole problem.** A `content://` URI arrives with
 * `FLAG_GRANT_READ_URI_PERMISSION`, and that grant is scoped to the task that received the intent
 * and revoked when the task ends. The bytes behind it can also simply stop existing: a photo shared
 * from a gallery and then deleted, a document provider that hands out a one-shot URI, a file on a
 * share that is unmounted an hour later. So a draft that merely stores the URI is a draft holding a
 * link that expires, and it expires quietly — nothing goes wrong until the send, which is minutes
 * or days later and by then nowhere near the share that caused it. `ComposeRepository.upload` turns
 * that into `"…" could not be read`, at the one moment the user can do least about it.
 *
 * This copies the bytes into the app's own cache instead, before the composer is ever shown, and
 * the draft points at the copy. What the user gets back after a process death is then a real file
 * rather than a revoked handle.
 *
 * **The name and the type are copied too**, into a sidecar beside the bytes. They come from the
 * provider — `OpenableColumns.DISPLAY_NAME` and `getType` — and asking the provider is exactly what
 * stops being possible once the grant is gone, so the answers are written down while there are
 * still answers to be had. Without them a reopened share sends `IMG_20260820.jpg` as
 * `attachment`/`application/octet-stream`.
 *
 * **The bytes are always at `<directory>/content`**, never under the name the provider gave. A
 * display name comes from another app and can be `../../databases/plmail.db` or four hundred
 * characters of nothing; putting it in a path is how a share becomes a write primitive. The name
 * belongs in the sidecar, which is data, and never in the filesystem, which is not.
 *
 * **In `cacheDir` rather than `filesDir`, with a sweep.** The window in which a copy matters is
 * short: the composer autosaves three seconds after it opens, and a successful save uploads the
 * bytes and replaces the staged file with the server's blob id — from then on the local copy is
 * dead weight that nothing reads. Files therefore need to survive a process death and a few hours
 * offline, not forever, and `filesDir` would mean every photo anybody ever shared kept until the
 * app is uninstalled. The sweep on the next share is what actually reclaims them; the platform
 * reclaiming `cacheDir` under storage pressure is the backstop, and it can only take files whose
 * draft has already been uploaded or abandoned.
 */
@Singleton
class SharedAttachmentStore
@Inject
constructor(@param:ApplicationContext private val context: Context) {

    /**
     * Copies [message]'s files out of their grant and returns the request that opens on them.
     *
     * On IO because it is: several megabytes per file, through a provider that may be another app's
     * process. The caller is a `LaunchedEffect` in the activity that received the intent, which is
     * what keeps the grant alive for the length of this.
     *
     * Nothing here throws. A file that cannot be taken is reported by name in the returned request
     * and the rest of the share still opens — losing one attachment out of four is a bad outcome,
     * and losing the message that was being written around them is a worse one.
     */
    suspend fun stage(message: SharedMessage): ComposeRequest.Share =
        withContext(Dispatchers.IO) {
            sweep()

            val staged = mutableListOf<String>()
            val tooLarge = mutableListOf<String>()
            val unreadable = mutableListOf<String>()

            message.streams.forEach { stream ->
                val uri = runCatching { stream.toUri() }.getOrNull()

                if (uri == null) {
                    unreadable += stream
                    return@forEach
                }

                val (name, type) = describe(uri)

                val directory = File(root(), UUID.randomUUID().toString())

                when (copy(uri, File(directory, CONTENT))) {
                    Outcome.COPIED -> {
                        File(directory, META).writeText(type + "\n" + name)
                        staged += directory.absolutePath
                    }
                    Outcome.TOO_LARGE -> {
                        directory.deleteRecursively()
                        tooLarge += name
                    }
                    Outcome.UNREADABLE -> {
                        directory.deleteRecursively()
                        unreadable += name
                    }
                }
            }

            ComposeRequest.Share(
                to = message.to,
                cc = message.cc,
                bcc = message.bcc,
                subject = message.subject,
                text = message.text,
                attachments = staged,
                tooLarge = tooLarge,
                unreadable = unreadable,
            )
        }

    /**
     * What the provider calls this file, and what it says is in it.
     *
     * Asked here rather than reused from `ComposeRepository.describe`, which asks the same question
     * for picked files. Two reasons, and the second is the real one. It answers with a
     * `StagedAttachment` pointed at the content URI — the exact thing that must not be kept, so two
     * thirds of the answer would be thrown away. And depending on it would drag the database, the
     * client pool and the credential store into a class whose whole job is copying a file, which is
     * the difference between this being covered by a test with a real provider in it and not being
     * covered at all.
     *
     * The name comes from `OpenableColumns` and not from the URI. A content URI's last path segment
     * is an opaque id on most providers, so a document called `Rechnung.pdf` would otherwise be
     * attached as `1000000042`.
     */
    private fun describe(uri: Uri): Pair<String, String> {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()

        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null

                cursor
                    .getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }
                    ?.let { cursor.getString(it) }
            }
        }
            .getOrNull()

        return name.oneLine(FALLBACK_NAME) to type.oneLine(FALLBACK_TYPE)
    }

    /**
     * Copies one file, refusing rather than finishing once it passes [MAX_BYTES].
     *
     * Counted while writing rather than trusted from `OpenableColumns.SIZE`, because that column is
     * optional, is null on a good number of providers, and is a claim by another app either way.
     * The ceiling is real: `upload` reads the whole attachment into a `ByteArray` before it posts
     * it, so a shared 400MB video is an `OutOfMemoryError` at send time rather than a slow send.
     *
     * The half-written file is deleted by the caller. **Nothing is ever truncated** — a message
     * that goes out with the first 25MB of a video looks like it worked.
     */
    private fun copy(uri: Uri, into: File): Outcome {
        into.parentFile?.mkdirs()

        return runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) Outcome.UNREADABLE else input.drainInto(into)
            }
        }
            // Every way a provider can refuse, and there are many: a revoked
            // grant is SecurityException, a deleted file is FileNotFound, a
            // provider that has been uninstalled since the share sheet drew it
            // is IllegalArgumentException, and a cloud provider with no local
            // copy throws whatever it likes. They all mean the same thing to
            // the user, so they are all one answer here.
            .getOrElse { Outcome.UNREADABLE }
    }

    private fun InputStream.drainInto(file: File): Outcome {
        file.outputStream().use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            var written = 0L

            while (true) {
                val read = read(buffer)
                if (read < 0) break

                written += read
                if (written > MAX_BYTES) return Outcome.TOO_LARGE

                output.write(buffer, 0, read)
            }
        }

        return Outcome.COPIED
    }

    /**
     * Drops staged files older than [KEEP_MILLIS], on the way into a new share.
     *
     * On the way in rather than on the way out, because there is no "out" to hook. The composer
     * closes long before the bytes are wanted: send hands the draft to `SendQueue`, which uploads
     * during the undo window and outside the composer's scope entirely, so deleting when the screen
     * closes would delete the attachment out from under the send. A stale directory costs cache
     * space until the next share; a deleted one costs the message.
     */
    private fun sweep() {
        val cutoff = System.currentTimeMillis() - KEEP_MILLIS

        root().listFiles().orEmpty().forEach { directory ->
            if (directory.lastModified() < cutoff) directory.deleteRecursively()
        }
    }

    private fun root(): File = File(context.cacheDir, ROOT).apply { mkdirs() }

    private enum class Outcome {
        COPIED,
        TOO_LARGE,
        UNREADABLE,
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024

        /**
         * Long enough to cover a share that is written offline and sent the next morning, short
         * enough that a phone does not accumulate a gallery. Anything the composer got as far as
         * saving is on the server by now and does not need its copy.
         */
        const val KEEP_MILLIS = 7L * 24 * 60 * 60 * 1_000
    }
}

/**
 * A staged file, read back from its directory.
 *
 * Free-standing and Android-free so the ViewModel can call it without holding a `Context`: by this
 * point everything is an absolute path under the app's own cache, and `java.io` is the whole of
 * what is needed. Null when the directory has been swept, or reclaimed, or never finished being
 * written — the composer then opens without that attachment rather than with a row pointing at
 * nothing.
 */
internal fun stagedAttachment(directory: String): StagedAttachment? {
    val content = File(directory, CONTENT)
    if (!content.isFile) return null

    val meta = runCatching { File(directory, META).readLines() }.getOrNull().orEmpty()

    return StagedAttachment(
        name = meta.getOrNull(1).oneLine(FALLBACK_NAME),
        type = meta.getOrNull(0).oneLine(FALLBACK_TYPE),
        // The file's own length rather than the provider's claim: this is the
        // number of bytes that will actually be sent.
        size = content.length(),
        // `file://` and not `content://`, which is the point of the whole
        // exercise. `ContentResolver.openInputStream` — what `upload` reads
        // through — takes either, and this one still resolves tomorrow. The
        // path is a UUID under our own cache, so it needs no escaping.
        uri = "file://" + content.absolutePath,
    )
}

/**
 * One line of it, or [fallback].
 *
 * A display name is a string another app chose and there is nothing stopping it holding newlines,
 * which would tear the two-line sidecar in half and make the type come back as half a filename.
 */
private fun String?.oneLine(fallback: String): String =
    this?.replace('\n', ' ')?.replace('\r', ' ')?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

/**
 * The bytes. A fixed name: see the note on [SharedAttachmentStore] about display names in paths.
 */
private const val CONTENT = "content"

/** The type on the first line, the display name on the second. */
private const val META = "meta"

private const val ROOT = "shared"

private const val FALLBACK_NAME = "attachment"

private const val FALLBACK_TYPE = "application/octet-stream"

/**
 * The largest file a share will take, per file.
 *
 * A fixed number rather than the session's `maxSizeUpload`, and that is a compromise worth stating.
 * The right ceiling is the server's, but it is per account and it is not known yet — the copy runs
 * before the composer has opened and therefore before an identity, and so an account, has been
 * chosen. 25MB is what most mail servers accept and comfortably inside what `upload` can hold in
 * one `ByteArray`. A file over it is refused **by name and out loud**, never trimmed.
 */
internal const val MAX_BYTES = 25L * 1024 * 1024

/** The same ceiling, for the sentence that has to name it. */
internal const val MAX_MEGABYTES = 25
