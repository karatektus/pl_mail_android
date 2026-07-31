package de.plmail.jmap.client

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Holds the client to the number of concurrent requests the server said it accepts.
 *
 * This is not politeness. plMail may be a Raspberry Pi behind a single FrankenPHP worker pool, and
 * a client that opens twenty connections because it has twenty things to fetch takes the whole
 * server down for its own user — including the web UI they would use to fix it.
 * `maxConcurrentRequests` is typically 4.
 */
class RequestGate(permits: Int) {
    private val semaphore = Semaphore(permits.coerceAtLeast(1))

    val availablePermits: Int
        get() = semaphore.availablePermits

    suspend fun <T> withPermit(body: suspend () -> T): T = semaphore.withPermit { body() }

    /**
     * Takes a permit and keeps it until released.
     *
     * For the one caller that needs it: an EventSource connection lives for minutes, and running it
     * through [withPermit] would hold a permit inside a suspension that never returns —
     * indistinguishable from a leak, and invisible until the app quietly stops being able to fetch
     * anything. Making the long hold explicit is the point.
     */
    suspend fun reserve(): Reservation {
        semaphore.acquire()
        return Reservation()
    }

    inner class Reservation : AutoCloseable {
        private var released = false

        override fun close() {
            // Idempotent: a stream that ends and is then cancelled would
            // otherwise release twice and hand out a permit that does not
            // exist, which surfaces much later as too many open connections.
            if (released) return

            released = true
            semaphore.release()
        }
    }
}
