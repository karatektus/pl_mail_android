package de.plmail.core.data

import de.plmail.jmap.protocol.FcmConfig
import de.plmail.jmap.protocol.PushCapability

/**
 * The three ways this app can be told about new mail.
 *
 * A closed set on purpose: it is what the settings screen offers, and every one of them is a
 * different *arrangement*, not a different setting. Web Push needs an app the user installs;
 * Firebase needs a server the administrator configured and Google in the path; pull needs nothing
 * and costs latency. There is no fourth answer that is a blend of them.
 */
enum class PushChoice(val wire: String) {
    /** UnifiedPush: a distributor app holds the connection and decrypts RFC 8291 payloads. */
    WEB_PUSH("webpush"),

    /** Firebase data messages, google flavour only, and only where the server has a project. */
    FCM("fcm"),

    /**
     * No subscription at all.
     *
     * Not "push off and hope": the delta sync on foreground and the fifteen-minute worker are
     * exactly what every other choice already falls back to, so this is the app running on the
     * floor it never leaves rather than a degraded mode. It is also the only honest option on a
     * de-Googled phone with no distributor installed, and the only one that tells nobody — not
     * Google, not a distributor's server — when mail arrives.
     */
    PULL("pull");

    companion object {
        fun of(wire: String?): PushChoice? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Why an option is not on offer.
 *
 * Every value here is a different thing for the user to *do*, which is the entire reason this is
 * not a boolean. "Your server does not support Firebase" and "your administrator has not switched
 * Firebase on" send someone to two different places, and one of those places does not exist on the
 * server they are running.
 */
enum class PushUnavailable {
    /**
     * The server answers nothing about FCM at all, so it predates the feature.
     *
     * The fix is to upgrade the server. Distinguished from [SERVER_DISABLED] because the session
     * publishes `fcm` even when false precisely so that these two can be told apart, and telling
     * someone to configure a page their build does not have is a wasted evening.
     */
    SERVER_TOO_OLD,

    /** The server knows about FCM and it is switched off. The fix is on its admin page. */
    SERVER_DISABLED,

    /** FCM is on but the published configuration is incomplete, so Firebase cannot be built. */
    SERVER_CONFIG_INCOMPLETE,

    /** This build contains no Firebase code. The fix is the other build, and that is deliberate. */
    NOT_IN_THIS_BUILD,

    /** No usable Play services on this device — a de-Googled ROM, a Huawei, an emulator image. */
    NO_PLAY_SERVICES,

    /** Firebase was there and would not start against this server's configuration. */
    INIT_FAILED,

    /** The server publishes no VAPID key, so Web Push is unconfigured on it. */
    NO_VAPID,

    /** No UnifiedPush distributor is installed, so there is nothing to hold the connection. */
    NO_DISTRIBUTOR,
}

/** One row of the picker: what it is, whether it can be picked, and why not. */
data class PushOption(
    val choice: PushChoice,
    val isAvailable: Boolean,
    /** Null when available. Never null when not — an option greyed out with no reason is a bug. */
    val reason: PushUnavailable? = null,
    /**
     * The transport's own words about a failure, where it had any. Not translated; it is grepped.
     */
    val detail: String? = null,
)

/**
 * Whether Firebase can be used on this device, at all, right now.
 *
 * Several failures with several different answers, so a sealed pair rather than a nullable token.
 *
 * [Ready] deliberately carries **no token**. Firebase's current API has no synchronous one: asking
 * to register returns immediately and the token arrives later, in the messaging service's own
 * callback — the same shape UnifiedPush has, where the distributor calls back with an endpoint. So
 * both transports produce an address asynchronously and both are recorded by the same method, which
 * is a symmetry worth having rather than an inconvenience to hide behind a blocking wrapper.
 */
sealed interface FcmAvailability {
    /** Firebase started against this server's project and registration has been asked for. */
    data object Ready : FcmAvailability

    data class Unavailable(val reason: PushUnavailable, val detail: String? = null) :
        FcmAvailability
}

/**
 * What the `google` flavour can do about Firebase, as the rest of the app is allowed to see it.
 *
 * Implemented per flavour in `:app`, and the seam runs this way round for the same reason
 * [PushTransport] and `MailDestinations` do: only `:app` knows which build this is, and the `foss`
 * flavour must contain **no Firebase artifact at all** — not a disabled one. A single build that
 * shipped `firebase-messaging` and decided at runtime would still link Google's code and would
 * still be ineligible for F-Droid, so the promise has to be kept by the bytecode rather than by a
 * flag.
 *
 * Everything here therefore has a `foss` implementation that answers honestly and imports nothing.
 */
interface FcmSupport {

    /**
     * Whether this build contains Firebase.
     *
     * A property rather than an inference from [prepare] failing, because the settings screen has
     * to distinguish "not in this build" — which is permanent, expected, and the point of the foss
     * flavour — from "the device could not start it", which is a problem.
     */
    val isCompiledIn: Boolean

    /**
     * Whether this device *could* run Firebase, without starting anything.
     *
     * Separate from [prepare] and the separation is load-bearing. Drawing the settings screen has
     * to be able to say whether the option is real, and if that question started Firebase then
     * merely *opening settings* would mint a registration token — an identifier Google holds and
     * can route to — for a user who came to choose "pull only". On a product whose audience
     * self-hosts their mail specifically to avoid that, asking the question must cost nothing.
     *
     * Null means nothing stands in the way on this device's side.
     */
    fun probe(): PushUnavailable?

    /**
     * Starts Firebase against [config] and asks it to register this device.
     *
     * Called when the user picks FCM, and again whenever the session's configuration changes,
     * because a Firebase app initialised against a different project delivers nothing and says
     * nothing about it.
     *
     * The token that results does **not** come back from here — it arrives in the messaging
     * service's registration callback and is handed to `PushTransportManager.tokenRotated`.
     */
    suspend fun prepare(config: FcmConfig): FcmAvailability

    /**
     * Gives up this device's token and stops Firebase.
     *
     * Called when the user leaves FCM or signs out. Deleting the token matters: a token the server
     * still holds and Google still routes is one that wakes a phone for a mailbox it is no longer
     * signed into.
     */
    suspend fun release()
}

/** The `foss` answer, and the answer for any device that cannot run Firebase. */
object NoFcmSupport : FcmSupport {
    override val isCompiledIn = false

    override fun probe(): PushUnavailable = PushUnavailable.NOT_IN_THIS_BUILD

    override suspend fun prepare(config: FcmConfig): FcmAvailability =
        FcmAvailability.Unavailable(PushUnavailable.NOT_IN_THIS_BUILD)

    override suspend fun release() = Unit
}

/**
 * What the server says it can deliver over, reduced to the two questions the picker asks.
 *
 * Built from [PushCapability] rather than passed around as one, because the interesting distinction
 * — a server that says `"fcm": false` versus one that says nothing — is a property of the *absence*
 * of a key and evaporates the moment anyone writes `capability?.fcm == true`.
 */
data class ServerPushSupport(
    val webPush: Boolean = false,
    val fcm: Boolean = false,
    val fcmConfig: FcmConfig? = null,
    /** False on an instance predating FCM, which is a different sentence from `fcm: false`. */
    val knowsFcm: Boolean = false,
) {
    /** Why FCM is not on offer from the server's side, or null when it is. */
    val fcmObjection: PushUnavailable?
        get() =
            when {
                !knowsFcm -> PushUnavailable.SERVER_TOO_OLD
                !fcm -> PushUnavailable.SERVER_DISABLED
                fcmConfig?.isComplete != true -> PushUnavailable.SERVER_CONFIG_INCOMPLETE
                else -> null
            }

    companion object {
        /** An unreachable or unread session supports nothing, which is what the picker draws. */
        val UNKNOWN = ServerPushSupport()

        fun from(capability: PushCapability?): ServerPushSupport =
            capability?.let {
                ServerPushSupport(
                    webPush = it.webPush,
                    fcm = it.fcm,
                    // Read only when `fcm` says so. The key is absent rather
                    // than null when Firebase is off, and reaching for it first
                    // is exactly the mistake that absence is shaped to prevent.
                    fcmConfig = if (it.fcm) it.fcmConfig else null,
                    knowsFcm = it.knowsFcm,
                )
            } ?: UNKNOWN
    }
}
