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
class OkHttpTransport(private val client: OkHttpClient) : StreamingTransport {

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

                val call = client.newCall(okRequest)

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

        return client.newCall(okRequest).await(hostOf(request.url))
    }

    companion object {
        /**
         * A client shaped for a server on someone's home connection.
         *
         * The read timeout is generous because a NAS waking a spun-down disk to answer a query is
         * normal here, not a fault — and an aggressive timeout turns that into a retry storm
         * against the machine that is already struggling.
         *
         * Retries on connection failure are left ON, but note this only retries connection
         * establishment, never a request the server has already begun answering, so it cannot
         * duplicate a send.
         */
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                // Connection pooling matters more than usual: TLS handshakes to
                // a small box are expensive, and the client deliberately keeps
                // few connections open.
                .retryOnConnectionFailure(true)
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

                    continuation.resumeWithException(JmapError.Unreachable(host, e))
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
