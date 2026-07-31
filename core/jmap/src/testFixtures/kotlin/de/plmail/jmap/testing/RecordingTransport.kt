package de.plmail.jmap.testing

import de.plmail.jmap.client.HttpRequest
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.client.JmapTransport
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A transport that answers from a script and remembers what it was asked.
 *
 * Recording the requests is half the value: most of what can go wrong in this layer is in what the
 * client *sends* — a back-reference missing its `#`, an `accountId` serialised as a number, a
 * body-fetch argument with the wrong capitalisation — and none of that is visible from the
 * response.
 */
class RecordingTransport(private val handler: suspend (HttpRequest) -> HttpResponse) :
    JmapTransport {

    private val mutex = Mutex()
    private val _requests = mutableListOf<HttpRequest>()
    private val callCount = AtomicInteger(0)

    val requests: List<HttpRequest>
        get() = _requests.toList()

    val calls: Int
        get() = callCount.get()

    val lastBody: String?
        get() = _requests.lastOrNull()?.body?.decodeToString()

    override suspend fun send(request: HttpRequest): HttpResponse {
        callCount.incrementAndGet()
        mutex.withLock { _requests += request }

        return handler(request)
    }

    companion object {
        /** Answers every request with the same body. */
        fun alwaysReturning(body: String, status: Int = 200): RecordingTransport =
            RecordingTransport {
                HttpResponse(
                    status = status,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = body.encodeToByteArray(),
                )
            }

        /**
         * Answers by URL, so one transport can serve both the discovery request and the API calls
         * that follow it.
         */
        fun routing(
            vararg routes: Pair<String, String>,
            status: Int = 200,
        ): RecordingTransport {
            val table = routes.toMap()

            return RecordingTransport { request ->
                val body =
                    table.entries.firstOrNull { request.url.contains(it.key) }?.value
                        ?: error("No canned response for ${request.url}")

                HttpResponse(
                    status = status,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = body.encodeToByteArray(),
                )
            }
        }
    }
}

/**
 * A transport whose responses are released by the test rather than returned immediately.
 *
 * Needed for anything about concurrency: single-flighting a session and honouring a permit limit
 * are both claims about what happens *while* a request is outstanding, and a transport that answers
 * instantly never leaves one outstanding long enough to observe.
 */
class GatedTransport(private val response: () -> HttpResponse) : JmapTransport {
    private val started = mutableListOf<CompletableDeferred<Unit>>()
    private val mutex = Mutex()
    private val callCount = AtomicInteger(0)

    val calls: Int
        get() = callCount.get()

    /** Suspends until [release] is called. */
    override suspend fun send(request: HttpRequest): HttpResponse {
        callCount.incrementAndGet()

        val gate = CompletableDeferred<Unit>()
        mutex.withLock { started += gate }

        gate.await()

        return response()
    }

    /** Lets every outstanding request finish. */
    suspend fun releaseAll() {
        mutex.withLock { started.toList() }.forEach { it.complete(Unit) }
    }

    suspend fun outstanding(): Int = mutex.withLock { started.count { !it.isCompleted } }
}
