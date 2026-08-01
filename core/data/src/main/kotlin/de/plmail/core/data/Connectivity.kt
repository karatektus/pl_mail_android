package de.plmail.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the device has a network at all.
 *
 * Deliberately *not* "whether the server is reachable", which is a different question and one only
 * a request can answer. The two produce different sentences and the difference is the whole point:
 * "you are offline" is something the user can fix from the quick settings, and "plMail can't reach
 * nas.local" means the phone is fine and the box is not. An app that says the first when the second
 * is true sends somebody to check their wifi while their server is down.
 *
 * `NET_CAPABILITY_VALIDATED` rather than merely connected, because a captive portal in a hotel is a
 * network by every other measure and reaches nothing. It is what makes "connected but nothing
 * works" report as offline, which is what it is from here.
 */
@Singleton
class Connectivity @Inject constructor(@param:ApplicationContext private val context: Context) {

    val isOnline: Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService<ConnectivityManager>()

        if (manager == null) {
            // No ConnectivityManager is not a device this app runs on,
            // but assuming *offline* would be the wrong failure: it
            // would suppress every request behind a signal that is
            // simply absent. Optimism here costs one failed request and
            // an honest error; pessimism costs the whole app.
            trySend(true)
            close()

            return@callbackFlow
        }

        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(true)
                }

                override fun onLost(network: Network) {
                    trySend(manager.hasValidatedNetwork())
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    trySend(
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    )
                }
            }

        // The current answer first. A callback only reports *changes*,
        // so a collector that started while the phone was already
        // offline would sit on the initial value until something moved
        // — which on a phone in a basement is never.
        trySend(manager.hasValidatedNetwork())

        manager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            callback,
        )

        awaitClose { manager.unregisterNetworkCallback(callback) }
    }
        .distinctUntilChanged()
}

private fun ConnectivityManager.hasValidatedNetwork(): Boolean =
    activeNetwork
        ?.let(::getNetworkCapabilities)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
