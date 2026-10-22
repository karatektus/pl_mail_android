package de.plmail.jmap.methods

import de.plmail.jmap.client.HttpRequest
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.JmapError
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A note's pictures, drawings and voice notes: the server's `mediaUrl` (notes capability),
 * `…/jmap/notes/{noteId}/media`. An upload is the raw file as the body, a download a GET, a removal
 * a DELETE. Note/get's `media` list says what is there.
 */
class NoteMediaClient(private val client: JmapClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether the server takes media on notes at all (older servers do not). */
    suspend fun isAvailable(): Boolean = template() != null

    suspend fun upload(
        noteId: String,
        kind: String,
        name: String,
        contentType: String,
        data: ByteArray,
    ): NoteMedia {
        val url = "${base(noteId)}?kind=${enc(kind)}&name=${enc(name)}"
        val response =
            client.authorized(
                HttpRequest(
                    url = url,
                    method = "POST",
                    headers = mapOf("Accept" to "application/json", "Content-Type" to contentType),
                    body = data,
                )
            )
        if (response.status == 401) throw JmapError.NotAuthenticated("Sign in again.")
        if (!response.isSuccess) {
            val detail = runCatching {
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["description"]
                    ?.jsonPrimitive
                    ?.content
            }
                .getOrNull()
            throw JmapError.RequestRejected(
                "noteMedia",
                response.status,
                detail ?: "The server did not take that file (HTTP ${response.status}).",
            )
        }
        return json.decodeFromString(NoteMedia.serializer(), response.bodyAsText())
    }

    suspend fun download(noteId: String, mediaId: String, sink: java.io.OutputStream): Long =
        client.authorizedDownload("${base(noteId)}/$mediaId", sink)

    suspend fun delete(noteId: String, mediaId: String) {
        val response =
            client.authorized(HttpRequest(url = "${base(noteId)}/$mediaId", method = "DELETE"))
        if (!response.isSuccess && response.status != 404) {
            throw JmapError.RequestRejected(
                "noteMedia",
                response.status,
                "The server would not remove that file (HTTP ${response.status}).",
            )
        }
    }

    private suspend fun template(): String? =
        client
            .session()
            .capabilities[Capability.NOTES]
            ?.get("mediaUrl")
            ?.jsonPrimitive
            ?.content
            ?.takeIf { it.isNotBlank() }

    private suspend fun base(noteId: String): String =
        (template()
                ?: throw JmapError.RequestRejected(
                    "unknownCapability",
                    0,
                    "This server cannot keep pictures or voice notes yet. Update the server.",
                ))
            .replace("{noteId}", enc(noteId))

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
