package de.plmail.core.data

import de.plmail.core.datastore.PushLogStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * How a delivery reached this device.
 *
 * Four values rather than two, because "push" is not one thing here and the difference is exactly
 * what somebody comparing against the server's delivery log needs. The server knows it sent one
 * message; the phone knows whether it came through a distributor the user installed, through
 * Google, or down a stream the app was holding open at the time.
 */
enum class PushDelivery(val wire: String) {
    /**
     * A Web Push POST decrypted by a UnifiedPush distributor.
     *
     * Named for the distributor rather than for the protocol, because that is the part that can be
     * uninstalled: on the server's side of the log this and [WEBPUSH] are indistinguishable, and on
     * this side the difference is which app to check.
     */
    UNIFIEDPUSH("unifiedpush"),

    /** A Web Push POST that reached the app by some other RFC 8030 route. */
    WEBPUSH("webpush"),

    /** A Firebase data message. */
    FCM("fcm"),

    /**
     * An EventSource `state` event, which is not a push at all.
     *
     * Logged beside the others anyway, and that is the point: a user watching this screen while the
     * app is open would otherwise see every message arrive "by push" when the app was in fact
     * holding a stream and the subscription may have been delivering nothing for weeks.
     */
    STREAM("stream");

    companion object {
        fun of(wire: String?): PushDelivery? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One delivery, as the app is willing to write it down.
 *
 * **No mail content, ever, and not merely because JMAP does not push any.** What arrives is a map
 * of account id to the object types whose state token moved, so that is what is kept: which
 * account, which types. A subject line here would be a copy of somebody's mail sitting in a
 * diagnostics file for the next two hundred messages.
 */
@Serializable
data class ReceivedPush(
    /** Device clock, milliseconds. Compared against the server's log, which is on its own clock. */
    val at: Long,
    val transport: String,
    /** The payload's `@type`: `StateChange`, `PushVerification`, or what it claimed to be. */
    val type: String,
    /** Per account id, the object types the payload said had moved. Empty for a verification. */
    val changed: Map<String, List<String>> = emptyMap(),
    /**
     * What the app made of it, when that is not obvious.
     *
     * Set for the cases worth explaining rather than for every entry: a payload that could not be
     * parsed, or a verification that was answered. An entry with nothing to add carries nothing.
     */
    val note: String? = null,
) {
    val delivery: PushDelivery?
        get() = PushDelivery.of(transport)
}

/**
 * The received-push log: the receiving half of a pair.
 *
 * The server keeps its own record of what it dispatched. This keeps what landed. Neither is
 * interesting on its own — a server log full of sends proves nothing about a phone, and a quiet
 * phone proves nothing about a server — and together they turn "push does not work" into a line
 * number.
 *
 * Recording is deliberately unconditional and happens **before** the payload is interpreted. A
 * message the client cannot parse still proves the chain works end to end, so logging only the ones
 * it understood would hide a client bug behind "nothing is arriving".
 */
@Singleton
class PushLog @Inject constructor(private val store: PushLogStore) {

    /** Newest first. Unparseable lines are dropped rather than drawn as damaged rows. */
    val entries: Flow<List<ReceivedPush>> =
        store.entries.map { lines -> lines.mapNotNull(::decode) }

    suspend fun record(entry: ReceivedPush) {
        store.append(JSON.encodeToString(ReceivedPush.serializer(), entry))
    }

    suspend fun clear() {
        store.clear()
    }

    private fun decode(line: String): ReceivedPush? =
        try {
            JSON.decodeFromString(ReceivedPush.serializer(), line)
        } catch (malformed: SerializationException) {
            // A log written by an older build, or a half-flushed line. Dropped
            // rather than surfaced: this screen exists to make the truth
            // legible and a row of decode errors is the opposite.
            null
        } catch (malformed: IllegalArgumentException) {
            null
        }

    private companion object {
        /**
         * Lenient about unknown keys for the same reason the JMAP codec is: a log written by a
         * later build must not make an earlier one throw away the user's evidence.
         */
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}
