package de.plmail.jmap.client

import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.JmapError
import de.plmail.jmap.protocol.MethodResults
import de.plmail.jmap.protocol.ProblemDocument
import de.plmail.jmap.protocol.RequestBuilder
import de.plmail.jmap.protocol.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Talks to one plMail server.
 *
 * Everything above this works in terms of typed methods and results; everything below is
 * [JmapTransport]. The client owns exactly three concerns that do not belong on either side:
 * discovering the session, keeping to the server's concurrency limit, and turning HTTP-shaped
 * failures into [JmapError].
 */
class JmapClient(
    private val discoveryUrl: String,
    private val credential: Credential,
    private val transport: JmapTransport,
) {
    private val sessionLock = Mutex()
    private var cachedSession: Session? = null
    private var inFlight: CompletableDeferred<Session>? = null
    private var gate: RequestGate? = null

    /**
     * The Session, fetched once and shared.
     *
     * Single-flighted: at launch the sidebar, the feed and every account's syncer all want it at
     * the same moment, and ten callers must cause one request rather than ten. They share the
     * in-flight result, so a failure reaches all of them and the *next* caller retries rather than
     * inheriting a cached error.
     */
    suspend fun session(): Session {
        cachedSession?.let {
            return it
        }

        // Ownership is decided inside the lock and never re-derived from
        // shared state afterwards. Comparing against `inFlight` later looks
        // equivalent and is not: another caller can clear it between the
        // release and the check, and the fetcher then awaits a deferred only it
        // will ever complete — a deadlock that needs a race to reproduce.
        var isOwner = false

        val waitOn = sessionLock.withLock {
            cachedSession?.let {
                return it
            }

            inFlight
                ?: CompletableDeferred<Session>().also {
                    inFlight = it
                    isOwner = true
                }
        }

        if (!isOwner) return waitOn.await()

        return try {
            val fetched = fetchSession()

            sessionLock.withLock {
                cachedSession = fetched
                gate = RequestGate(fetched.core.maxConcurrentRequests)
                inFlight = null
            }

            waitOn.complete(fetched)
            fetched
        } catch (error: Throwable) {
            // Cleared before completing, so the next caller starts a fresh
            // attempt rather than inheriting this failure forever.
            sessionLock.withLock { inFlight = null }
            waitOn.completeExceptionally(error)
            throw error
        }
    }

    /**
     * Forgets the cached session.
     *
     * Call on foreground and after recovering from a 401. The URLs in it are the server's to
     * change, and a client that caches them across a reverse proxy reconfiguration silently keeps
     * talking to the old place.
     */
    suspend fun invalidateSession() {
        sessionLock.withLock {
            cachedSession = null
            inFlight = null
        }
    }

    /** Confirms an address and credential actually work, and reports what the server says it is. */
    suspend fun verify(): Session {
        invalidateSession()
        return session()
    }

    /** Sends one batch of method calls. */
    suspend fun send(builder: RequestBuilder): MethodResults {
        require(!builder.isEmpty) { "Refusing to send a request with no method calls." }

        val session = session()
        val body =
            Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), builder.build())
        val encoded = body.encodeToByteArray()

        if (encoded.size > session.core.maxSizeRequestObject) {
            throw JmapError.RequestRejected(
                type = "requestTooLarge",
                status = 0,
                detail =
                    "This request is ${encoded.size} bytes; the server accepts " +
                        "${session.core.maxSizeRequestObject}. Split the batch.",
            )
        }

        val response = withPermit {
            transport.send(
                HttpRequest(
                    url = session.apiUrl,
                    method = "POST",
                    headers =
                        mapOf(
                            "Content-Type" to "application/json",
                            "Accept" to "application/json",
                            "Authorization" to credential.authorizationHeader,
                        ),
                    body = encoded,
                )
            )
        }

        return MethodResults.decode(response.body, response.status)
    }

    /**
     * Uploads bytes and gets back a blob id (RFC 8620 §6.1).
     *
     * Not a method call — a plain POST of raw bytes with the media type in `Content-Type`. The
     * ceiling comes from the live session rather than a constant, so an instance configured for
     * larger uploads is not second-guessed by its own client.
     *
     * Uploaded blobs are **scratch space** that the server sweeps on a timer, which is why compose
     * uploads at send time rather than when a file is picked: a draft left open overnight would
     * otherwise be sent with its attachments already collected.
     */
    suspend fun upload(data: ByteArray, contentType: String, accountId: AccountId): UploadedBlob {
        require(data.isNotEmpty()) { "Refusing to upload an empty file." }

        val session = session()

        if (data.size > session.core.maxSizeUpload) {
            throw JmapError.RequestRejected(
                type = "tooLarge",
                status = 413,
                detail =
                    "This file is ${data.size} bytes; the server accepts at most " +
                        "${session.core.maxSizeUpload}.",
            )
        }

        val url = session.uploadUrl.expandTemplate(mapOf("accountId" to accountId.value))

        val response = withPermit {
            transport.send(
                HttpRequest(
                    url = url,
                    method = "POST",
                    headers =
                        mapOf(
                            "Content-Type" to contentType,
                            "Accept" to "application/json",
                            "Authorization" to credential.authorizationHeader,
                        ),
                    body = data,
                )
            )
        }

        if (!response.isSuccess) {
            throw MethodResults.problemFrom(response.bodyAsText(), response.status)
        }

        return runCatching {
            MethodResults.JMAP_JSON.decodeFromString<UploadedBlob>(response.bodyAsText())
        }
            .getOrElse {
                throw JmapError.MalformedResponse("The upload response was not an upload object.")
            }
    }

    /**
     * Downloads one blob into [sink] and returns how many bytes arrived.
     *
     * The URL is built from the session's own `downloadUrl` template, never assembled here: the
     * blob endpoint sits behind whatever reverse proxy the user has put in front of their server,
     * and that is theirs to reconfigure. [name] and [type] are template variables rather than
     * decoration — the server uses them for the `Content-Disposition` filename and the
     * `Content-Type` it answers with, so a download asked for with the wrong type comes back
     * labelled wrongly and opens in the wrong app.
     *
     * Streamed rather than buffered. A message may carry fifty megabytes by this server's own
     * advertised limit, and `send` would put all of it on the heap of a phone that is also holding
     * a WebView.
     *
     * Throws [JmapError.RequestRejected] on a non-2xx, because a blob that is gone — swept upload,
     * message deleted on another device — is a normal outcome the caller has to be able to name.
     */
    suspend fun download(
        accountId: AccountId,
        blobId: String,
        name: String,
        type: String,
        sink: java.io.OutputStream,
    ): Long {
        val transport =
            transport as? DownloadingTransport
                ?: throw IllegalStateException(
                    "This transport cannot stream a download; blobs must not be buffered."
                )

        val session = session()

        val url =
            session.downloadUrl.expandTemplate(
                mapOf(
                    "accountId" to accountId.value,
                    "blobId" to blobId,
                    "name" to name,
                    "type" to type,
                )
            )

        return withPermit {
            transport.download(
                HttpRequest(
                    url = url,
                    method = "GET",
                    headers = mapOf("Authorization" to credential.authorizationHeader),
                )
            ) { body ->
                if (!body.isSuccess) {
                    throw JmapError.RequestRejected(
                        type = "blobNotFound",
                        status = body.status,
                        detail = "The server would not hand over blob $blobId.",
                    )
                }

                body.bytes.copyTo(sink)
            }
        }
    }

    /**
     * Runs [body] holding one concurrency permit.
     *
     * Before the first session arrives there is no advertised limit to respect, and the only
     * request in flight is the discovery one — so the permit is skipped rather than guessed at.
     */
    suspend fun <T> withPermit(body: suspend () -> T): T = gate?.withPermit(body) ?: body()

    /** Holds a permit for as long as a long-lived connection is open. */
    suspend fun reservePermit(): RequestGate.Reservation? = gate?.reserve()

    private suspend fun fetchSession(): Session {
        val response =
            transport.send(
                HttpRequest(
                    url = discoveryUrl,
                    method = "GET",
                    headers =
                        mapOf(
                            "Accept" to "application/json",
                            "Authorization" to credential.authorizationHeader,
                        ),
                )
            )

        when {
            response.status == 401 -> {
                val problem = runCatching {
                    MethodResults.JMAP_JSON.decodeFromString<ProblemDocument>(response.bodyAsText())
                }
                    .getOrNull()

                throw JmapError.NotAuthenticated(problem?.detail)
            }
            !response.isSuccess -> throw JmapError.UnexpectedStatus(response.status)
        }

        return runCatching {
            MethodResults.JMAP_JSON.decodeFromString<Session>(response.bodyAsText())
        }
            .getOrElse {
                throw JmapError.MalformedResponse(
                    "$discoveryUrl did not return a JMAP session. Is that the right address?"
                )
            }
    }
}

/** What `POST {uploadUrl}` answers with. */
@kotlinx.serialization.Serializable
data class UploadedBlob(
    val accountId: String = "",
    val blobId: String,
    val type: String = "application/octet-stream",
    val size: Long = 0,
)

/**
 * Fills in an RFC 6570 template of the simple `{name}` variety, which is all the session's URLs
 * use.
 *
 * Values are percent-encoded, because a blob's filename segment reaches this straight from a
 * message's headers and can contain anything at all.
 */
internal fun String.expandTemplate(values: Map<String, String>): String =
    values.entries.fold(this) { url, (key, value) ->
        url.replace("{$key}", java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20"))
    }
