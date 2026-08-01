package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
 * away, which meant `PushRepository.isLive` — written precisely to detect the one failure that
 * silently delivers nothing forever, an unverified subscription — could never be called, because
 * there was no id to call it with. The diagnostic existed and was unreachable.
 *
 * Not encrypted, and nothing here is a secret: the endpoint URL is a capability, but the
 * distributor has it, the server has it, and it is already in this app's own network traffic. What
 * it is *not* is reconstructible from the server, which is why it lives here rather than in Room —
 * the database's destructive-migration policy would throw it away, and a device whose subscription
 * id is gone cannot check or revoke its own registration.
 */
@Singleton
class PushStateStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    val state: Flow<PushState> =
        preferences.data.map { stored ->
            PushState(
                subscriptionId = stored[SUBSCRIPTION_ID],
                endpoint = stored[ENDPOINT],
                registeredAt = stored[REGISTERED_AT],
                lastMessageAt = stored[LAST_MESSAGE_AT],
                lastError = stored[LAST_ERROR],
            )
        }

    /** Records a successful registration, clearing whatever went wrong before it. */
    suspend fun registered(subscriptionId: String, endpoint: String, at: Long) {
        preferences.edit { store ->
            store[SUBSCRIPTION_ID] = subscriptionId
            store[ENDPOINT] = endpoint
            store[REGISTERED_AT] = at
            store.remove(LAST_ERROR)
        }
    }

    /**
     * A push arrived. Not what it said — that is nobody's business here — only that one did.
     *
     * This is the single most useful line on the diagnostics screen, because it is the only
     * evidence that the whole chain works: server, subscription, distributor, device. Everything
     * else on that screen is something the app *believes*; this is something that happened.
     */
    suspend fun received(at: Long) {
        preferences.edit { it[LAST_MESSAGE_AT] = at }
    }

    /** A registration that failed, in the distributor's own words. */
    suspend fun failed(reason: String) {
        preferences.edit { it[LAST_ERROR] = reason }
    }

    /** Forgets the registration, for when the distributor drops us or the account is signed out. */
    suspend fun cleared() {
        preferences.edit { store ->
            store.remove(SUBSCRIPTION_ID)
            store.remove(ENDPOINT)
            store.remove(REGISTERED_AT)
        }
    }

    private companion object {
        val SUBSCRIPTION_ID = stringPreferencesKey("push_subscription_id")
        val ENDPOINT = stringPreferencesKey("push_endpoint")
        val REGISTERED_AT = longPreferencesKey("push_registered_at")
        val LAST_MESSAGE_AT = longPreferencesKey("push_last_message_at")
        val LAST_ERROR = stringPreferencesKey("push_last_error")
    }
}

/** Everything the app knows about its push registration, as one value. */
data class PushState(
    val subscriptionId: String? = null,
    val endpoint: String? = null,
    val registeredAt: Long? = null,
    val lastMessageAt: Long? = null,
    val lastError: String? = null,
) {
    /**
     * Registered as far as this device can tell. Whether the *server* agrees needs a round trip.
     */
    val isRegistered: Boolean
        get() = subscriptionId != null
}
