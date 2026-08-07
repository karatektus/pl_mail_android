package de.plmail.feature.calendar

import de.plmail.core.database.AgendaRow
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The occurrences that draw one row — one meeting, however many rows the cache holds it in.
 *
 * **One meeting legitimately reaches plMail twice.** An invitation arrives by mail and is extracted
 * onto the account's own calendar with the organiser's UID; a provider auto-adds the same meeting
 * and the mirror pulls it onto a connected calendar, with the same UID and a remote id of its own.
 * Both rows are correct and the server keeps both, deliberately: collapsing them in the model would
 * break sync, because each is a remote object with its own etag and sync state.
 *
 * **The collapse is therefore the client's, and on this surface it has to be.**
 * `CalendarEvent/query` answers with the ids of the rows it holds — see
 * `docs/internals/calendar-model.md`, which lists JMAP as the one reader deliberately left
 * uncollapsed — because a protocol that merged them would hand a client ids it cannot then `get`.
 * The web collapses at read time in `App\Service\Calendar\EventClusterer`; this is that class, and
 * every rule below is copied from it rather than reinvented, so the phone and the browser cannot
 * come to draw a different number of chips for one meeting.
 *
 * **A lone occurrence is a cluster of one.** That is why this type exists rather than a duplicates
 * side-channel: every view keeps exactly one code path, and a row decides how to draw its dot from
 * [isMerged] rather than from which shape it was handed. Almost no cluster is merged.
 *
 * [primary] is the occurrence every single-valued question is answered from — the time, the title,
 * the event a detail screen opens on. Picking one is only honest because a cluster of several
 * exists *only while its members agree* on everything a user would notice; the moment they do not,
 * [clusterRows] splits them back into clusters of one and there is no winner to pick.
 */
data class EventCluster(
    /** Every row this one meeting is held in, in a deterministic order. See [clusterRows]. */
    val members: List<AgendaRow>
) {
    init {
        require(members.isNotEmpty()) { "A cluster is at least one occurrence." }
    }

    /**
     * The member every single-valued question is answered from.
     *
     * The first, and the ordering is [clusterRows]'s — which is the one place this had to add
     * something the server gets for free. The web's `OccurrenceCluster::of` takes `$members[0]` and
     * is stable because its caller's query ends `ORDER BY startsAt, id`; a Room query ordered only
     * by start hands ties back in whatever order SQLite found them, and a representative that moves
     * between two renders of the same data is a detail screen that opens on a different copy each
     * time it is tapped.
     */
    val primary: AgendaRow
        get() = members.first()

    /** True when this row stands for more than one stored copy of the meeting. */
    val isMerged: Boolean
        get() = members.size > 1

    /**
     * The member calendars' colours, in [members] order, as the server spells them.
     *
     * A list rather than a set or a map: it is read positionally, because a merged row's dot hands
     * out one equal slice per member and the calendar names in the description are joined in the
     * same order, so the two readings of the dot agree with each other.
     *
     * Nulls are kept rather than dropped — a calendar whose row has not been refreshed yet has no
     * colour, and dropping it would give a two-calendar meeting a one-colour dot, which says
     * something untrue.
     */
    val colors: List<String?>
        get() = members.map { it.calendarColor }

    /** The member calendars' names, in [members] order. See [colors]. */
    val calendarNames: List<String>
        get() = members.mapNotNull { it.calendarName?.takeIf(String::isNotBlank) }
}

/**
 * Groups a day's — or a window's — occurrence rows into the rows a person reads.
 *
 * **UID plus start is the key, and it is the only honest one.** Matching on title and time would
 * collapse a weekly 1:1 held with two different people at the same hour into one row, which is a
 * meeting quietly disappearing from a calendar — the worst shape a calendar bug takes. The start is
 * in the key because two occurrences of one series are the same *event* and not the same *meeting*.
 *
 * **A group is merged only while its members agree**, on exactly the five things a user would
 * notice on a row: start, end, title, all-day, and whether it has been called off. The moment they
 * disagree the group splits back into clusters of one and the views draw a row each. That is
 * deliberate: a merged row that quietly picked a winner would hide a real disagreement — an update
 * that reached one path and not the other — behind a tidier UI, which is the difference between a
 * merge and a cover-up. And **disagreement splits the whole group** rather than merging the
 * majority sub-group, because a majority is a winner picked with extra steps.
 *
 * **Recurrence is deliberately not one of the five.** Two copies where one repeats and the other
 * does not agree about the occurrence they share and about nothing else, and the repeating copy
 * draws its own rows on every later day with no partner to merge with — which is exactly the
 * visible signal that the two differ.
 *
 * **Two rows on one calendar are two meetings, by construction.** The server's UID is unique within
 * a calendar, so a repeat there is one series with two occurrences at the same instant — an
 * instance dragged onto a sibling's time — and merging those would erase one of them from the view.
 *
 * A row with **no uid** is never merged with anything. The web reaches the same answer through
 * `copiesOf`'s empty-uid guard; here it matters more, because [AgendaRow.eventUid] is nullable for
 * a cache written before anything read the column, and a null grouping key would fold every such
 * row on a day into one.
 *
 * Order is preserved: every member of a group shares its start, so grouping cannot reorder the
 * result, and a caller's day grouping stays stable between renders.
 */
