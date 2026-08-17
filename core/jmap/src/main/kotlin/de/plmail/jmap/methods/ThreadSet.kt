package de.plmail.jmap.methods

import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.JmapMethod
import de.plmail.jmap.protocol.ThreadId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `Thread/set` — a plMail extension, not RFC 8621.
 *
 * The spec's Thread is read-only: it is derived from its messages and has nothing on it to change.
 * plMail's differs in two ways — a thread can be snoozed, and a thread can be reported as having
 * been shown — and both belong to the conversation rather than to any message in it.
 *
 * **Snoozing is not a flag.** Setting `snoozedUntil` moves the conversation out of the Inbox and
 * into Snoozed, and a scheduled job brings it back. The web UI goes through the same service, so a
 * snooze means the same thing whichever client set it — which is exactly why this must not be
 * reimplemented locally.
 *
 * **Reporting a display is how the New marker is retired**, and it is the only way a client that is
 * not the browser can do it. Before the server published `isNew` a mailbox triaged entirely on a
 * phone opened in the browser with every conversation from the last day still badged, because only
 * a rendered Twig list could clear one. See [MailThread.isNew].
 *
 * `create` and `destroy` are refused by the server outright: threads come into being when mail
 * arrives and go away when their last message does.
 */
class ThreadSet(
    private val accountId: AccountId,
    private val update: Map<ThreadId, ThreadPatch>,
) : JmapMethod<ThreadSetResult> {

    override val name = "Thread/set"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put(
            "update",
            buildJsonObject { update.forEach { (id, patch) -> put(id.value, patch.toJson()) } },
        )
    }

    override fun decode(json: Json, arguments: JsonObject): ThreadSetResult =
        json.decodeFromJsonElement(ThreadSetResult.serializer(), arguments)

    companion object {
        /** Puts a conversation away until [utcDateTime]. */
        fun snooze(accountId: AccountId, threadId: ThreadId, utcDateTime: String) =
            ThreadSet(accountId, mapOf(threadId to ThreadPatch.snoozedUntil(utcDateTime)))

        /** Brings it back now. */
        fun unsnooze(accountId: AccountId, threadId: ThreadId) =
            ThreadSet(accountId, mapOf(threadId to ThreadPatch.snoozedUntil(null)))

        /**
         * Reports that these conversations have been put in front of the user.
         *
         * A batch rather than one call per row, because that is how they arrive: a list draws a
         * page at a time, and one request per visible row would be twenty-five round trips to
         * somebody's NAS for a column nothing is waiting on.
         *
         * Safe to repeat. The server keeps the *first* display time and a later report changes
         * nothing, so a caller may send this for every row it draws without tracking which ones it
         * has reported before — which is what makes it usable from a scroll listener.
         */
        fun shown(accountId: AccountId, threadIds: List<ThreadId>) =
            ThreadSet(accountId, threadIds.associateWith { ThreadPatch.shown() })
    }
}

class ThreadPatch private constructor(private val fields: Map<String, JsonElement>) {

    fun toJson(): JsonObject = JsonObject(fields)

    companion object {
        /**
         * `isNew: false`, and there is no counterpart that sets it true.
         *
         * The server refuses `true` outright, and rightly: the marker records that a row *was*
         * displayed, so un-retiring it would mean writing over a fact — and a client able to do it
         * could keep its own badges alive for ever.
         */
        fun shown(): ThreadPatch =
            ThreadPatch(mapOf("isNew" to kotlinx.serialization.json.JsonPrimitive(false)))

        fun snoozedUntil(utcDateTime: String?): ThreadPatch =
            ThreadPatch(
                mapOf(
                    "snoozedUntil" to
                        (utcDateTime?.let { kotlinx.serialization.json.JsonPrimitive(it) }
                            ?: JsonNull)
                )
            )
    }
}

@Serializable
data class ThreadSetResult(
    val accountId: String = "",
    val oldState: String? = null,
    val newState: String = "",
    val updated: Map<String, JsonElement?> = emptyMap(),
    val notUpdated: Map<String, SetError> = emptyMap(),
)
