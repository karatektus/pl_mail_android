package de.plmail.jmap.protocol

import java.time.Duration
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
     * Which push transports this instance can actually deliver over.
     *
     * Null means the instance publishes no push capability at all, which is a *third* state and not
     * the same as one that publishes it with nothing configured — see [PushCapability].
     */
    val push: PushCapability?
        get() = capabilities[Capability.PUSH]?.let(PushCapability::from)

    /**
     * The VAPID key needed before a Web Push subscription can be created.
     *
     * Null *or blank* means Web Push is unconfigured on this instance — the server publishes the
     * capability either way, so presence of the key is the signal, not presence of the capability.
     * Don't offer push when it is absent.
     */
    val vapidPublicKey: String?
        get() = push?.vapidPublicKey

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

    /**
     * What this account will accept on an `EmailSubmission/set`, scheduling included.
     *
     * **Per account, not per session.** RFC 8621 §7 puts `maxDelayedSend` in `accountCapabilities`,
     * and plMail follows it — so a client that read the session-level object would find an empty
     * `{}` and conclude that no server anywhere can schedule. The defaults here are the spec's,
     * which is "cannot": an instance that says nothing about delayed send is one that does not do
     * it.
     */
    fun submission(id: AccountId): SubmissionCapability =
        account(id)
            ?.accountCapabilities
            ?.get(Capability.SUBMISSION)
            ?.let(SubmissionCapability::from) ?: SubmissionCapability()

    /**
     * The account to ask for contact suggestions.
     *
     * Unlike calendars this is a convenience rather than the whole surface — **every** account
     * answers `Contact/autocomplete` from the same user-wide address book, because a suggestion has
     * no id and therefore none of the (accountId, id) collisions that made calendars
     * single-account. So this is somewhere to ask, and any account would do.
     */
    val primaryContactsAccount: AccountId?
        get() = primaryAccounts[Capability.CONTACTS]?.let(::AccountId) ?: primaryMailAccount

    /** The advertised autocomplete limits, or null on an instance without the extension. */
    val contacts: ContactsCapability?
        get() = capabilities[Capability.CONTACTS]?.let(ContactsCapability::from)

    /**
     * What the server already knows about how this user wants the app to look.
     *
     * Null means the instance has no appearance extension, which is a supported instance rather
     * than a broken one: the app keeps its own local choice and never calls `Appearance/get`.
     */
    val appearance: AppearanceCapability?
        get() = capabilities[Capability.APPEARANCE]?.let(AppearanceCapability::from)

    /**
     * How much of one account this server has fetched so far.
     *
     * Null when the account publishes no sync capability. Absence is the signal, as with calendars
     * — "this server does not say" is not the same sentence as "this server has everything", which
     * is a completed backfill.
     */
    fun syncWindow(id: AccountId): SyncWindow? =
        account(id)?.accountCapabilities?.get(Capability.SYNC)?.let(SyncWindow::from)
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
 * The per-account `urn:ietf:params:jmap:submission` limits.
 *
 * [maxDelayedSend] is the **only** thing that decides whether this app offers "send later", and it
 * is read here rather than compared against a constant: the ceiling is the server's to pick —
 * plMail holds a messenger envelope rather than handing HOLDFOR to a relay, so the number is a
 * retention decision that instance made and not a property of the protocol. Zero means the feature
 * is hidden, which is also what the spec's default says for a server that publishes nothing.
 *
 * [extensions] is `submissionExtensions`, the SMTP extensions the server will honour through the
 * envelope, keyed by extension name. `FUTURERELEASE` carrying `HOLDFOR`/`HOLDUNTIL` (RFC 4865) is
 * the one this client uses. Checked as well as [maxDelayedSend] because the two answer different
 * questions — how long a hold may be, and whether a hold can be asked for at all.
 */
