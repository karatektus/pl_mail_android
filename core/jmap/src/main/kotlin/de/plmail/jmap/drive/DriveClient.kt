package de.plmail.jmap.drive

import de.plmail.jmap.client.HttpRequest
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.JmapError
import java.net.URLEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Drive over the server's `/jmap/drive` REST API.
 *
 * The base URL comes from the session capability [Capability.DRIVE] — never hard-coded — and every
 * call goes through [JmapClient.authorized], which attaches the same credential the mail calls use
 * and refuses to send it anywhere but this server's own origin.
 */
class DriveClient(private val client: JmapClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** True when the connected server offers Drive at all. */
    suspend fun isAvailable(): Boolean = baseUrlOrNull() != null

    /** The largest single upload the server accepts. */
    suspend fun maxUploadBytes(): Long =
        client
            .session()
            .capabilities[Capability.DRIVE]
            ?.get("maxSizeUpload")
            ?.jsonPrimitive
            ?.content
            ?.toLongOrNull() ?: 50_000_000L

    suspend fun list(folderId: Long?): DriveListing =
        decode(get(if (folderId == null) "" else "?folder=$folderId"))

    /** Every file, across folders, newest first — for "attach from Drive" pickers. */
    suspend fun allFiles(): List<DriveFile> = decode<DriveAllFiles>(get("/all")).files

    suspend fun upload(name: String, contentType: String, data: ByteArray, folderId: Long?): DriveFile {
        require(data.isNotEmpty()) { "Refusing to upload an empty file." }
        val max = maxUploadBytes()
        if (data.size > max) {
            throw JmapError.RequestRejected(
                "tooLarge",
                413,
                "This file is ${data.size / 1_000_000} MB; Drive accepts at most ${max / 1_000_000} MB.",
            )
        }
        val query = buildString {
            append("/upload?name=").append(enc(name))
            folderId?.let { append("&folder=").append(it) }
        }
        return decode(
            send("POST", query, data, contentType.ifBlank { "application/octet-stream" })
        )
    }

    suspend fun createFolder(name: String, parentId: Long?): DriveFolder =
        decode(
            sendJson(
                "POST",
                "/folder",
                buildJsonObject {
                    put("name", name)
                    put("parentId", parentId?.let { JsonPrimitive(it) } ?: JsonNull)
                },
            )
        )

    suspend fun renameFolder(id: Long, name: String) {
        sendJson("PATCH", "/folder/$id", buildJsonObject { put("name", name) })
    }

    suspend fun deleteFolder(id: Long) {
        send("DELETE", "/folder/$id")
    }

    suspend fun renameFile(id: Long, name: String): DriveFile =
        decode(sendJson("PATCH", "/file/$id", buildJsonObject { put("name", name) }))

    suspend fun moveFile(id: Long, folderId: Long?): DriveFile =
        decode(
            sendJson(
                "PATCH",
                "/file/$id",
                buildJsonObject { put("folderId", folderId?.let { JsonPrimitive(it) } ?: JsonNull) },
            )
        )

    suspend fun deleteFile(id: Long) {
        send("DELETE", "/file/$id")
    }

    suspend fun download(id: Long, sink: java.io.OutputStream): Long =
        client.authorizedDownload(baseUrl() + "/file/$id/download", sink)

    suspend fun shares(fileId: Long): List<DriveShare> =
        decode<DriveShares>(get("/file/$fileId/shares")).shares

    /**
     * Creates a public link. [expires] is `YYYY-MM-DD` or null for never; a blank [password] means
     * none. A protected link has no `downloadUrl` — the recipient must open [DriveShare.url].
     */
    suspend fun createShare(fileId: Long, password: String?, expires: String?): DriveShare =
        decode(
            sendJson(
                "POST",
                "/file/$fileId/shares",
                buildJsonObject {
                    password?.takeIf { it.isNotEmpty() }?.let { put("password", it) }
                    expires?.takeIf { it.isNotBlank() }?.let { put("expires", it) }
                },
            )
        )

    suspend fun revokeShare(shareId: Long) {
        send("DELETE", "/share/$shareId")
    }

    // ---------------------------------------------------------------- plumbing

    private suspend fun baseUrlOrNull(): String? =
        client
            .session()
            .capabilities[Capability.DRIVE]
            ?.get("apiUrl")
            ?.jsonPrimitive
            ?.content
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }

    private suspend fun baseUrl(): String =
        baseUrlOrNull()
            ?: throw JmapError.RequestRejected(
                "unknownCapability",
                0,
                "This server has no Drive. Update the plMail server to get it.",
            )

    private suspend fun get(path: String): HttpResponse = send("GET", path)

    private suspend fun sendJson(
        method: String,
        path: String,
        body: kotlinx.serialization.json.JsonObject,
    ): HttpResponse = send(method, path, body.toString().encodeToByteArray(), "application/json")

    private suspend fun send(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String? = null,
    ): HttpResponse {
        val headers = buildMap {
            put("Accept", "application/json")
            contentType?.let { put("Content-Type", it) }
        }
        val response =
            client.authorized(
                HttpRequest(url = baseUrl() + path, method = method, headers = headers, body = body)
            )
        if (response.status == 401) throw JmapError.NotAuthenticated(problem(response))
        if (!response.isSuccess) {
            throw JmapError.RequestRejected(
                type = "drive",
                status = response.status,
                detail = problem(response) ?: "Drive answered with HTTP ${response.status}.",
            )
        }
        return response
    }

    private fun problem(response: HttpResponse): String? =
        runCatching { json.decodeFromString<DriveProblem>(response.bodyAsText()).detail }.getOrNull()

    private inline fun <reified T> decode(response: HttpResponse): T =
        runCatching { json.decodeFromString<T>(response.bodyAsText()) }
            .getOrElse { throw JmapError.MalformedResponse("Drive sent something unexpected.") }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

@Serializable
data class DriveFolder(val id: Long, val name: String, val parentId: Long? = null)

@Serializable
data class DriveFile(
    val id: Long,
    val name: String,
    val type: String = "application/octet-stream",
    val size: Long = 0,
    val folderId: Long? = null,
    val updatedAt: String = "",
    val shareCount: Int = 0,
    val folderName: String? = null,
)

@Serializable
data class DriveListing(
    val folder: DriveFolder? = null,
    val breadcrumbs: List<DriveFolder> = emptyList(),
    val folders: List<DriveFolder> = emptyList(),
    val files: List<DriveFile> = emptyList(),
    val usedBytes: Long = 0,
    val quotaBytes: Long? = null,
)

@Serializable
data class DriveShare(
    val id: Long,
    val url: String,
    val downloadUrl: String? = null,
    @kotlinx.serialization.SerialName("protected") val isProtected: Boolean = false,
    val expiresAt: String? = null,
    val downloadCount: Int = 0,
    val createdAt: String = "",
) {
    /**
     * The link to hand someone: the share's landing page, which shows the file name and size with a
     * Download button — and a "Report this link" for the recipient.
     */
    val bestLink: String
        get() = url
}

@Serializable internal data class DriveAllFiles(val files: List<DriveFile> = emptyList())

@Serializable internal data class DriveShares(val shares: List<DriveShare> = emptyList())

@Serializable internal data class DriveProblem(val type: String? = null, val detail: String? = null)
