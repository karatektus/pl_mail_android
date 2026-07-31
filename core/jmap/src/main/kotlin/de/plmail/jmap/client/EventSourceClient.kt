package de.plmail.jmap.client

import de.plmail.jmap.methods.StateChange
import de.plmail.jmap.protocol.MethodResults
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Server-sent events, for a foreground session only.
 *
 * ## Read this before using it
 *
 * **Each connection holds a PHP worker for its entire life.** Under FrankenPHP that is a hard
 * capacity limit: N connected clients means N occupied workers, and once they are all taken the
 * server stops answering ordinary requests. On a home NAS, N is small. One app that keeps a stream
 * open in the background can make its owner's mail server stop responding — including the web UI
 * they would use to work out why.
 *
 * So: connect when the app is foregrounded, **disconnect the moment it is backgrounded**, and let
 * Web Push handle background delivery. The stream also holds a permit from the client's
 * [RequestGate] for its whole life, so it can never consume the entire concurrency budget.
 *
 * The server hard-closes every connection after 300 seconds and expects a reconnect. That is normal
 * operation, not an error.
 */
class EventSourceClient(
    private val client: JmapClient,
    private val transport: StreamingTransport,
    private val credential: Credential,
) {

    /**
     * A cold flow of state changes, reconnecting until cancelled.
     *
     * A `state` event arrives immediately on connect, so a subscriber knows where it stands without
     * an extra round trip. `ping` events are consumed rather than emitted — they keep the
     * connection alive and say nothing.
     */
    fun events(
        types: List<String> = DEFAULT_TYPES,
        ping: Int = DEFAULT_PING_SECONDS,
    ): Flow<StateChange> = flow {
        var backoff = INITIAL_BACKOFF

        while (true) {
            val session = client.session()
            val url =
                session.eventSourceUrl
                    ?: error("This server advertises no eventSourceUrl; poll instead.")

            val reservation = client.reservePermit()
            val parser = EventStreamParser()

            try {
                transport
                    .lines(
                        HttpRequest(
                            url =
                                url.expandTemplate(
                                    mapOf(
                                        "types" to types.joinToString(","),
                                        "closeafter" to "no",
                                        "ping" to ping.toString(),
                                    )
                                ),
                            method = "GET",
                            headers =
                                mapOf(
                                    "Accept" to "text/event-stream",
                                    "Authorization" to credential.authorizationHeader,
                                ),
                        )
                    )
                    .collect { line -> parser.consume(line)?.let { emit(it) } }

                // A clean end after ~300s is the server doing exactly what it
                // said it would, so the backoff resets rather than treating
                // normal operation as a fault and slowly backing off to a
                // minute between reconnects.
                backoff = INITIAL_BACKOFF
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // An unreachable server is the expected state for a NAS, not
                // an exceptional one. Back off and try again.
            } finally {
                reservation?.close()
            }

            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
        }
    }

    companion object {
        val DEFAULT_TYPES = listOf("Email", "Mailbox", "Thread", "EmailSubmission")

        /** The server's minimum is 5s; 30 is its default and plenty. */
        const val DEFAULT_PING_SECONDS = 30

        private val INITIAL_BACKOFF: Duration = 1.seconds
        private val MAX_BACKOFF: Duration = 60.seconds
    }
}

/**
 * Line-at-a-time `text/event-stream` parsing.
 *
 * Deliberately tolerant: anything unrecognised — comments, `ping` events, future event types — is
 * skipped rather than failing the stream. A parser that throws on an unknown event type breaks the
 * moment the server adds one.
 *
 * Stateful because the format is record-oriented: fields accumulate until a blank line ends the
 * record.
 */
internal class EventStreamParser {
    private var eventName: String? = null
    private val data = StringBuilder()

    /** Returns a change when [line] completes one, otherwise null. */
    fun consume(line: String): StateChange? {
        when {
            line.isBlank() -> return flush()
            // A leading colon is a comment, used as a keep-alive.
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
            line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
        }

        return null
    }

    private fun flush(): StateChange? {
        val payload = data.toString()
        data.clear()

        val name = eventName
        eventName = null

        if (name != null && name != STATE_EVENT) return null
        if (payload.isBlank()) return null

        return runCatching { MethodResults.JMAP_JSON.decodeFromString<StateChange>(payload) }
            .getOrNull()
    }

    private companion object {
        const val STATE_EVENT = "state"
    }
}
