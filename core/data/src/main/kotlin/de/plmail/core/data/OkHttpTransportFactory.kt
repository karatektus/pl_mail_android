package de.plmail.core.data

import de.plmail.jmap.client.JmapTransport
import de.plmail.jmap.client.KeyFingerprint
import de.plmail.jmap.client.OkHttpTransport
import de.plmail.jmap.client.ServerAddress
import de.plmail.jmap.client.ServerTrust
import de.plmail.jmap.client.StreamingTransport
import de.plmail.jmap.client.serverTrust
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * The real [TransportFactory].
 *
 * One shared [OkHttpClient] is the base for every server, and each server gets a client derived
 * from it with its own trust manager. `newBuilder()` rather than a fresh `OkHttpClient()` is what
 * makes that affordable: the derived client shares the connection pool, dispatcher and thread
 * pools, so per-server clients cost almost nothing — building them from scratch would give each
 * server its own idle threads and its own pool, which on a device syncing three accounts is pure
 * waste.
 *
 * Timeouts are generous on purpose. The server is frequently a Raspberry Pi or a NAS that has just
 * woken a disk, reached over a VPN from a train; the default ten seconds turns a slow-but-working
 * setup into an app that reports the server as down.
 */
@Singleton
class OkHttpTransportFactory @Inject constructor() : TransportFactory {

    private val base: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_SECONDS, TimeUnit.SECONDS)
            // Retries are the client's own, not OkHttp's: JMAP requests are not
            // all idempotent, and a silently repeated Email/set is a duplicate
            // send.
            .retryOnConnectionFailure(false)
            .build()

    /**
     * The base for a connection that is meant to be silent, derived once.
     *
     * `readTimeout(0)` is the entire difference, and it is a statement about the protocol rather
     * than laxity: an EventSource stream is *expected* to write nothing between events, so there is
     * no duration after which silence is evidence of anything. What notices a dead connection is
     * the server's own `ping` and the socket closing, neither of which a timeout here would improve
     * on.
     *
     * `by lazy`, because a device with a working push distributor never opens one of these — see
     * `LiveUpdates`.
     */
    private val streaming: OkHttpClient by lazy {
        base.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    override fun create(address: ServerAddress, pinned: KeyFingerprint?): JmapTransport =
        OkHttpTransport(base.newBuilder().serverTrust(ServerTrust(address.host, pinned)).build())

    override fun createStreaming(
        address: ServerAddress,
        pinned: KeyFingerprint?,
    ): StreamingTransport =
        OkHttpTransport(
            streaming.newBuilder().serverTrust(ServerTrust(address.host, pinned)).build()
        )

    private companion object {
        const val CONNECT_SECONDS = 20L

        /**
         * For ordinary requests only.
         *
         * An idle EventSource connection is held open for 300 seconds by design and sends nothing
         * while the mailbox is quiet, so reading one through this client would abort it here every
         * sixty seconds and look exactly like a flaky server. That is why [streaming] exists rather
         * than the stream borrowing this client — the note that used to stand here promised the
         * separate client would arrive as a deliberate addition, and this is it.
         */
        const val READ_SECONDS = 60L
    }
}
