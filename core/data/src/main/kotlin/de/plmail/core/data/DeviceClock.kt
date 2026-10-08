package de.plmail.core.data

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The system clock, in whatever zone the device is in *now*.
 *
 * `Clock.systemDefaultZone()` reads the zone once, when it is called, and keeps it — and this app
 * holds one clock for the life of the process. So a phone that landed in another country went on
 * placing calendar events, and deciding what "today" is, in the zone it took off from until Android
 * happened to kill the process. This asks each time. The platform updates the default zone in a
 * running process when it changes, so asking is all it takes.
 */
internal object DeviceClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)

    override fun instant(): Instant = Instant.now()
}
