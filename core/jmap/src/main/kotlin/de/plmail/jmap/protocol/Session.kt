package de.plmail.jmap.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Session object (RFC 8620 §2), and the root of everything.
 *
 * Every other URL the client uses is discovered from here and **must be re-read rather than derived
 * or cached across restarts**. The server generates them from the request's `Host` header, which is
 * what lets one credential work from an emulator reaching `10.0.2.2:8002` and a phone reaching
 * `nas.local` — and it is also why a client that builds `apiUrl` by appending to the address the
 * user typed silently talks to the wrong place after a reverse-proxy change.
 */
@Serializable
data class Session(
    val capabilities: Map<String, JsonObject> = emptyMap(),
    val accounts: Map<String, Account> = emptyMap(),
    val primaryAccounts: Map<String, String> = emptyMap(),
    val username: String = "",
    val apiUrl: String,
    val downloadUrl: String,
    val uploadUrl: String,
    val eventSourceUrl: String? = null,
    val state: String = "",
) {
    val core: CoreCapability
        get() = capabilities[Capability.CORE]?.let(CoreCapability::from) ?: CoreCapability()

    /**
     * The VAPID key needed before a Web Push subscription can be created.
     *
     * Null *or blank* means Web Push is unconfigured on this instance — the server publishes the
     * capability either way, so presence of the key is the signal, not presence of the capability.
     * Don't offer push when it is absent.
     */
    val vapidPublicKey: String?
        get() =
            capabilities[Capability.PUSH]
                ?.get("vapidPublicKey")
                ?.jsonPrimitive
                ?.contentOrNullIfBlank()

    /** Accounts, in a stable order, keyed by their id. */
    val accountIds: List<AccountId>
        get() = accounts.keys.sorted().map(::AccountId)

    fun account(id: AccountId): Account? = accounts[id.value]

    /**
     * The account the server nominates as primary for mail.
     *
     * A convenience, not a default view: plMail exposes **one JMAP account per connected mailbox**,
     * and the unified inbox — which is the product's default — is every account merged client-side.
     * Anything that reaches for only the primary is almost certainly a bug.
     */
    val primaryMailAccount: AccountId?
        get() = primaryAccounts[Capability.MAIL]?.let(::AccountId)

    /**
     * The one account that serves calendars, or null on an instance without the extension.
     *
     * Unlike mail, this really is the whole surface rather than a default view: **every other
     * account answers a calendar method with `accountNotSupportedByMethod`**, and an unknown id
     * with `accountNotFound`. Calendars are user-scoped in plMail while JMAP accounts are per
     * connected mailbox, so fanning a calendar request out the way the unified inbox fans out
     * `Email/query` fails on every account but one.
     *
     * Read from `primaryAccounts` under the calendars URN rather than reused from
     * [primaryMailAccount]. The two agree on this server, which is exactly what would let a wrong
     * assumption go unnoticed until an instance disagreed.
     */
    val primaryCalendarAccount: AccountId?
        get() = primaryAccounts[Capability.CALENDARS]?.let(::AccountId)

    /**
     * The calendar limits for one account, or null when that account serves no calendars.
     *
     * Absence is the signal — the second account in a multi-mailbox login publishes no calendars
     * capability at all — so this doubles as the check for whether calendars may be asked of it.
     */
    fun calendars(id: AccountId): CalendarsCapability? =
        account(id)?.accountCapabilities?.get(Capability.CALENDARS)?.let(CalendarsCapability::from)

    /** Whether this server exposes calendars at all. */
    val supportsCalendars: Boolean
        get() = capabilities.containsKey(Capability.CALENDARS)
}

@Serializable
data class Account(
    val name: String = "",
    val isPersonal: Boolean = true,
    val isReadOnly: Boolean = false,
    val accountCapabilities: Map<String, JsonObject> = emptyMap(),
)

