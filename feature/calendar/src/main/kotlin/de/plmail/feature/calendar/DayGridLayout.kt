package de.plmail.feature.calendar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * One cluster's place in a day column, as fractions of that column.
 *
 * Fractions rather than dp so the same numbers stay correct at whatever height the column ends up
 * with — a phone, a tablet, or a grid the user has scrolled into a different zoom. The composable
 * multiplies them by the height it actually has and nothing has to be recomputed when that changes.
 */
data class PlacedCluster(
    val cluster: EventCluster,
    /** Where it starts, 0f at midnight and 1f at the next. */
    val top: Float,
    /** How tall it is, as a fraction of the day. May be 0f for a zero-length event. */
    val height: Float,
    /** Which column-within-the-column this takes, 0-based. */
    val lane: Int,
    /** How many lanes the run it belongs to is wide. Never 0. */
    val lanes: Int,
    /** It began before this day. The block is clipped at the top and says so. */
    val continuesBefore: Boolean,
    /** It ends after this day. */
    val continuesAfter: Boolean,
)

/** One day of a time grid: what has no time, and where everything that does goes. */
data class DayGrid(
    val date: LocalDate,
    /** All-day and multi-day rows, which live in the band above the axis rather than on it. */
    val allDay: List<EventCluster>,
    val timed: List<PlacedCluster>,
)

/**
 * Turning a day's clusters into positions on a time grid.
 *
 * A port of `App\Service\Calendar\DayGridLayout`, rule for rule, so the phone and the browser draw
 * the same day the same way. What each rule is for:
 *
 * **Positions are wall-clock minutes since local midnight, not elapsed time.** The grid draws
 * twenty-four labelled rows, so a block has to land against the row whose label matches the clock
 * the user reads the event in; deriving the offset from a difference of instants would agree with
 * the labels on every day except the two a year that are not twenty-four hours long, and disagree
 * by an hour on those. The consequence is stated rather than hidden: on the day a zone springs
 * forward the 02:00 row is drawn although no event can be in it, and on the day it falls back the
 * two 02:00s are drawn over each other as an overlap. Both are what every other calendar does, and
 * both are honest about a grid that has one row per label. It costs nothing here in any case — the
 * cache stores wall clock and never an instant, so there is no difference of instants available to
 * get this wrong with.
 *
 * **Overlap is answered by lanes, in runs.** Everything that overlaps anything else in an unbroken
 * run of the day shares one lane count, so the block edges line up down the whole run. Sizing each
 * pair independently is the obvious cheaper thing and it produces a column where a two-wide block
 * sits beside a three-wide one, which reads as a rendering fault rather than as information.
 *
 * **Deliberately not done:** an event with free lanes to its right is not widened to fill them.
 * Google does that and it looks tidier, but it makes a block's width depend on events it does not
 * overlap, so adding an event at 3pm can resize one at 9am — and the widths then stop being
 * readable as "this many things are happening at once", which is the only thing the width is for.
 *
 * A cluster is placed by its primary's span, which is the whole cluster's: members that disagree
 * about when they are have already been split apart by [clusterRows], so there is no second answer
 * to choose between.
 */
fun placeDay(date: LocalDate, clusters: List<EventCluster>): DayGrid {
    val dayStart = date.atStartOfDay()

    // The next local midnight rather than `plusMinutes(1440)`: on a day that
    // springs forward the two are an hour apart, and an event starting at 23:30
    // that evening would otherwise be judged to run past the end of its own day.
    val dayEnd = date.plusDays(1).atStartOfDay()

    val allDay = mutableListOf<EventCluster>()
    val spans = mutableListOf<Span>()

    clusters.forEach { cluster ->
        if (cluster.primary.isAllDay) allDay += cluster
        else spans += cluster.spanOf(dayStart, dayEnd)
    }

    return DayGrid(date = date, allDay = allDay, timed = assignLanes(spans))
}

/** One cluster clipped to one day, as a pair of minute offsets. */
private data class Span(
    val cluster: EventCluster,
    val from: Int,
    val to: Int,
    val before: Boolean,
    val after: Boolean,
)