data class SubmissionCapability(
    val maxDelayedSend: Long = 0,
    val extensions: Map<String, List<String>> = emptyMap(),
) {
    /** Whether an absolute release time may be asked for. */
    val supportsHoldUntil: Boolean
        get() = HOLD_UNTIL in futureRelease

    /** Whether a relative hold may be asked for. */
    val supportsHoldFor: Boolean
        get() = HOLD_FOR in futureRelease

    /**
     * Whether "send later" exists on this account at all.
     *
     * Both halves, because either one alone is a promise the server has not made: a ceiling with no
     * `FUTURERELEASE` is a server that would refuse the parameter, and the extension with a ceiling
     * of zero is a server that would refuse every value of it.
     */
    val supportsScheduledSend: Boolean
        get() = maxDelayedSend > 0 && (supportsHoldUntil || supportsHoldFor)

    /** The longest hold this account accepts, as a duration rather than a number. */
    val longestHold: Duration
        get() = Duration.ofSeconds(maxDelayedSend)

    private val futureRelease: Set<String>
        get() =
            extensions[FUTURE_RELEASE].orEmpty().mapTo(mutableSetOf()) { it.uppercase(Locale.ROOT) }

    companion object {
        private const val FUTURE_RELEASE = "FUTURERELEASE"
        private const val HOLD_FOR = "HOLDFOR"
        private const val HOLD_UNTIL = "HOLDUNTIL"

        fun from(json: JsonObject): SubmissionCapability {
            val extensions =
                (json["submissionExtensions"] as? JsonObject).orEmpty().mapValues { (_, value) ->
                    (value as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                }

            return SubmissionCapability(
                maxDelayedSend =
                    (json["maxDelayedSend"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
                extensions = extensions,
            )
        }

        private fun JsonObject?.orEmpty(): Map<String, kotlinx.serialization.json.JsonElement> =
            this ?: emptyMap()

        private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
            this ?: emptyList()
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

/**
 * The `urn:plmail:params:jmap:contacts` limits.
 *
 * Advertised so a type-ahead does not have to discover the cap by having a request silently
 * shortened. The server caps rather than refuses — it echoes the limit it used — so a client that
 * ignored these would still work and would quietly ask for a sequential scan per keystroke.
 */
data class ContactsCapability(val defaultSuggestions: Int = 8, val maxSuggestions: Int = 50) {
    companion object {
        fun from(json: JsonObject): ContactsCapability {
            val defaults = ContactsCapability()

            fun int(key: String, fallback: Int) =
                (json[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: fallback

            return ContactsCapability(
                defaultSuggestions = int("defaultSuggestions", defaults.defaultSuggestions),
                maxSuggestions = int("maxSuggestions", defaults.maxSuggestions),
            )
        }
    }
}

/**
 * The appearance capability: a compact read of the current settings, plus the vocabularies.
 *
 * The four values in [hint] are the *whole* point of it being in the session. `Appearance/get` is
 * the authoritative read and needs a round trip; these arrive with discovery, which the app already
 * does before it draws anything, so the first frame can be painted in the theme the user actually
 * chose rather than in the default followed by a flash.
 *
 * [themes] is read rather than assumed because it is how a client discovers a theme it does not
 * have — `paper` is one today — without having to be told.
 */
data class AppearanceCapability(
    val hint: AppearanceHint = AppearanceHint(),
    val themes: List<String> = emptyList(),
    val layouts: List<String> = emptyList(),
    val densities: List<String> = emptyList(),
    val ranges: Map<String, ClosedRange> = emptyMap(),
) {
    /** The bounds for one numeric knob, or null when this server does not publish it. */
    fun range(property: String): ClosedRange? = ranges[property]

    companion object {
        fun from(json: JsonObject): AppearanceCapability {
            fun strings(key: String) =
                (json[key] as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonPrimitive)?.content
                }

            val compact = json["appearance"] as? JsonObject

            fun hint(key: String) = (compact?.get(key) as? JsonPrimitive)?.content

            return AppearanceCapability(
                hint =
                    AppearanceHint(
                        theme = hint("theme"),
                        layout = hint("layout"),
                        accent = hint("accent"),
                        density = hint("density"),
                    ),
                themes = strings("themes"),
                layouts = strings("layouts"),
                densities = strings("densities"),
                ranges =
                    (json["ranges"] as? JsonObject)
                        .orEmpty()
                        .mapNotNull { (name, bounds) ->
                            val range = bounds as? JsonObject ?: return@mapNotNull null
                            val min = (range["min"] as? JsonPrimitive)?.content?.toFloatOrNull()
                            val max = (range["max"] as? JsonPrimitive)?.content?.toFloatOrNull()

                            if (min == null || max == null) null else name to ClosedRange(min, max)
                        }
                        .toMap(),
            )
        }
    }
}

/**
 * The session's four-field appearance summary.
 *
 * Deliberately loose strings: this is the same vocabulary `Appearance/get` answers in, and the one
 * resolver in `:core:designsystem` is what turns either of them into types. `accent` has no Android
 * counterpart today — each theme here carries its own accent, tuned to pass AA on that theme's
 * surfaces — so it is carried and not used rather than dropped, because a client that never parsed
 * it could not later notice it had arrived.
 */
data class AppearanceHint(
    val theme: String? = null,
    val layout: String? = null,
    val accent: String? = null,
    val density: String? = null,
)

/** A numeric knob's published bounds. */
data class ClosedRange(val min: Float, val max: Float) {
    fun clamp(value: Float): Float = value.coerceIn(min, max)
}

/**
 * How much of one account the server has actually got hold of, per `urn:plmail:params:jmap:sync`.
 *
 * Both fields answer the same user question — "why can I not find a mail I know exists?" — and
 * neither is about this device. The client's own cached count answers a different question and both
 * are worth showing.
 *
 * The server keeps every message an account has; there is no retention setting to report, so an
 * unfinished backfill is the only gap it can honestly name. Older servers still publish a
 * `syncLimit` here from when there was one — [from] does not read it, which is the whole of the
 * compatibility story: an unknown key in a capability object is ignored, never an error.
 *
 * [backfillTarget] is a message *count*, not a date — how far back a completed backfill reached, 0
 * meaning the whole mailbox. Null means none has ever finished, which is why it cannot be collapsed
 * into 0. A positive number is an account the retired cap stopped short, and reads as unfinished.
 *
 * [backfillPending] means "there is mail still coming", not "a worker is running this second".
 * Nothing records the latter, and a client that worded it as progress would be inventing a
 * guarantee.
 */
data class SyncWindow(val backfillTarget: Int? = null, val backfillPending: Boolean = false) {
    companion object {
        fun from(json: JsonObject): SyncWindow =
            SyncWindow(
                backfillTarget = (json["backfillTarget"] as? JsonPrimitive)?.content?.toIntOrNull(),
                backfillPending =
                    (json["backfillPending"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                        ?: false,
            )
    }
}

/**
 * The `urn:plmail:params:jmap:push` vendor extension: which transports this instance can deliver
 * over.
 *
 * RFC 8620 defines no standard place for any of this, so a strict parser sees none of it.
 *
 * **The three fields take three different rules for absence, and each one is deliberate.**
 * - [vapidPublicKey] is blank when Web Push is unconfigured. Blank rather than missing, so it is
 *   always safe to read.
 * - [knowsFcm] distinguishes an instance that says `"fcm": false` from one that predates FCM and
 *   says nothing. Both mean "do not offer Firebase" and the *reason* differs: the first is an
 *   administrator who has not pasted a Firebase project in yet, the second is a server that has to
 *   be upgraded first. Telling a self-hoster to upgrade a server that is already current, or to
 *   configure a page that does not exist, are equally useless instructions.
 * - [fcmConfig] is absent — not null — when FCM is off, because a null object invites a caller to
 *   read `.projectId` off it and get null. Check [fcm] first; this is a value that either exists or
 *   does not.
 */
data class PushCapability(
    val vapidPublicKey: String? = null,
    /** Whether Firebase is configured *and* switched on. */
    val fcm: Boolean = false,
    /** Whether the server answered the FCM question at all. False on an instance predating FCM. */
    val knowsFcm: Boolean = false,
    val fcmConfig: FcmConfig? = null,
) {
    /** Whether a Web Push (or UnifiedPush) subscription may be created against this instance. */
    val webPush: Boolean
        get() = vapidPublicKey != null

    companion object {
        fun from(json: JsonObject): PushCapability {
            val fcm = json["fcm"] as? JsonPrimitive

            return PushCapability(
                vapidPublicKey = (json["vapidPublicKey"] as? JsonPrimitive)?.contentOrNullIfBlank(),
                fcm = fcm?.content?.toBooleanStrictOrNull() ?: false,
                knowsFcm = fcm != null,
                fcmConfig = (json["fcmConfig"] as? JsonObject)?.let(FcmConfig::from),
            )
        }
    }
}

/**
 * The four public values Android's `FirebaseOptions.Builder` needs, published by the server.
 *
 * The normal Android arrangement — a `google-services.json` processed at build time — cannot work
 * for plMail: one APK serves every installation and every installation has its own Firebase
 * project. So the server publishes these and the client builds `FirebaseOptions` at runtime,
 * against whatever instance this install is signed into.
 *
 * All four ship inside every Firebase app's APK and are public by nature. The service-account key
 * that can actually *send* never leaves the server.
 */
data class FcmConfig(
    val projectId: String,
    val applicationId: String,
    val apiKey: String,
    val senderId: String,
) {
    /** Whether all four values are present. A partial config cannot initialise Firebase. */
    val isComplete: Boolean
        get() =
            projectId.isNotBlank() &&
                applicationId.isNotBlank() &&
                apiKey.isNotBlank() &&
                senderId.isNotBlank()

    companion object {
        fun from(json: JsonObject): FcmConfig {
            fun value(key: String) = (json[key] as? JsonPrimitive)?.content.orEmpty()

            return FcmConfig(
                projectId = value("projectId"),
                applicationId = value("applicationId"),
                apiKey = value("apiKey"),
                senderId = value("senderId"),
            )
        }
    }
}

private fun JsonPrimitive.contentOrNullIfBlank(): String? = content.takeIf { it.isNotBlank() }