/**
 * The `urn:ietf:params:jmap:core` limits, with the spec's defaults where the server omits one.
 *
 * Read from the session rather than hardcoded so an instance configured for larger uploads is not
 * second-guessed by its own client.
 */
data class CoreCapability(
    val maxSizeUpload: Long = 50_000_000,
    val maxConcurrentUpload: Int = 4,
    val maxSizeRequestObject: Long = 10_000_000,
    val maxConcurrentRequests: Int = 4,
    val maxCallsInRequest: Int = 16,
    val maxObjectsInGet: Int = 500,
    val maxObjectsInSet: Int = 500,
) {
    companion object {
        fun from(json: JsonObject): CoreCapability {
            val defaults = CoreCapability()

            fun long(key: String, fallback: Long) =
                (json[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: fallback

            fun int(key: String, fallback: Int) =
                (json[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: fallback

            return CoreCapability(
                maxSizeUpload = long("maxSizeUpload", defaults.maxSizeUpload),
                maxConcurrentUpload = int("maxConcurrentUpload", defaults.maxConcurrentUpload),
                maxSizeRequestObject = long("maxSizeRequestObject", defaults.maxSizeRequestObject),
                maxConcurrentRequests =
                    int("maxConcurrentRequests", defaults.maxConcurrentRequests),
                maxCallsInRequest = int("maxCallsInRequest", defaults.maxCallsInRequest),
                maxObjectsInGet = int("maxObjectsInGet", defaults.maxObjectsInGet),
                maxObjectsInSet = int("maxObjectsInSet", defaults.maxObjectsInSet),
            )
        }
    }
}

/**
 * The per-account `urn:plmail:params:jmap:calendars` limits.
 *
 * [maxEventsInGet] is **not** core's `maxObjectsInGet`, and the gap is the point: the server allows
 * 500 objects in a general get and 100 events, because expanding a recurring series is far more
 * work than reading a row. A client that chunked its hydration by the core limit would have every
 * calendar request refused.
 */
data class CalendarsCapability(
    val maxEventsInGet: Int = 100,
    val maxEventsInSet: Int = 500,
    /** False on every account today — the server owns which calendars exist. */
    val mayCreateCalendar: Boolean = false,
    val materialisedHorizon: MaterialisedHorizon = MaterialisedHorizon(),
) {
    companion object {
        fun from(json: JsonObject): CalendarsCapability {
            val defaults = CalendarsCapability()

            fun int(key: String, fallback: Int) =
                (json[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: fallback

            val horizon = json["materialisedHorizon"] as? JsonObject

            return CalendarsCapability(
                maxEventsInGet = int("maxEventsInGet", defaults.maxEventsInGet),
                maxEventsInSet = int("maxEventsInSet", defaults.maxEventsInSet),
                mayCreateCalendar =
                    (json["mayCreateCalendar"] as? JsonPrimitive)?.content?.toBoolean()
                        ?: defaults.mayCreateCalendar,
                materialisedHorizon =
                    MaterialisedHorizon(
                        past =
                            (horizon?.get("past") as? JsonPrimitive)?.content
                                ?: defaults.materialisedHorizon.past,
                        future =
                            (horizon?.get("future") as? JsonPrimitive)?.content
                                ?: defaults.materialisedHorizon.future,
                    ),
            )
        }
    }
}

/**
 * How far either side of today the server has actually expanded recurring events.
 *
 * Both values are **opaque**: they are PHP relative-date expressions (`-1 year`, `+2 years`), not
 * ISO 8601 durations, and nothing in the client may parse them. They exist so a query outside the
 * window can be explained to the user — outside it the server answers from a partial index and
 * quietly returns less than it holds, which is indistinguishable from an empty month unless the
 * client says so.
 */
data class MaterialisedHorizon(val past: String = "", val future: String = "")

private fun JsonPrimitive.contentOrNullIfBlank(): String? = content.takeIf { it.isNotBlank() }