private fun EventCluster.spanOf(dayStart: LocalDateTime, dayEnd: LocalDateTime): Span {
    val starts = startsAt() ?: dayStart
    val ends = endsAt() ?: starts

    val before = starts < dayStart

    // `>=` on the end and `>` on the flag, and the difference is the bug this
    // line exists for: an event finishing exactly at midnight is not continuing
    // into tomorrow, but its end reads as 00:00 and would give it a height
    // running from its start back up to the top of the column.
    val from = if (before) 0 else starts.minuteOfDay()
    val to = if (ends >= dayEnd) MINUTES_IN_DAY else ends.minuteOfDay()

    return Span(
        cluster = this,
        from = from,
        // An end before its start is data to survive, not a condition to raise:
        // a negative height would be a block drawn upwards over the ones above.
        to = maxOf(from, to),
        before = before,
        after = ends > dayEnd,
    )
}

/**
 * Hands every span a lane, and every span in the same overlapping run the same lane count.
 *
 * A run ends at the first span that starts at or after everything before it has finished. Within a
 * run, a span takes the first lane whose previous occupant has ended — greedy, which is optimal for
 * interval colouring on a list sorted by start, and is why the sort below is not incidental.
 */
private fun assignLanes(spans: List<Span>): List<PlacedCluster> {
    // Longest-first among equal starts, so the block that covers the others
    // takes lane 0 and the short ones stack to its right. The reverse puts the
    // long block in the rightmost lane with a column of stubs beside it that
    // look like the main event.
    val sorted = spans.sortedWith(compareBy({ it.from }, { -it.to }))

    val placed = mutableListOf<PlacedCluster>()
    val run = mutableListOf<Span>()

    // Minute -1 rather than a null, so the "has the run ended" test is one
    // comparison. Nothing can start before minute 0, so an empty run never ends.
    var runEnd = NO_RUN

    for (span in sorted) {
        if (runEnd != NO_RUN && span.from >= runEnd) {
            placed += layOutRun(run)
            run.clear()
            runEnd = NO_RUN
        }

        run += span
        runEnd = if (runEnd == NO_RUN) span.to else maxOf(runEnd, span.to)
    }

    return placed + layOutRun(run)
}

private fun layOutRun(run: List<Span>): List<PlacedCluster> {
    if (run.isEmpty()) return emptyList()

    /** Where each lane's current occupant finishes. */
    val laneEnds = mutableListOf<Int>()
    val lanes = IntArray(run.size)

    run.forEachIndexed { index, span ->
        val free = laneEnds.indexOfFirst { it <= span.from }
        val lane = if (free >= 0) free else laneEnds.size.also { laneEnds += 0 }

        laneEnds[lane] = span.to
        lanes[index] = lane
    }

    val width = laneEnds.size

    return run.mapIndexed { index, span ->
        PlacedCluster(
            cluster = span.cluster,
            top = span.from.toFloat() / MINUTES_IN_DAY,
            height = (span.to - span.from).toFloat() / MINUTES_IN_DAY,
            lane = lanes[index],
            lanes = width,
            continuesBefore = span.before,
            continuesAfter = span.after,
        )
    }
}

/**
 * Minutes since local midnight, read off the wall clock.
 *
 * See the file's note on why that distinction is the whole of DST handling here.
 */
private fun LocalDateTime.minuteOfDay(): Int = toLocalTime().toSecondOfDay() / SECONDS_IN_MINUTE

/**
 * The vertical axis, in minutes.
 *
 * A grid column is one calendar day and is drawn as twenty-four equal rows whatever the zone did
 * that day.
 */
internal const val MINUTES_IN_DAY = 24 * 60

private const val SECONDS_IN_MINUTE = 60

/** "No run is open." A minute nothing can start at. See [assignLanes]. */
private const val NO_RUN = -1

/**
 * The slot a tap at [fraction] down a day column lands in, snapped to the quarter hour.
 *
 * A quarter is what the web's grid snaps a new event to, and it is the smallest unit any of this
 * product's editors can express — the time picker offers minutes, but nobody who taps a column at
 * 09:07 meant 09:07. Snapping **down** rather than to the nearest, because a tap just above the
 * 10:00 line meaning "ten" is the same gesture as a tap just below it, and rounding up would put an
 * event at a time above where the finger was.
 *
 * Clamped rather than trusted: a fling that ends a pixel past the bottom of the column would
 * otherwise propose an event at 24:00, which is a time that does not exist.
 */
internal fun slotAt(date: LocalDate, fraction: Float): LocalDateTime {
    val minute = (fraction * MINUTES_IN_DAY).toInt().coerceIn(0, MINUTES_IN_DAY - 1)
    val snapped = minute - minute % SLOT_MINUTES

    return LocalDateTime.of(date, LocalTime.MIDNIGHT).plusMinutes(snapped.toLong())
}

/** The quarter hour. See [slotAt]. */
internal const val SLOT_MINUTES = 15
