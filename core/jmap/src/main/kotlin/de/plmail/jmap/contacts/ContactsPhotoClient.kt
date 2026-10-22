package de.plmail.jmap.contacts

import de.plmail.jmap.client.HttpRequest
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.JmapError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

/**
 * Contact photos: upload and download.
 *
 * The JMAP methods name photos by URL; the bytes travel outside JMAP. Upload is a multipart POST to
 * the `apiUrl` the CONTACTS capability advertises (`…/jmap/contacts/photo`), answering `{"blobId":
 * …}`; the blob id is then referenced from `Contact/set` as `photoBlobId`, which the server
 * consumes once. Download is an authorized GET of the `photoUrl` a `Contact/get` returned.
 *
 * Every call goes through [JmapClient.authorized], which attaches the same credential the mail
 * calls use and refuses to send it anywhere but this server's own origin — the photo URL must never
 * be handed to an image loader directly.
 */
class ContactsPhotoClient(private val client: JmapClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Uploads one photo and returns the blob id for `Contact/set`'s `photoBlobId`.
     *
     * The server allows jpg/png/webp up to 5 MB and downscales to 512px itself; the client mirrors
     * the ceiling so a picked RAW file fails here with a readable message instead of after the
     * round trip.
     */
    suspend fun upload(name: String, contentType: String, data: ByteArray): String {
        require(data.isNotEmpty()) { "Refusing to upload an empty photo." }
        if (data.size > MAX_PHOTO_BYTES) {
            throw JmapError.RequestRejected(
                "tooLarge",
                413,
                "That photo is ${data.size / 1_000_000} MB; contact photos are at most " +
                    "${MAX_PHOTO_BYTES / 1_000_000} MB.",
            )
        }

        val partType = contentType.ifBlank { "application/octet-stream" }.toMediaTypeOrNull()
        val multipart =
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("photo", name.ifBlank { "photo" }, data.toRequestBody(partType))
                .build()
        val buffer = Buffer()
        multipart.writeTo(buffer)

        val response =
            client.authorized(
                HttpRequest(
                    url = photoEndpoint(),
                    method = "POST",
                    headers =
                        mapOf(
                            "Accept" to "application/json",
                            "Content-Type" to multipart.contentType().toString(),
                        ),
                    body = buffer.readByteArray(),
                )
            )
        if (response.status == 401)
            throw JmapError.NotAuthenticated("The server refused the photo upload.")
        if (!response.isSuccess) {
            throw JmapError.RequestRejected(
                type = "photoUpload",
                status = response.status,
                detail = "The server would not take that photo (HTTP ${response.status}).",
            )
        }

        return runCatching {
            json.decodeFromString<PhotoUploadResult>(response.bodyAsText()).blobId
        }
            .getOrElse {
                throw JmapError.MalformedResponse("The photo upload answered unexpectedly.")
            }
    }

    /**
     * The photo's bytes, or null when the contact has no photo.
     *
     * Bounded: a photo the server did not downscale is still only read up to [MAX_PHOTO_BYTES]
     * before the call is refused rather than the phone holding an unbounded download.
     */
    suspend fun bytes(photoUrl: String?): ByteArray? {
        if (photoUrl.isNullOrBlank()) return null

        val response = client.authorized(HttpRequest(url = photoUrl, method = "GET"))
        if (response.status == 401)
            throw JmapError.NotAuthenticated("The server refused the photo.")
        if (!response.isSuccess) {
            throw JmapError.RequestRejected(
                type = "photoDownload",
                status = response.status,
                detail = "The server would not hand over that photo (HTTP ${response.status}).",
            )
        }
        if (response.body.size > MAX_PHOTO_BYTES) {
            throw JmapError.RequestRejected(
                "tooLarge",
                413,
                "That photo is larger than ${MAX_PHOTO_BYTES / 1_000_000} MB; refusing to hold it.",
            )
        }
        return response.body
    }

    /**
     * The photo endpoint, read from the CONTACTS capability's `apiUrl` — the session's `apiUrl` is
     * the JMAP API endpoint (`…/jmap/api`), not the path prefix the photo routes live under.
     */
    private suspend fun photoEndpoint(): String = baseUrl() + "/photo"

    private suspend fun baseUrlOrNull(): String? =
        client
            .session()
            .capabilities[Capability.CONTACTS]
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
                "This server has no Contacts. Update the server to get it.",
            )

    companion object {
        /** The server's own ceiling: jpg/png/webp, 5 MB. */
        const val MAX_PHOTO_BYTES = 5_000_000
    }
}

@Serializable private data class PhotoUploadResult(val blobId: String = "")
