package de.plmail.jmap.methods

import de.plmail.jmap.protocol.JmapMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `NotificationSettings/get` — which mail the user wants to be interrupted for, everywhere
 * (pl_mail#369).
 *
 * A singleton like `Appearance/get`: id `"singleton"`, no `accountId`, declared under
 * `urn:plmail:params:jmap:notifications`. The choice belongs to the person, not to a device, so the
 * web, every browser and this app read the same list. The server filters the browsers' pushes with
 * it; this app still gets every push, because pushes drive its sync, and applies the list itself.
 */
class NotificationSettingsGet : JmapMethod<NotificationSettingsGetResult> {

    override val name = "NotificationSettings/get"

    override fun arguments(): JsonObject = buildJsonObject { put("ids", JsonNull) }

    override fun decode(json: Json, arguments: JsonObject): NotificationSettingsGetResult =
        json.decodeFromJsonElement(NotificationSettingsGetResult.serializer(), arguments)
}

@Serializable
data class NotificationSettingsGetResult(
    val state: String = "",
    val list: List<NotificationSettings> = emptyList(),
) {
    val settings: NotificationSettings?
        get() = list.firstOrNull()
}

/**
 * The user's notification switches.
 *
 * [scopes] is the list that is **on**, always spelled out — `["category:primary"]` for somebody who
 * never chose. The keys are the ones [de.plmail.core.data.NotifyScope] builds; the server stores
 * exactly what this app would.
 */
@Serializable
data class NotificationSettings(
    val scopes: List<String> = emptyList(),
    /** The browsers' "Only notify for unread mail". The app knows what is read and ignores it. */
    val skipRead: Boolean = false,
) {
    companion object {
        const val SINGLETON = "singleton"
    }
}

/**
 * `NotificationSettings/set` — replaces the whole list of switches that are on.
 *
 * The whole list rather than one toggle, because that is what the server takes: what is stored is
 * what was sent, and there is nothing to reconcile.
 */
class NotificationSettingsSet(private val scopes: List<String>) :
    JmapMethod<NotificationSettingsSetResult> {

    override val name = "NotificationSettings/set"

    override fun arguments(): JsonObject = buildJsonObject {
        put(
            "update",
            buildJsonObject {
                put(
                    NotificationSettings.SINGLETON,
                    buildJsonObject { put("scopes", JsonArray(scopes.map(::JsonPrimitive))) },
                )
            },
        )
    }

    override fun decode(json: Json, arguments: JsonObject): NotificationSettingsSetResult =
        json.decodeFromJsonElement(NotificationSettingsSetResult.serializer(), arguments)
}

@Serializable
data class NotificationSettingsSetResult(
    val newState: String = "",
    val updated: Map<String, JsonObject?> = emptyMap(),
    val notUpdated: Map<String, SetError> = emptyMap(),
) {
    /** Why the write was refused, if it was. */
    val refusal: SetError?
        get() = notUpdated[NotificationSettings.SINGLETON]
}
