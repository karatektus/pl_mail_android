package de.plmail.core.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import de.plmail.jmap.drive.DriveClient
import de.plmail.jmap.drive.DriveFile
import de.plmail.jmap.drive.DriveFolder
import de.plmail.jmap.drive.DriveListing
import de.plmail.jmap.drive.DriveShare
import de.plmail.jmap.protocol.JmapError
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drive for the app: browse, upload from the phone, download, share links.
 *
 * A thin layer over [DriveClient] that adds the Android parts — reading a picked `content://` URI,
 * writing a download to the app's cache so it can be opened or shared through the FileProvider.
 * Nothing is cached in Room: Drive is browsed live, the way the web explorer does it.
 */
@Singleton
class DriveRepository
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val clients: AccountClients,
) {

    private suspend fun drive(): DriveClient =
        DriveClient(clients.current() ?: throw JmapError.NotAuthenticated("Not connected."))

    /** Whether the connected server has Drive. False when not connected or unreachable. */
    suspend fun isAvailable(): Boolean =
        runCatching { clients.current()?.let { DriveClient(it).isAvailable() } ?: false }
            .getOrDefault(false)

    suspend fun list(folderId: Long?): DriveListing = drive().list(folderId)

    suspend fun allFiles(): List<DriveFile> = drive().allFiles()

    suspend fun createFolder(name: String, parentId: Long?): DriveFolder =
        drive().createFolder(name, parentId)

    suspend fun renameFolder(id: Long, name: String) = drive().renameFolder(id, name)

    suspend fun deleteFolder(id: Long) = drive().deleteFolder(id)

    suspend fun renameFile(id: Long, name: String): DriveFile = drive().renameFile(id, name)

    suspend fun moveFile(id: Long, folderId: Long?): DriveFile = drive().moveFile(id, folderId)

    suspend fun deleteFile(id: Long) = drive().deleteFile(id)

    suspend fun shares(fileId: Long): List<DriveShare> = drive().shares(fileId)

    suspend fun createShare(fileId: Long, password: String?, expires: String?): DriveShare =
        drive().createShare(fileId, password, expires)

    suspend fun revokeShare(shareId: Long) = drive().revokeShare(shareId)

    /**
     * A link anyone can open for [file]: an existing unprotected, unexpired-by-server link when
     * there is one, otherwise a fresh one with no password and no expiry.
     *
     * This is what "insert a Drive link" in the composer and the event editor uses.
     */
    suspend fun linkFor(file: DriveFile): String {
        val client = drive()
        val existing = client.shares(file.id).firstOrNull { !it.isProtected }
        return (existing ?: client.createShare(file.id, null, null)).bestLink
    }

    /**
     * Uploads one picked file into [folderId] (null = Drive root).
     *
     * Read fully before sending — Drive's own upload ceiling (50 MB by default) is the bound.
     */
    suspend fun upload(uri: Uri, folderId: Long?): DriveFile {
        // The phone app reads a file whole before sending it, so it keeps to a
        // size a phone can hold; bigger files go up from the website.
        val declared =
            withContext(Dispatchers.IO) {
                runCatching {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                        }
                    }
                    .getOrNull()
            }
        if (declared != null && declared > PHONE_MAX_BYTES) {
            throw JmapError.RequestRejected(
                "tooLarge",
                413,
                "That file is ${declared / 1_000_000} MB. The phone app uploads up to ${PHONE_MAX_BYTES / 1_000_000} MB — use the website for bigger files.",
            )
        }

        val (name, type, bytes) =
            withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val type = resolver.getType(uri) ?: "application/octet-stream"
                val name =
                    runCatching {
                            resolver.query(uri, null, null, null, null)?.use { c ->
                                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (c.moveToFirst() && col >= 0) c.getString(col) else null
                            }
                        }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: (uri.lastPathSegment ?: "upload")
                val bytes =
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw JmapError.RequestRejected(
                            "unreadable",
                            0,
                            "That file can no longer be read. Pick it again.",
                        )
                Triple(name, type, bytes)
            }
        return drive().upload(name, type, bytes, folderId)
    }

    /**
     * Downloads [file] into the app cache (`cache/drive/`) and returns it, ready to open or share
     * through the FileProvider. The cache is the OS's to clear; nothing lands in shared storage
     * unless the user then chooses to save it.
     */
    suspend fun downloadToCache(file: DriveFile): File {
        val dir = File(context.cacheDir, "drive").apply { mkdirs() }
        val safe = file.name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").ifBlank { "file" }
        val target = File(dir, "${file.id}-$safe")
        val client = drive()
        withContext(Dispatchers.IO) {
            target.outputStream().use { out -> client.download(file.id, out) }
        }
        return target
    }

    /** Streams [file] into a user-chosen destination (Storage Access Framework "Save as"). */
    suspend fun downloadTo(file: DriveFile, destination: Uri) {
        val client = drive()
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(destination)?.use { out ->
                client.download(file.id, out)
            } ?: throw JmapError.RequestRejected("unwritable", 0, "Could not write there.")
        }
    }

    private companion object {
        const val PHONE_MAX_BYTES = 300L * 1024 * 1024
    }
}
