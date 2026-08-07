package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What the app knows about its own push registration.
 *
 * All of it, and every field earns its place by being a question somebody has to answer when push
 * "does not work" — which for this audience means *they* have to answer it, on their own server, at
 * whatever hour they noticed.
 *
 * The subscription id is the load-bearing one. Registering returns it and the app used to throw it
 * away, which meant the registration could never afterwards be checked or revoked.
 *
 * **[verifiedAt] is here rather than asked of the server, and that is a correction.** The app used
 * to answer "is this subscription verified?" by reading `verificationCode` back off a
 * `PushSubscription/get` and calling a null one verified — but the server never returns that
 * property to anybody, by design, because echoing it would hand the handshake to whoever could read
 * one response. So the check was structurally incapable of returning false and the diagnostic it
 * powered was decorative. The only device that knows the handshake completed is this one, at the
 * moment it echoed the code back, so that is where the fact is recorded.
 *
 * Not encrypted, and nothing here is a secret: the endpoint URL is a capability, but the
 * distributor has it, the server has it, and it is already in this app's own network traffic. The
 * FCM token is the same kind of thing and is kept for one reason — [PushTransport] rotation has to
 * be able to tell a *new* token from the one already registered, and Firebase will hand out the
 * same one repeatedly. What none of it is is reconstructible from the server, which is why it lives
 * here rather than in Room — the database's destructive-migration policy would throw it away, and a
 * device whose subscription id is gone cannot check or revoke its own registration.
 */
@Singleton
class PushStateStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    val state: Flow<PushState> =
        preferences.data.map { stored ->
            PushState(
                subscriptionId = stored[SUBSCRIPTION_ID],
                endpoint = stored[ENDPOINT],
                transport = stored[TRANSPORT],
                choice = stored[CHOICE],
                fcmToken = stored[FCM_TOKEN],
                registeredAt = stored[REGISTERED_AT],
                verifiedAt = stored[VERIFIED_AT],
                lastMessageAt = stored[LAST_MESSAGE_AT],
                lastMessageTransport = stored[LAST_MESSAGE_TRANSPORT],
                lastError = stored[LAST_ERROR],
                hasSweptLegacySubscriptions = stored[LEGACY_SWEPT] == true,
            )
        }

    /**
     * Records what the user picked, before anything has been done about it.
     *
     * Separate from [registered] on purpose: the choice is durable and the registration is an
     * attempt to honour it. A device that chose FCM and has not finished the handshake is in a
     * state the settings screen has to be able to describe, and collapsing the two would make it
     * indistinguishable from a device that chose nothing.
     */
    suspend fun chose(choice: String) {
        preferences.edit { it[CHOICE] = choice }
    }

    /**
     * Records a successful registration, clearing whatever went wrong before it.
     *
     * **[verifiedAt] is cleared here and only set by [verified].** A create arms a fresh handshake
     * — including a rotation, which the server treats as one — so carrying the previous
     * verification forward would report a subscription as delivering during the window in which it
     * provably is not.
     */
    suspend fun registered(
        subscriptionId: String,
        transport: String,
        endpoint: String?,
        fcmToken: String?,
        at: Long,
    ) {
        preferences.edit { store ->
            store[SUBSCRIPTION_ID] = subscriptionId
            store[TRANSPORT] = transport
            store[REGISTERED_AT] = at

            if (endpoint == null) store.remove(ENDPOINT) else store[ENDPOINT] = endpoint
            if (fcmToken == null) store.remove(FCM_TOKEN) else store[FCM_TOKEN] = fcmToken

            store.remove(VERIFIED_AT)
            store.remove(LAST_ERROR)
        }
    }

    /**
     * The handshake completed: the code arrived on this device and was echoed back.
     *
     * The single most consequential line in this store. Before it, the subscription exists and
     * receives nothing; after it, the subscription is live. Nothing else on the device can tell
     * those apart.
     */
    suspend fun verified(at: Long) {
        preferences.edit { store ->
            store[VERIFIED_AT] = at
            store.remove(LAST_ERROR)
        }
    }

    /**
     * A push arrived, and which way in.
     *
     * Not what it said — the received-push log records that — only that one did, and over what.
     * This is the only evidence on the screen that the whole chain works: server, subscription,
     * transport, device. Everything else is something the app *believes*.
     */
    suspend fun received(at: Long, transport: String) {
        preferences.edit { store ->
            store[LAST_MESSAGE_AT] = at
            store[LAST_MESSAGE_TRANSPORT] = transport
        }
    }

    /** A registration that failed, in the transport's own words. */
    suspend fun failed(reason: String) {
        preferences.edit { it[LAST_ERROR] = reason }
    }

    /**
     * The one-time sweep of the legacy `plmail` subscription has been made against this server.
     *
     * Recorded so the sweep costs one extra `PushSubscription/get` in the life of an install rather
     * than one per registration — and **only ever set after the sweep succeeded**, because the
     * alternative is a device that asked once, was refused, and never asks again. See
     * `PushRepository` for what is being swept and why destroying it cannot lose anything.
     */
    suspend fun sweptLegacySubscriptions() {
        preferences.edit { it[LEGACY_SWEPT] = true }
    }

    /**
     * Forgets the registration, for when the transport drops us or the account is signed out.
     *
     * The user's [chose] preference deliberately survives: signing back into the same server should
     * not silently move somebody off the transport they picked. So does
     * [PushState.hasSweptLegacySubscriptions], because the row it describes is *the server's* and a
     * device dropping its own registration has not put the legacy one back.
     */
    suspend fun cleared() {
        preferences.edit { store ->
            store.remove(SUBSCRIPTION_ID)
            store.remove(ENDPOINT)
            store.remove(TRANSPORT)
            store.remove(FCM_TOKEN)
            store.remove(REGISTERED_AT)
            store.remove(VERIFIED_AT)
        }
    }

    /** Forgets the choice as well. Sign-out, where the next server may not offer the same ones. */
    suspend fun forgotten() {
        cleared()
        preferences.edit { store ->
            store.remove(CHOICE)
            store.remove(LAST_ERROR)
            // The sweep is a fact about one server's subscription table, and the
            // next sign-in may be a different server holding its own legacy row.
            store.remove(LEGACY_SWEPT)
        }
    }

    private companion object {
        val SUBSCRIPTION_ID = stringPreferencesKey("push_subscription_id")
        val ENDPOINT = stringPreferencesKey("push_endpoint")
        val TRANSPORT = stringPreferencesKey("push_transport")
        val CHOICE = stringPreferencesKey("push_choice")
        val FCM_TOKEN = stringPreferencesKey("push_fcm_token")
        val REGISTERED_AT = longPreferencesKey("push_registered_at")
        val VERIFIED_AT = longPreferencesKey("push_verified_at")
        val LAST_MESSAGE_AT = longPreferencesKey("push_last_message_at")
        val LAST_MESSAGE_TRANSPORT = stringPreferencesKey("push_last_message_transport")
        val LAST_ERROR = stringPreferencesKey("push_last_error")
        val LEGACY_SWEPT = booleanPreferencesKey("push_legacy_subscription_swept")
    }
}

