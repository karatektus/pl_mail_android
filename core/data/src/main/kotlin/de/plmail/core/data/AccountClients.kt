package de.plmail.core.data

import de.plmail.core.datastore.CredentialStore
import de.plmail.jmap.client.JmapClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one [JmapClient] for the stored connection.
 *
 * Shared rather than constructed per call, and that is not a micro-optimisation: `JmapClient`
 * caches and single-flights the Session, so ten callers at launch cause one discovery request. A
 * fresh client per repository throws that away and asks the server ten times — against a Raspberry
 * Pi that advertises four concurrent requests.
 *
 * Keyed on the connection rather than held forever, so re-pairing against a different server or
 * accepting a new certificate produces a new client instead of one still talking to the old address
 * with the old trust.
 */
@Singleton
class AccountClients
@Inject
constructor(
    private val credentials: CredentialStore,
    private val transports: TransportFactory,
) {

    private val mutex = Mutex()
    private var cached: Cached? = null

    /**
     * The client for the current connection, or null when there is none.
     *
     * Null rather than a throw: "not paired yet" is a state the app spends its whole first launch
     * in, and it is onboarding's job to fix rather than a caller's to handle as an error.
     */
    suspend fun current(): JmapClient? {
        val connection = credentials.connection.first() ?: return null

        return mutex.withLock {
            val key = Key(connection.address.discoveryUrl, connection.pinnedKey?.hex)

            cached?.takeIf { it.key == key }?.client
                ?: JmapClient(
                        discoveryUrl = connection.address.discoveryUrl,
                        credential = connection.credential,
                        transport = transports.create(connection.address, connection.pinnedKey),
                    )
                    .also { cached = Cached(key, it) }
        }
    }

    /**
     * The client that reaches [accountKey].
     *
     * One credential enumerates every account behind it, so today this is [current] for any account
     * on the connected server. It takes the key anyway because a second *server* is a real future
     * feature, and the call sites that will need to distinguish are the ones being written now.
     */
    suspend fun forAccount(accountKey: String): JmapClient? =
        current()?.takeIf { accountKey.isNotBlank() }

    private data class Key(val discoveryUrl: String, val pin: String?)

    private class Cached(val key: Key, val client: JmapClient)
}
