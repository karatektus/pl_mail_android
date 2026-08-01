package de.plmail.jmap.client

import de.plmail.jmap.protocol.JmapError
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The real transport.
 *
 * The only file in this module that knows OkHttp exists, which is what lets the whole protocol
 * layer be tested on the JVM against a fake.
 */
class OkHttpTransport(private val client: OkHttpClient) : StreamingTransport, DownloadingTransport {

    /**
     * The same client, allowed to retry, used for GETs only.
     *
     * `retryOnConnectionFailure` is off on the client this is built from, and that is right for a
     * JMAP method call: OkHttp's `recover()` will re-send a request whose body it can replay *even
     * after the send has started*, so a `POST /jmap/api` carrying an `Email/set` could be applied
     * twice. It is wrong for everything else, because it is also what handles a **pooled connection
     * the server closed while nobody was looking** — and this product's user reads a message for
     * two minutes and then taps an attachment, which is precisely that window.
     *
     * Observed on 2026-08-01: `SocketException: Software caused connection abort`, suppressing
     * `unexpected end of stream`, on the session GET before a blob download, after the app had been
     * idle. The user is shown "could not reach your server" for a server that is running fine.
     *
     * `newBuilder()` shares the pool, dispatcher and thread pools, so this costs an object.
     */
    private val retrying: OkHttpClient by lazy {
        client.newBuilder().retryOnConnectionFailure(true).build()
    }

    /**
     * Which client a request may use.
     *
     * By method, not by URL: what makes a retry safe is that repeating the request cannot change
     * anything on the server, and GET is the only method this client sends for which that is true.
     * Session discovery and blob downloads are both GETs; every JMAP method call is a POST.
     */
    private fun clientFor(request: HttpRequest): OkHttpClient =
        if (request.method.equals("GET", ignoreCase = true)) retrying else client

    /**
     * Hands the body over without reading it, and closes the response afterwards.
     *
     * `execute()` on the IO dispatcher rather than `enqueue`, because the point is to keep the body
     * open across the caller's work: an enqueued call resumes a coroutine and then has nowhere to
     * hold the response while [receive] copies it. The `use` block is what guarantees the
     * connection goes back to the pool even when [receive] throws or the coroutine is cancelled
     * mid-copy — a download the user backed out of must not cost a connection.
     */
    override suspend fun <T> download(
        request: HttpRequest,
        receive: suspend (ResponseBody) -> T,
    ): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val okRequest =
                Request.Builder()
                    .url(request.url)
                    .apply { request.headers.forEach { (name, value) -> header(name, value) } }
                    .build()

            val call = clientFor(request).newCall(okRequest)

            try {
                call.execute().use { response ->
                    receive(
                        ResponseBody(
                            status = response.code,
                            contentType = response.body.contentType()?.toString(),
                            // -1 is OkHttp's "the server used chunked encoding",
                            // which this product's server does for a message
                            // blob. Reported as null rather than as a length of
                            // minus one, so a progress bar cannot be built on it
                            // by accident.
                            length = response.body.contentLength().takeIf { it >= 0 },
                            bytes = response.body.byteStream(),
                        )
                    )
                }
            } catch (failure: IOException) {
                throw ServerTrust.trustFailure(failure)
                    ?: JmapError.Unreachable(hostOf(request.url), failure)
            }
        }

    /**
     * Reads the body line by line as it arrives.
     *
     * `source().readUtf8Line()` blocks the calling thread, hence the IO dispatcher — and the flow
     * is cancellable at each line, so backgrounding the app closes the connection promptly rather
     * than at the next event, which on an idle mailbox could be thirty seconds away.
     */
    override fun lines(request: HttpRequest): kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow
            .flow {
                val okRequest =
                    Request.Builder()
                        .url(request.url)
                        .apply { request.headers.forEach { (n, v) -> header(n, v) } }
                        .build()

                val call = clientFor(request).newCall(okRequest)

                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw JmapError.UnexpectedStatus(response.code)
                    }

                    val source = response.body.source()

                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        emit(line)
                    }
                }
            }
            .flowOn(kotlinx.coroutines.Dispatchers.IO)

    override suspend fun send(request: HttpRequest): HttpResponse {
        val body = request.body?.toRequestBody(request.headers["Content-Type"]?.toMediaTypeOrNull())

        val okRequest =
            Request.Builder()
                .url(request.url)
                .method(request.method, body)
                .apply { request.headers.forEach { (name, value) -> header(name, value) } }
                .build()

        return clientFor(request).newCall(okRequest).await(hostOf(request.url))
    }

    companion object {
        /**
         * A client shaped for a server on someone's home connection.
         *
         * The read timeout is generous because a NAS waking a spun-down disk to answer a query is
         * normal here, not a fault — and an aggressive timeout turns that into a retry storm
         * against the machine that is already struggling.
         *
         * Retries on connection failure are off; see the field above, which turns them back on for
         * the requests where repeating one cannot change anything.
         */
        fun defaultClient(trust: ServerTrust? = null): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                // Connection pooling matters more than usual: TLS handshakes to
                // a small box are expensive, and the client deliberately keeps
                // few connections open.
                //
                // Retries are OFF here, and the class above turns them back on
                // for GETs alone. The comment this replaces said a retry "only
                // retries connection establishment, never a request the server
                // has already begun answering, so it cannot duplicate a send" --
                // which is not what OkHttp does. `RetryAndFollowUpInterceptor`
                // declines to retry only when the body is *one-shot*; a byte
                // array is replayable, so a POST whose send had already started
                // would go a second time. That is a duplicated `Email/set`.
                .retryOnConnectionFailure(false)
                // Without this the pinning in ServerTrust is dead code: the
                // platform's own evaluation runs, a self-signed NAS is refused,
                // and the fingerprint the user already accepted is never
                // consulted. A trust manager that nothing installs protects
                // nothing.
                .apply { trust?.let { serverTrust(it) } }
                .build()

        private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url
    }
}

/**
 * Bridges OkHttp's callback API to a coroutine, cancelling the call if the coroutine is cancelled.
 *
 * Without the cancellation handler a cancelled sync would leave its request in flight, which on a
 * four-permit gate means the next one waits for work nobody is going to read.
 */
private suspend fun Call.await(host: String): HttpResponse =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { cancel() } }

        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return

                    // A pinning refusal reaches here wrapped in whatever the
                    // handshake threw, so it has to be recovered from the cause
                    // chain. Reported as "cannot reach your server" it would
                    // send the user to check their network rather than to the
                    // one screen that can fix it.
                    val trustFailure = ServerTrust.trustFailure(e)

                    continuation.resumeWithException(trustFailure ?: JmapError.Unreachable(host, e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val headers =
                            it.headers.names().associateWith { name -> it.headers[name].orEmpty() }

                        continuation.resume(
                            HttpResponse(
                                status = it.code,
                                headers = headers,
                                // Non-null since OkHttp 5; an empty body is an
                                // empty array rather than an absent object.
                                body = it.body.bytes(),
                            )
                        )
                    }
                }
            }
        )
    }