/** Everything the app knows about its push registration, as one value. */
data class PushState(
    val subscriptionId: String? = null,
    val endpoint: String? = null,
    /** The transport the *registered* subscription uses, as the wire names it. */
    val transport: String? = null,
    /** The transport the user asked for, which may not have been reached yet. */
    val choice: String? = null,
    val fcmToken: String? = null,
    val registeredAt: Long? = null,
    val verifiedAt: Long? = null,
    val lastMessageAt: Long? = null,
    val lastMessageTransport: String? = null,
    val lastError: String? = null,
    /**
     * Whether the one-time cleanup of the pre-hash `plmail` subscription has already been made.
     *
     * Not a diagnostic — nothing draws it — but stored beside the rest because it is part of what
     * the app knows about its registration, and because the alternative to remembering is a
     * `PushSubscription/get` before every single create.
     */
    val hasSweptLegacySubscriptions: Boolean = false,
) {
    /**
     * Registered as far as this device can tell. Whether the *server* agrees needs a round trip.
     */
    val isRegistered: Boolean
        get() = subscriptionId != null

    /** Registered *and* past the handshake, which is the only state that receives anything. */
    val isLive: Boolean
        get() = isRegistered && verifiedAt != null

    /**
     * Registered and waiting for the code — a real, temporary, silent state.
     *
     * Worth naming because it is where a transport switch legitimately sits for a few seconds and
     * where a broken one sits forever, and the two look identical unless the screen says which.
     */
    val isAwaitingVerification: Boolean
        get() = isRegistered && verifiedAt == null
}