fun clusterRows(rows: List<AgendaRow>): List<EventCluster> {
    val grouped = LinkedHashMap<String, MutableList<AgendaRow>>()

    rows.forEachIndexed { index, row ->
        // A row with no uid is not matchable, so it gets a key nothing else can
        // collide with rather than sharing the empty one.
        val key =
            row.eventUid?.takeIf { it.isNotBlank() }?.let { "${row.startKey()}|$it" } ?: "#$index"

        grouped.getOrPut(key) { mutableListOf() } += row
    }

    return grouped.values.flatMap { group ->
        val ordered = group.sortedWith(MEMBER_ORDER)

        if (ordered.membersAgree()) listOf(EventCluster(ordered))
        else ordered.map { EventCluster(listOf(it)) }
    }
}

/**
 * Which member leads, and it has to be decided rather than inherited.
 *
 * By id, ascending, numerically where the server's ids are numbers — which they are on plMail — and
 * lexically otherwise, so an id shape this build has not seen still produces *an* order rather than
 * none. The event key breaks a remaining tie, which can only happen across two accounts.
 */
private val MEMBER_ORDER: Comparator<AgendaRow> =
    compareBy({ it.eventId.toLongOrNull() ?: Long.MAX_VALUE }, { it.eventId }, { it.eventKey })

/**
 * Whether every member of a group is the same meeting as every other.
 *
 * The five fields, plus the one-row-per-calendar rule. A group of one always agrees, which is the
 * ordinary case and costs nothing to say.
 */
private fun List<AgendaRow>.membersAgree(): Boolean {
    if (size == 1) return true

    val calendars = mutableSetOf<String>()
    var signature: List<Any?>? = null

    forEach { row ->
        if (!calendars.add(row.calendarKey)) return false

        val current = row.signature()

        if (signature == null) signature = current else if (current != signature) return false
    }

    return true
}

/**
 * What a user would notice about one occurrence, as a comparable value.
 *
 * Times through [startKey] and [endKey] rather than as the stored strings: two copies of one
 * meeting can be published with different zones — one extracted with the organiser's, one mirrored
 * with the calendar's — and `10:00 Europe/Berlin` and `09:00 Europe/London` are the same moment and
 * must not read as a disagreement. That is the server's rule too; it compares timestamps rather
 * than `DateTimeImmutable`s for exactly this reason.
 *
 * "Called off" is the event's `status`, which is the only form of cancellation that reaches this
 * cache: an occurrence cancelled by an `excluded` override is simply not answered by the expanded
 * query, so there is no row for it to disagree with. What can reach the screen is one copy marked
 * cancelled and the other confirmed, and merging those would draw a live meeting that one of the
 * two paths has been told is off.
 */
private fun AgendaRow.signature(): List<Any?> =
    listOf(startKey(), endKey(), title, isAllDay, status == STATUS_CANCELLED)

/**
 * When this occurrence starts, as a value two copies can be compared on.
 *
 * The instant where a zone is published, and the bare wall clock where none is. Both are correct
 * and they are deliberately not interchangeable: an all-day event and a floating one have no
 * instant at all — that is the whole point of storing them as a wall clock — so resolving them
 * against the device would make the same birthday compare unequal to itself the day somebody
 * travels.
 *
 * A zoned occurrence and a floating one therefore never merge, which is right: they are two
 * different claims about when the meeting is.
 */
internal fun AgendaRow.startKey(): String = instantKey(startLocal, zoneId)

internal fun AgendaRow.endKey(): String = instantKey(endLocal, zoneId)

private fun instantKey(local: String?, zone: String?): String {
    val at = local.toLocalDateTimeOrNull() ?: return "?${local.orEmpty()}"
    val id = zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return "floating/$at"

    return at.atZone(id).toInstant().toString()
}

/** The wire's word for an event that has been called off. */
internal const val STATUS_CANCELLED = "cancelled"

/** The occurrence's own start, for placing it. Null for an all-day row and an unparseable one. */
internal fun EventCluster.startsAt(): LocalDateTime? = primary.startLocal.toLocalDateTimeOrNull()

internal fun EventCluster.endsAt(): LocalDateTime? = primary.endLocal.toLocalDateTimeOrNull()
