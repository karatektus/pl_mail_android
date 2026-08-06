package de.plmail.core.data

import androidx.room.withTransaction
import de.plmail.core.database.AgendaRow
import de.plmail.core.database.CalendarEntity
import de.plmail.core.database.CalendarEventEntity
import de.plmail.core.database.CalendarOccurrenceEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.datastore.CredentialStore
import de.plmail.jmap.calendar.Calendar
import de.plmail.jmap.calendar.CalendarEvent
import de.plmail.jmap.calendar.CalendarEventFilter
import de.plmail.jmap.calendar.RecurrenceRule
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.methods.CalendarEventGet
import de.plmail.jmap.methods.CalendarEventPatch
import de.plmail.jmap.methods.CalendarEventQuery
import de.plmail.jmap.methods.CalendarEventQueryResult
import de.plmail.jmap.methods.CalendarEventSet
import de.plmail.jmap.methods.CalendarGet
import de.plmail.jmap.methods.EventTimeZone
import de.plmail.jmap.methods.NewCalendarEvent
import de.plmail.jmap.methods.SetError
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.CalendarEventId
import de.plmail.jmap.protocol.CalendarId
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.JmapError
import de.plmail.jmap.protocol.MaterialisedHorizon
import de.plmail.jmap.protocol.RequestBuilder
import de.plmail.jmap.protocol.ResultReference
import de.plmail.jmap.protocol.Session
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A span of days, half-open: [from] is shown, [to] is the first day that is not.
 *
 * Half-open because the wire is — `CalendarEvent/query`'s `after` is inclusive and its `before` is
 * exclusive — and a window closed at both ends would have to add and subtract a day at every
 * boundary, which is the arithmetic that puts an event on the wrong side of a month.
 */
data class CalendarWindow(val from: LocalDate, val to: LocalDate) {

    init {
        require(to.isAfter(from)) { "A calendar window has to contain at least one day." }
        require(from.plusDays(MAX_DAYS) >= to) {
            "A window of more than $MAX_DAYS days is more occurrences than a screen can draw and " +
                "more pages than one refresh should spend. Refresh what is on screen."
        }
    }

    /**
     * This window, narrowed to the span the server will expand occurrences over, or null when none
     * of it is.
     *
     * An expanded `CalendarEvent/query` past the account's `materialisedHorizon` is refused
     * outright with `cannotCalculateOccurrences` rather than answered short — the answer *is* the
     * list of occurrences, so a series that stops at the horizon would come back as a series that
     * ends. So the window is clamped before it is sent, and a caller that got back less than it
     * asked for is told the client cannot promise the rest.
     *
     * Clamped against the client's **own** conservative line rather than the server's words,
     * because `materialisedHorizon` is published as PHP relative-date expressions — `-1 year`, `+2
     * years` — which are opaque strings nothing here may parse. [GUARANTEED_DAYS] is inside the
     * horizon this server actually keeps in both directions, so the error is always in the
     * direction of asking for less than is there and saying so.
     */
    internal fun clampedTo(today: LocalDate): CalendarWindow? {
        val start = maxOf(from, today.minusDays(GUARANTEED_DAYS))
        val end = minOf(to, today.plusDays(GUARANTEED_DAYS))

        return if (end.isAfter(start)) CalendarWindow(start, end) else null
    }

    /**
     * The wire's inclusive lower bound for *fetching* this window, in UTC — see [startOfDayUtc].
     *
     * Deliberately the **earlier** of the two readings of "the first moment of [from]", and the
     * upper bound is the later of the two: an event the server holds as an instant is bounded by
     * the UTC-converted local midnight, and one it holds as a wall clock — every all-day event, and
     * anything imported with an explicit null zone — by the naive one. A single query has to reach
     * both, and the two disagree by the device's offset, so the fetch takes their union and lets
     * placement decide which local day each event is actually on. The cost is at most a day's
     * events at each edge, which `place` drops; the alternative is a floating event at 23:00 on the
     * window's last day never being asked for at all.
     */
    internal fun fetchAfter(zone: ZoneId): String =
        minOf(from.startOfDayUtc(zone), from.atStartOfDay()).toWire()

    /** The wire's exclusive upper bound. See [fetchAfter] for why it is a union. */
    internal fun fetchBefore(zone: ZoneId): String =
        maxOf(to.startOfDayUtc(zone), to.atStartOfDay()).toWire()

    companion object {
        /**
         * A year and a day.
         *
         * Not a performance cap so much as an honesty one: an expanded query counts *occurrences*,
         * so a daily standup over a decade is thousands of ids paged a hundred at a time against a
         * machine that advertises four concurrent requests. A calendar shows a month.
         */
        const val MAX_DAYS = 366L

        /**
         * How far either side of today a window may reach before it is clamped. See [clampedTo].
         *
         * The client's own line rather than the server's, drawn conservatively: this server
         * materialises a year back and two years forward, so a window in the second year is clamped
         * and reported as possibly incomplete when it was in fact answerable. Saying "there may be
         * more" about a full month is a smaller lie than a month that silently stops.
         */
        const val GUARANTEED_DAYS = 365L

        /** The span a month grid needs, including the leading and trailing part-weeks. */
        fun around(day: LocalDate, before: Long = 7, after: Long = 42): CalendarWindow =
            CalendarWindow(day.minusDays(before), day.plusDays(after))
    }
}

/** What a refresh did, or why it did nothing. */
sealed interface CalendarRefresh {
    data class Refreshed(
        val events: Int,
        val occurrences: Int,
        /** Round trips spent. One for a window inside the horizon, however much recurs in it. */
        val requests: Int,
        /**
         * Whether part of this window was outside what the server will answer for.
         *
         * True does not mean anything is missing; it means the client cannot promise nothing is,
         * which is a different sentence and the only honest one available. See
         * [CalendarWindow.clampedTo].
         */
        val mayBeIncomplete: Boolean,
        /** The server's own words for how far it materialises. Opaque — display, never parse. */
        val horizon: MaterialisedHorizon,
    ) : CalendarRefresh

    /**
     * There is no calendar to show — either nothing is paired yet, or this server publishes no
     * calendar account.
     *
     * One case rather than two, because the app already has an answer to "not paired" everywhere
     * else and a calendar screen has the same thing to draw either way. It is a state, not an
     * error: an instance without the vendor extension is a supported instance.
     */
    data object NoCalendarAccount : CalendarRefresh

    /** Nothing answered. [host] where the transport knew one, because a name is actionable. */
    data class Unreachable(val host: String?) : CalendarRefresh

    /** The server answered, and the answer was no. */
    data class Rejected(val reason: String) : CalendarRefresh
}

/** What a create, change or delete came to. */
sealed interface CalendarWriteResult {
    /** The server took it. [eventKey] is the row the cache now holds. */
    data class Applied(val eventKey: String, val id: CalendarEventId) : CalendarWriteResult

    /**
     * The server refused, and a refusal is an answer.
     *
     * Never retried and never queued — replaying a refusal produces a loop that terminates never,
     * which is the rule the mail outbox is built on and the reason calendar writes stay out of it.
     */
    data class Rejected(val type: String, val reason: String) : CalendarWriteResult {
        /** A read-only calendar, as the server reports it. Never guessed from `myRights`. */
        val isForbidden: Boolean
            get() = type == FORBIDDEN

        private companion object {
            const val FORBIDDEN = "forbidden"
        }
    }

    /** Nothing answered. The change reached nothing, and has been taken back off the cache. */
    data class Unreachable(val host: String?) : CalendarWriteResult

    data object NoCalendarAccount : CalendarWriteResult
}

/**
 * An event as a form holds it: the whole event, never a patch.
 *
 * `CalendarEvent/set` refuses JSON-pointer patch paths — the one place it and `Email/set` genuinely
 * disagree — so every change sends whole properties anyway. Carrying the whole event here means a
 * caller cannot accidentally send half of one.
 */
data class EventDraft(
    val title: String,
    /**
     * Local wall-clock time, formatted for the wire in this file.
     *
     * Kept as a `LocalDateTime` rather than a string so that formatting an instant — the single
     * easiest way to get this wrong, and one the server only reports after the round trip — is not
     * something a caller can do.
     */
    val start: LocalDateTime,
    val duration: Duration,
    val isAllDay: Boolean = false,
    /** Null means the calendar's own zone. [EventTimeZone.Floating] is a different event. */
    val timeZone: EventTimeZone? = null,
    val location: String? = null,
    val description: String? = null,
    val status: String = NewCalendarEvent.STATUS_CONFIRMED,
    /**
     * **Create only.** An update deliberately never sends `recurrenceRules`.
     *
     * The cache stores *whether* an event recurs but not the rule it recurs by — no screen in this
     * app draws a rule — so an update built from a draft would send one derived from nothing, and
     * null on that property means "stop recurring". Silently un-recurring somebody's standup
     * because they corrected its title is not a trade worth making for an editor that does not
     * exist yet.
     */
    val recurrenceRule: RecurrenceRule? = null,
)

/**
 * The calendar as the event editor uses it: one event out, three ways in.
 *
 * A seam rather than [CalendarRepository] itself, for the reason [KnownLabels] is one: the
 * repository reaches Room, DataStore and OkHttp, so an editor holding it cannot be exercised
 * anywhere those are not — and the questions worth asking of that screen are about which form is on
 * it, which is a question a database has no part in.
 */
interface EventEditing {

    suspend fun event(eventKey: String): CalendarEventEntity?

    suspend fun create(calendarKey: String, draft: EventDraft): CalendarWriteResult

    suspend fun update(eventKey: String, draft: EventDraft): CalendarWriteResult

    suspend fun delete(eventKey: String): CalendarWriteResult
}

/**
 * The calendar, cached and refreshed on demand.
 *
 * **This surface has no delta and no push, and everything here follows from that.** The state is
 * the literal string `"fixed"` forever, there is no `CalendarEvent/changes`, and the server's
 * StateChange push tracks Mailbox, Email, Thread and EmailSubmission — never an event. So there is
 * nothing to subscribe to and nothing to compare against: refreshing means re-running the windowed
 * query, and the cache is what the UI reads in between.
 *
 * Which means **refresh only while somebody is looking**: the calendar opening, a pull-to-refresh,
 * at most a foreground with the calendar on screen. Never a timer. The audience runs this on a
 * Raspberry Pi with a single PHP worker pool, and a calendar polling in the background is the one
 * thing in this app that could make the server slow for the person who owns it.
 *
 * Recurring events are placed by the **server**, from `CalendarEvent/query`'s `expandRecurrences`:
 * one id per occurrence in the window, and a `CalendarEvent/get` back-referenced onto it says where
 * each of them is. Expanding a rule on the device is forbidden by the client specification, and the
 * reason is that the phone and the web UI would then disagree at a DST boundary — the same event,
 * an hour apart, on two screens of the same product. Until 2026-08-06 obeying that cost one one-day
 * probe query per day of the window; it now costs one argument.
 *
 * An occurrence id is **opaque** — `42_20260304T090000Z` is the series id and the occurrence's
 * original start, and reading either half back out is the same client-side expansion by a quieter
 * route. Which series an occurrence belongs to is its object's `seriesId`, and where it goes is its
 * object's `start`.
 */
@Singleton
class CalendarRepository
@Inject
constructor(
    private val database: PlMailDatabase,
    private val clients: AccountClients,
    private val credentials: CredentialStore,
    private val clock: Clock,
) : EventEditing {

    /**
     * The window most recently refreshed.
     *
     * Held because a write involving recurrence has to re-ask the server where the occurrences
     * landed, and the range worth re-asking about is the one on screen. Reconstructing them locally
     * from a rule is the one thing this class may not do.
     */
    @Volatile private var lastRefreshed: CalendarWindow? = null

    /**
     * The zone every query window is converted out of.
     *
     * The device's, from the injected [Clock], because a window is "the days this person is looking
     * at" and that is a wall clock rather than a location. Injected so a test can be a phone in
     * Berlin without the machine running it having to be one.
     */
    private val zone: ZoneId
        get() = clock.zone

    /** Every calendar, invisible ones included — see `CalendarDao.observeAll`. */
    fun calendars(): Flow<List<CalendarEntity>> = database.calendars().observeAll()

    /**
     * Whether this install has a calendar at all, for deciding whether to offer one.
     *
     * Two sources, because either alone is wrong. The **session** is the authority — an instance
     * that publishes no calendars capability has no calendar and must not be offered one — but
     * asking it needs the network, and a cold launch on a train would then hide a calendar the
     * device is holding a month of. The **cache** answers that case and cannot answer the first: it
     * is empty before the first refresh, and a screen that could only appear after being opened
     * could never be opened.
     *
     * So: the cache, or the session, and false while neither has spoken. The false comes first
     * deliberately — a probe that has to time out must not hold the answer, because what is waiting
     * on it is a navigation row that is either there or not.
     */
    fun isAvailable(): Flow<Boolean> =
        combine(calendars(), publishesCalendars()) { cached, published ->
                cached.isNotEmpty() || published
            }
            // Two sources feeding one boolean, so most of what arrives here says
            // the same thing twice -- every refresh rewrites the calendar rows,
            // and the answer to "is there a calendar" was already yes.
            .distinctUntilChanged()

    /**
     * Whether the session nominates a calendar account, re-asked when the answer could have
     * changed.
     *
     * **Asked once per collection was a bug, seen on a fresh install on 2026-08-06**: the drawer
     * collects this before pairing has stored a credential, the probe answers false with nothing to
     * ask, and nothing ever asked again — so the Calendar row only appeared after the process was
     * killed and relaunched. The cache leg could not rescue it either, because the cache only gains
     * calendars from a refresh and the only way to a refresh was the row that was not there.
     *
     * Re-asked on a *change in the world*, never on a timer: the stored connection, which is what
     * pairing writes, and the account rows, which is what a sync that fetched a session writes.
     * Both are the flows the rest of the app already treats as "the accounts changed" — see
     * `AccountsRepository`. Reduced to the parts a probe's answer can depend on before
     * [distinctUntilChanged], because `dataStore.data` and Room's invalidation tracker both re-emit
     * for writes that changed nothing this cares about, and a probe per push timestamp is polling
     * with extra steps.
     *
     * [transformLatest] rather than a plain map, so a probe still waiting on a server that has gone
     * away is abandoned when a newer answer is available — and [onStart] keeps the emit-false-first
     * property that stops a hanging probe holding up the drawer.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun publishesCalendars(): Flow<Boolean> =
        combine(credentials.connection, database.accounts().observeAll()) { connection, accounts ->
                connection?.address?.discoveryUrl to accounts.map { it.uid }
            }
            .distinctUntilChanged()
            .transformLatest { emit(hasCalendarAccount()) }
            .onStart { emit(false) }

    /**
     * One event **series** as the cache holds it, for an editor to open on.
     *
     * A series rather than the occurrence that was tapped, and that is the whole reason this exists
     * beside [agenda]: an update sends whole properties, so a form seeded from one occurrence of a
     * weekly standup would send that occurrence's date as the series' start and drag the entire
     * series onto the day the user happened to be looking at.
     */
    override suspend fun event(eventKey: String): CalendarEventEntity? =
        database.calendarEvents().byUid(eventKey)

    /** Everything from [from] onwards, earliest first. The agenda. */
    fun agenda(from: LocalDate, limit: Int = AGENDA_LIMIT): Flow<List<AgendaRow>> =
        database.calendarEvents().observeAgenda(from.toString(), limit)

    /** Everything inside one window, for a week or a month grid. */
    fun occurrences(window: CalendarWindow): Flow<List<AgendaRow>> =
        database.calendarEvents().observeBetween(window.from.toString(), window.to.toString())

    /**
     * Re-runs the window and reconciles the cache to the answer.
     *
     * **One round trip**, for a month, whatever recurs in it — three steps:
     * 1. One request carrying `Calendar/get` and *two* windowed `CalendarEvent/query` calls, each
     *    back-referenced into its own `CalendarEvent/get`: the collapsed query answers the series a
     *    form is edited through, the expanded one answers the occurrences a day view draws. See
     *    [fetch] for why both are needed and why they travel together.
     * 2. Further pages of either while its reported `total` says there are more, chunked by the
     *    account's `maxEventsInGet` — **100**, not core's `maxObjectsInGet` of 500, and counted in
     *    occurrences on the expanded side.
     * 3. One transaction: replace the calendars, upsert the series, replace the window's
     *    occurrences, sweep the series nothing places any more.
     */
    suspend fun refresh(window: CalendarWindow): CalendarRefresh {
        val origin = credentials.connection.first()?.address?.origin
        val client = clients.current()

        if (origin == null || client == null) return CalendarRefresh.NoCalendarAccount

        return try {
            runRefresh(client, origin, window)
        } catch (offline: IOException) {
            CalendarRefresh.Unreachable(host = null)
        } catch (unreachable: JmapError.Unreachable) {
            CalendarRefresh.Unreachable(unreachable.host)
        } catch (rejected: Exception) {
            CalendarRefresh.Rejected(rejected.message ?: "The calendar could not be refreshed.")
        }
    }

    private suspend fun runRefresh(
        client: JmapClient,
        origin: String,
        window: CalendarWindow,
    ): CalendarRefresh {
        val session = client.session()
        val accountId = session.primaryCalendarAccount ?: return CalendarRefresh.NoCalendarAccount
        val accountKey = StoreKey.account(origin, accountId.value)

        // The calendars capability's own limit, never core's. The server allows
        // 500 objects in a general get and 100 events, because expanding a
        // recurring series costs far more than reading a row -- and a client
        // chunking by the wrong number has every request refused.
        val limits = session.calendars(accountId)
        val pageSize = limits?.maxEventsInGet ?: CalendarEventGet.MAX_EVENTS_IN_GET
        val horizon = limits?.materialisedHorizon ?: MaterialisedHorizon()

        // Read once, so the window and everything derived from it cannot be
        // built against two different zones if the device's changes mid-refresh
        // -- which is a flight landing, not a hypothetical.
        val deviceZone = zone

        // Clamped rather than sent as asked: an expanded query past the horizon
        // is refused outright, and the refusal would take the whole month down
        // with it. What is left is asked about honestly and the rest is reported
        // as something the server cannot answer for.
        val asked =
            window.clampedTo(LocalDate.now(clock))
                ?: return CalendarRefresh.Refreshed(
                    events = 0,
                    occurrences = 0,
                    requests = refreshCalendarsOnly(client, session, accountId, accountKey),
                    // Nothing was asked about this window, so nothing may be
                    // written about it either -- in particular the reconcile is
                    // skipped, because "the server was not asked" and "the
                    // server reports nothing here" are the same empty answer and
                    // only one of them means the days are empty.
                    mayBeIncomplete = true,
                    horizon = horizon,
                )

        val fetched =
            try {
                fetch(client, session, accountId, asked, deviceZone, pageSize)
            } catch (beyond: JmapError.MethodFailed) {
                if (beyond.type != CANNOT_CALCULATE_OCCURRENCES) throw beyond

                // The clamp above is the client's own conservative line, so this
                // is an instance materialising less than this one does. Same
                // answer as a window entirely outside it: the cache stands and
                // the screen says the server cannot promise more.
                return CalendarRefresh.Refreshed(
                    events = 0,
                    occurrences = 0,
                    requests = 1,
                    mayBeIncomplete = true,
                    horizon = horizon,
                )
            }

        val calendarRows = fetched.calendars.map { it.toEntity(accountKey) }
        val zones = fetched.calendars.associate { it.id.value to it.timeZone }
        val eventRows = fetched.series.map { it.toEntity(accountKey) }
        val seriesByKey = fetched.series.associateBy { StoreKey.objectKey(accountKey, it.id.value) }

        val occurrences =
            fetched.occurrences.flatMap { occurrence ->
                val eventKey = StoreKey.objectKey(accountKey, occurrence.writableId.value)

                place(
                    occurrence = occurrence,
                    // Read from `seriesId`, never from the occurrence id. The
                    // two agree on this server and the id is documented opaque,
                    // which is exactly the combination that lets a shortcut go
                    // unnoticed until an id shape changes.
                    series = seriesByKey[eventKey],
                    eventKey = eventKey,
                    accountKey = accountKey,
                    window = asked,
                    // The occurrence's own zone, then the calendar's. A get
                    // cannot tell an absent zone from an explicit null, so a
                    // genuinely floating event inherits here -- see
                    // `CalendarEventEntity.timeZone`.
                    calendarZone = seriesByKey[eventKey]?.calendarId?.let { zones[it.value] },
                )
            }

        database.withTransaction {
            val stale =
                database.calendars().forAccount(accountKey).map { it.uid } -
                    calendarRows.map { it.uid }.toSet()

            database.calendars().upsert(calendarRows)
            database.calendars().delete(stale)

            database.calendarEvents().upsertEvents(eventRows)

            // Replace, not merge. There is no `/changes` on this surface, so the
            // only thing that can tell the cache an occurrence is gone -- moved,
            // excluded by an override, or the whole event deleted from another
            // client -- is that the re-run query no longer reports it.
            //
            // Bounded by the window that was *asked about* rather than the one
            // requested, so a clamped tail is left as it was instead of being
            // emptied on the strength of a question nobody put.
            database
                .calendarEvents()
                .clearOccurrencesBetween(asked.from.toString(), asked.to.toString())
            database.calendarEvents().upsertOccurrences(occurrences)

            database.calendarEvents().deleteUnplacedEvents()
        }

        lastRefreshed = window

        return CalendarRefresh.Refreshed(
            events = eventRows.size,
            occurrences = occurrences.size,
            requests = fetched.requests,
            mayBeIncomplete = asked != window,
            horizon = horizon,
        )
    }

    /** What one refresh read off the server before any of it reached a table. */
    private class Fetched(
        val calendars: List<Calendar>,
        /** The series, as an editor reads them: whole objects, their own start, their own rule. */
        val series: List<CalendarEvent>,
        /** One per occurrence in the window, each already resolved against its override. */
        val occurrences: List<CalendarEvent>,
        val requests: Int,
    )

    /**
     * The window, as two queries in one request.
     *
     * **Both are needed, and the second is not the first with extra rows.** `expandRecurrences`
     * answers one id per occurrence, and the object behind an occurrence id is the series with that
     * occurrence's override merged into it — its `start` is that Tuesday's, its `title` may be that
     * Tuesday's, and `recurrenceRules` comes back null. Those are exactly the properties an editor
     * must *not* see: a form seeded from one of them would send that occurrence's date as the
     * series' start and drag a whole standup onto the day somebody happened to be looking at. So
     * the collapsed query answers the series, the expanded one answers the days, and the cache
     * keeps them in the two tables it already had.
     *
     * They travel in one request because a JMAP request is a batch — `Calendar/get`, two queries
     * and two back-referenced gets is five calls against a `maxCallsInRequest` of 32 — and because
     * the series a collapsed query names are a superset of the series the occurrences belong to,
     * both being the same range query underneath. Learning the series ids from the expanded answer
     * instead would cost a second round trip *and* would still not be free of the temptation to
     * read them out of the occurrence ids.
     *
     * Paged rather than asked for in one go, because a get is capped at the account's
     * `maxEventsInGet` and a back-referenced get handed three hundred ids is refused outright — the
     * refusal being of the whole call, so a busy month would draw nothing rather than its first
     * hundred. The two sides page independently: a month of one weekly meeting is one series and
     * five occurrences, and a daily standup reaches a hundred occurrences in a quarter.
     */
    private suspend fun fetch(
        client: JmapClient,
        session: Session,
        accountId: AccountId,
        window: CalendarWindow,
        zone: ZoneId,
        pageSize: Int,
    ): Fetched {
        val after = window.fetchAfter(zone)
        val before = window.fetchBefore(zone)

        val seriesPaging = Paging()
        val occurrencePaging = Paging()

        var calendars = emptyList<Calendar>()
        val series = mutableListOf<CalendarEvent>()
        val occurrences = mutableListOf<CalendarEvent>()
        var requests = 0

        while (seriesPaging.hasMore || occurrencePaging.hasMore) {
            val batch = request(session)

            // Only on the first pass. The calendar list is not windowed and does
            // not page, and re-reading it per page would be traffic that answers
            // the same question every time.
            val calendarsHandle = if (requests == 0) batch.add(CalendarGet(accountId)) else null

            val seriesHandles =
                seriesPaging
                    .takeIf { it.hasMore }
                    ?.let { paging ->
                        val q =
                            batch.add(query(accountId, after, before, paging.position, pageSize))

                        q to batch.add(hydrate(accountId, q.reference("/ids"), CACHED_PROPERTIES))
                    }

            val occurrenceHandles =
                occurrencePaging
                    .takeIf { it.hasMore }
                    ?.let { paging ->
                        val q =
                            batch.add(
                                query(
                                    accountId,
                                    after,
                                    before,
                                    paging.position,
                                    pageSize,
                                    expandRecurrences = true,
                                )
                            )

                        q to
                            batch.add(
                                hydrate(
                                    accountId,
                                    q.reference("/ids"),
                                    CalendarEventGet.OCCURRENCE_PROPERTIES,
                                )
                            )
                    }

            val answers = client.send(batch)

            requests++

            calendarsHandle?.let { calendars = answers.result(it).list }

            seriesHandles?.let { (queryHandle, getHandle) ->
                seriesPaging.advance(answers.result(queryHandle))
                series += answers.result(getHandle).list
            }

            occurrenceHandles?.let { (queryHandle, getHandle) ->
                occurrencePaging.advance(answers.result(queryHandle))
                occurrences += answers.result(getHandle).list
            }
        }

        return Fetched(calendars, series, occurrences, requests)
    }

    /**
     * One query's place in its own answer.
     *
     * Two of these rather than one because the collapsed and expanded queries count different
     * things — series against occurrences — so one cursor over both would page whichever ran out
     * first past its end.
     */
    private class Paging {
        var position = 0
            private set

        private var total: Int? = null

        /** True before anything has been read, which is what makes the first page unconditional. */
        val hasMore: Boolean
            get() = total?.let { position < it } ?: true

        /**
         * Advances by one page.
         *
         * A page that reported nothing ends the paging whatever `total` said: this surface has no
         * `queryState` to detect a window changing underneath, so trusting a total that never
         * arrives is a loop with no way out.
         */
        fun advance(page: CalendarEventQueryResult) {
            position += page.ids.size
            total = if (page.ids.isEmpty()) position else page.total ?: position
        }
    }

    /**
     * The calendars alone, for a window the server will not expand.
     *
     * Worth the round trip on its own: the calendar list is what gives a row its colour and its
     * name, it is not windowed, and a screen scrolled past the horizon still draws the cache.
     */
    private suspend fun refreshCalendarsOnly(
        client: JmapClient,
        session: Session,
        accountId: AccountId,
        accountKey: String,
    ): Int {
        val batch = request(session)
        val handle = batch.add(CalendarGet(accountId))
        val calendarRows = client.send(batch).result(handle).list.map { it.toEntity(accountKey) }

        database.withTransaction {
            val stale =
                database.calendars().forAccount(accountKey).map { it.uid } -
                    calendarRows.map { it.uid }.toSet()

            database.calendars().upsert(calendarRows)
            database.calendars().delete(stale)
        }

        return 1
    }

    /**
     * Creates an event and writes what the server made of it.
     *
     * Server first, unlike an archive or a star. The id the server assigns is what every later edit
     * has to be addressed to, so an optimistic row would be a row the user could tap before it
     * existed — the same reason `LabelRepository.create` does not guess one either. What follows
     * the answer *is* optimistic: the row and, for a one-off, its days are written from the draft
     * rather than by re-reading the event back.
     */
    override suspend fun create(calendarKey: String, draft: EventDraft): CalendarWriteResult {
        val calendar =
            database.calendars().byUid(calendarKey)
                ?: return CalendarWriteResult.Rejected(
                    "notFound",
                    "That calendar is not on this device.",
                )
        val client = clients.current() ?: return CalendarWriteResult.NoCalendarAccount

        return write {
            val session = client.session()
            val accountId =
                session.primaryCalendarAccount ?: return@write CalendarWriteResult.NoCalendarAccount

            val request = request(session)
            val handle =
                request.add(
                    CalendarEventSet(
                        accountId = accountId,
                        create =
                            mapOf(CREATION_ID to draft.toNewEvent(CalendarId(calendar.calendarId))),
                    )
                )

            val result = client.send(request).result(handle)

            result.notCreated[CREATION_ID]?.let {
                return@write CalendarWriteResult.Rejected(it.type, it.readable())
            }

            val created =
                result.created[CREATION_ID]
                    ?: return@write CalendarWriteResult.Rejected(
                        "serverFail",
                        "The server created no event and gave no reason.",
                    )

            val eventKey = StoreKey.objectKey(calendar.accountKey, created.id.value)
            val row =
                draft.toEntity(
                    uid = eventKey,
                    accountKey = calendar.accountKey,
                    eventId = created.id.value,
                    calendarKey = calendar.uid,
                    calendarId = calendar.calendarId,
                    calendarZone = calendar.timeZone,
                    eventUid = created.uid,
                    // Read from the answer, never inferred from the rule that
                    // was sent: a rule the server cannot convert is stored
                    // verbatim and expands to one occurrence, so a create
                    // carrying a recurrence can come back not recurring.
                    isRecurring = created.isRecurring,
                    sequence = created.sequence,
                )

            database.withTransaction {
                database.calendarEvents().upsertEvents(listOf(row))

                // A one-off is placed here and now, which is what makes it
                // appear without a round trip. A recurring one cannot be: where
                // its occurrences land is the server's answer, and the refresh
                // below is how that is asked.
                if (!created.isRecurring) {
                    database.calendarEvents().upsertOccurrences(row.placeFromItself())
                }
            }

            if (created.isRecurring) refreshLastWindow()

            CalendarWriteResult.Applied(eventKey, created.id)
        }
    }

    /**
     * Changes an event, on the phone first and then on the server.
     *
     * Local first, like every other mutation in this app — the row has to change under the finger.
     * **Unlike** the mail actions, the local write is taken back when the server refuses or does
     * not answer, and that is a deliberate difference rather than an inconsistency: a failed
     * archive leaves a snackbar offering the way back and a durable queue that will retry it, and a
     * calendar edit in this milestone has neither. With no queue and no undo, keeping the change
     * would mean the phone showing something that is never going to become true, with nothing on
     * screen able to put it right.
     */
    override suspend fun update(eventKey: String, draft: EventDraft): CalendarWriteResult {
        val existing =
            database.calendarEvents().byUid(eventKey)
                ?: return CalendarWriteResult.Rejected(
                    "notFound",
                    "That event is not on this device.",
                )
        val client = clients.current() ?: return CalendarWriteResult.NoCalendarAccount

        val previous = database.calendarEvents().occurrencesOf(eventKey)
        val updated =
            draft.toEntity(
                uid = existing.uid,
                accountKey = existing.accountKey,
                eventId = existing.eventId,
                calendarKey = existing.calendarKey,
                calendarId = existing.calendarId,
                calendarZone = database.calendars().byUid(existing.calendarKey)?.timeZone,
                eventUid = existing.eventUid,
                isRecurring = existing.isRecurring,
                sequence = existing.sequence,
                recurrenceOverrides = existing.recurrenceOverrides,
            )

        suspend fun restore() {
            database.withTransaction {
                database.calendarEvents().upsertEvents(listOf(existing))
                database.calendarEvents().clearOccurrencesOf(eventKey)
                database.calendarEvents().upsertOccurrences(previous)
            }
        }

        database.withTransaction {
            database.calendarEvents().upsertEvents(listOf(updated))

            // The title and the place come off the series row, so a rename shows
            // on every occurrence the moment this commits. Only the *times* of a
            // recurring series need the server, which is what the refresh below
            // is for.
            if (!existing.isRecurring) {
                database.calendarEvents().clearOccurrencesOf(eventKey)
                database.calendarEvents().upsertOccurrences(updated.placeFromItself())
            }
        }

        return write(onFailure = { restore() }) {
            val session = client.session()
            val accountId =
                session.primaryCalendarAccount ?: return@write CalendarWriteResult.NoCalendarAccount

            val request = request(session)
            val handle =
                request.add(
                    CalendarEventSet(
                        accountId = accountId,
                        update = mapOf(CalendarEventId(existing.eventId) to draft.toPatch()),
                    )
                )

            val result = client.send(request).result(handle)

            result.notUpdated.values.firstOrNull()?.let {
                restore()

                return@write CalendarWriteResult.Rejected(it.type, it.readable())
            }

            if (existing.isRecurring) refreshLastWindow()

            CalendarWriteResult.Applied(eventKey, CalendarEventId(existing.eventId))
        }
    }

    /**
     * Deletes an event. The event, not one occurrence of it.
     *
     * A real delete, unlike `Email/set` `destroy` which moves a message to Trash — there is no
     * calendar bin to recover from, so whatever offers this needs its own confirmation rather than
     * borrowing mail's undo. Cancelling a single occurrence is a different operation entirely: an
     * `excluded` override on the series, because there is no id for one occurrence.
     */
    override suspend fun delete(eventKey: String): CalendarWriteResult {
        val existing =
            database.calendarEvents().byUid(eventKey)
                ?: return CalendarWriteResult.Rejected(
                    "notFound",
                    "That event is not on this device.",
                )
        val client = clients.current() ?: return CalendarWriteResult.NoCalendarAccount

        val previous = database.calendarEvents().occurrencesOf(eventKey)

        suspend fun restore() {
            database.withTransaction {
                database.calendarEvents().upsertEvents(listOf(existing))
                database.calendarEvents().upsertOccurrences(previous)
            }
        }

        database.withTransaction {
            database.calendarEvents().clearOccurrencesOf(eventKey)
            database.calendarEvents().deleteEvent(eventKey)
        }

        return write(onFailure = { restore() }) {
            val session = client.session()
            val accountId =
                session.primaryCalendarAccount ?: return@write CalendarWriteResult.NoCalendarAccount

            val request = request(session)
            val handle =
                request.add(
                    CalendarEventSet(
                        accountId = accountId,
                        destroy = listOf(CalendarEventId(existing.eventId)),
                    )
                )

            val result = client.send(request).result(handle)

            result.notDestroyed.values.firstOrNull()?.let {
                restore()

                return@write CalendarWriteResult.Rejected(it.type, it.readable())
            }

            CalendarWriteResult.Applied(eventKey, CalendarEventId(existing.eventId))
        }
    }

    /**
     * Runs a write and names its failure.
     *
     * The distinction is the one the mail side draws and it is load-bearing: a transport failure is
     * "nothing answered" and a rejection is an answer. Calendar writes deliberately do **not** join
     * the mail outbox in this milestone — a queue replaying event edits needs its own conflict
     * story, and this surface has no `ifInState` to build one on.
     */
    private suspend fun write(
        onFailure: suspend () -> Unit = {},
        block: suspend () -> CalendarWriteResult,
    ): CalendarWriteResult =
        try {
            block()
        } catch (offline: IOException) {
            onFailure()
            CalendarWriteResult.Unreachable(host = null)
        } catch (unreachable: JmapError.Unreachable) {
            onFailure()
            CalendarWriteResult.Unreachable(unreachable.host)
        } catch (rejected: Exception) {
            onFailure()
            CalendarWriteResult.Rejected(
                (rejected as? JmapError.MethodFailed)?.type ?: "serverFail",
                rejected.message ?: "The server refused the change.",
            )
        }

    /**
     * Whether the connected server nominates an account for calendars.
     *
     * False on anything that fails, including a transport error, because this only ever *adds* to
     * what the cache already said — see [isAvailable]. Throwing here, or reporting "unknown", would
     * turn a missing network into a question the caller cannot draw.
     */
    private suspend fun hasCalendarAccount(): Boolean =
        try {
            clients.current()?.session()?.primaryCalendarAccount != null
        } catch (unreachable: IOException) {
            false
        } catch (refused: Exception) {
            false
        }

    /** Re-asks the server where a recurring series now lands, over the range on screen. */
    private suspend fun refreshLastWindow() {
        lastRefreshed?.let { refresh(it) }
    }

    /**
     * Places one occurrence inside [window], on the days its **own** start and duration cover.
     *
     * Which days those are is read off the object the server answered for this occurrence id and
     * from nothing else: its `start` is already resolved against whatever override moved it, so
     * there is no rule to expand, no override map to interpret, and no timestamp to lift out of an
     * id. [series] is the row it hangs off — [CalendarEvent.seriesId]'s object, never a prefix of
     * the occurrence id — and an occurrence whose series did not come back is dropped rather than
     * orphaned, because nothing joins to a series row that is not there.
     *
     * The day an occurrence lands on is its own wall clock, never the device offset the window was
     * converted by. An all-day event dated the eighth belongs to the eighth in every zone, which is
     * the whole reason all-day events exist.
     */
    private fun place(
        occurrence: CalendarEvent,
        series: CalendarEvent?,
        eventKey: String,
        accountKey: String,
        window: CalendarWindow,
        calendarZone: String?,
    ): List<CalendarOccurrenceEntity> {
        if (series == null) return emptyList()

        val start = occurrence.start?.asLocalDateTime() ?: return emptyList()
        val calendarKey = StoreKey.objectKey(accountKey, series.calendarId?.value.orEmpty())

        return daysSpanned(start, occurrence.duration)
            .filter { it in window }
            .map { day ->
                CalendarOccurrenceEntity(
                    // The start is part of the key, not decoration: two occurrences
                    // of one series can share a day -- an hourly meeting, or an
                    // override moved onto a day that already had one -- and a key of
                    // event-and-date would silently keep whichever was written last.
                    uid = StoreKey.occurrence(eventKey, day.toString(), start.toWire()),
                    eventKey = eventKey,
                    accountKey = accountKey,
                    calendarKey = calendarKey,
                    date = day.toString(),
                    startLocal = start.toWire(),
                    endLocal = start.plusWireDuration(occurrence.duration)?.toWire(),
                    // The event's own zone, or none. **Never the calendar's**, and
                    // that changed: the fallback used to be `?: calendarZone`,
                    // which read as harmless and made a real state unreachable.
                    //
                    // An all-day event has no zone on the wire and must be given
                    // none, or a reader in another zone resolves
                    // midnight-in-Berlin to the previous evening -- the whole
                    // thing all-day events exist to not do. That much the
                    // `takeUnless` already did. But a **floating** event also has
                    // no zone and is not all-day: "9am wherever you are", which
                    // plMail stores as a bare wall clock. Inheriting the
                    // calendar's zone there labelled it `Europe/Berlin` on a
                    // phone in Tokyo, and `EventDetailScreen` draws its
                    // "floating" caption on `zoneId == null` -- so the fallback
                    // did not just mislabel one event, it made that branch and
                    // its string dead for every calendar that has a zone, which
                    // is all of them.
                    //
                    // Safe because the server is explicit: probed on 8002,
                    // `CalendarEvent/get` publishes `timeZone` on every timed
                    // event and omits it only where `showWithoutTime` is set. So
                    // the fallback never stood in for an ordinary event's zone;
                    // the only thing it ever answered for was a floating one.
                    zoneId = occurrence.timeZone.takeUnless { occurrence.showWithoutTime },
                    isAllDay = occurrence.showWithoutTime,
                    // Only when this occurrence's title is not the series'. Storing
                    // it unconditionally would work until a rename on the web left
                    // every cached occurrence carrying the old name until its own
                    // window was refreshed -- the reason the colour is a join too.
                    titleOverride = occurrence.title?.takeIf { it != series.title },
                )
            }
    }

    private fun query(
        accountId: AccountId,
        after: String,
        before: String,
        position: Int = 0,
        limit: Int? = null,
        expandRecurrences: Boolean = false,
    ) =
        CalendarEventQuery(
            accountId = accountId,
            filter = CalendarEventFilter(after = after, before = before),
            position = position,
            limit = limit,
            expandRecurrences = expandRecurrences,
        )

    private fun hydrate(accountId: AccountId, ids: ResultReference, properties: List<String>) =
        CalendarEventGet.byReference(
            accountId = accountId,
            queryReference = ids,
            properties = properties,
        )

    /**
     * A request declaring the calendars capability, and nothing else.
     *
     * `USING_CALENDARS` behaves **oppositely** to the push URN: push is advertised in the session
     * and fails the whole request if it is declared, calendars is advertised and refuses the call
     * if it is not. Mail is deliberately absent — a calendar request needs nothing from it, and
     * declaring a capability a request does not use is how a client breaks on an instance where
     * that one is switched off.
     */
    private fun request(session: Session): RequestBuilder =
        RequestBuilder(
            using = Capability.USING_CALENDARS,
            maxCallsInRequest = session.core.maxCallsInRequest,
        )

    private companion object {
        const val CREATION_ID = "c1"

        /** Enough rows to scroll a season without holding a year in memory. */
        const val AGENDA_LIMIT = 500

        /** The server's own name for a window it will not expand occurrences over. */
        const val CANNOT_CALCULATE_OCCURRENCES = "cannotCalculateOccurrences"

        /**
         * Exactly the properties the cache keeps of a **series**.
         *
         * Asked for by name rather than taking the whole object: an event carries arbitrary
         * JSCalendar — participants, alerts, links, whatever an import brought — and a month of a
         * busy calendar is a lot of JSON to move over a domestic uplink in order to store none of
         * it. `recurrenceOverrides` is on the list because it is what a per-occurrence edit patches
         * — cancelling one occurrence is an `excluded` override on the series — and a patch built
         * without the map it is amending would drop every other occurrence's override.
         *
         * What a *day* needs is deliberately a shorter list; see
         * `CalendarEventGet.OCCURRENCE_PROPERTIES`.
         */
        val CACHED_PROPERTIES =
            listOf(
                "id",
                "uid",
                "calendarId",
                "title",
                "description",
                "start",
                "duration",
                "timeZone",
                "showWithoutTime",
                "status",
                "isRecurring",
                "sequence",
                "locations",
                "recurrenceOverrides",
            )
    }
}

/**
 * The wire's LocalDateTime spelling: `2026-08-03T10:00:00`, no offset and no trailing `Z`.
 *
 * An explicit pattern rather than `ISO_LOCAL_DATE_TIME`, which drops the seconds when they are zero
 * — `2026-08-03T10:00` — while the server's validator names the format it wants down to the second.
 */
private val WIRE_LOCAL_DATE_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

internal fun LocalDateTime.toWire(): String = format(WIRE_LOCAL_DATE_TIME)

/**
 * A local day's first moment, as the **UTC** wall clock a `CalendarEvent/query` window is read in.
 *
 * **JMAP calendar query windows are UTC on the wire.** `after` and `before` are JSCalendar
 * LocalDateTimes with no offset and no `Z`, which looks like an invitation to send the device's own
 * wall clock — and the server's parse is the authority on what they mean:
 * `App\Jmap\Query\CalendarEventQueryRunner::run()` puts each through `utcDate()`, which reads the
 * string and then `setTimezone('UTC')`, and matches it against occurrence spans stored as UTC
 * instants. So a naive local window is a window shifted by the device's offset.
 *
 * Found on a device rather than in a test, on 2026-08-06: an event created at 01:00 Europe/Berlin
 * is stored at 23:00Z the day before, the agenda then asked for `after: 2026-08-06T00:00:00`, the
 * server honestly answered that nothing was on that day, and the refresh's reconcile swept the
 * just-saved event out of the cache. Every event between midnight and the offset vanished from the
 * app moments after being created while remaining on the server.
 *
 * From the *zoned* boundary rather than by adding twenty-four hours: a local day is 23 or 25 hours
 * long twice a year, and a fixed-duration edge puts the evening of a DST Sunday on the Monday.
 */
internal fun LocalDate.startOfDayUtc(zone: ZoneId): LocalDateTime =
    atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()

/** Tolerant on the way in: the server writes seconds, but nothing here depends on it doing so. */
private fun String.asLocalDateTime(): LocalDateTime? = runCatching {
    LocalDateTime.parse(this)
}
    .getOrNull()

/**
 * ISO 8601, and quietly null when it is a shape `Duration` cannot hold.
 *
 * `Duration.parse` takes `PT15M` and `P1D` but not `P1M` — a month is not a fixed number of
 * seconds. An event carrying one gets an occurrence with no end rather than no occurrence: what
 * time it starts is what a day view needs, and refusing to place it would lose the event over a
 * detail.
 */
private fun LocalDateTime.plusWireDuration(iso: String?): LocalDateTime? = iso?.let {
    runCatching { plus(Duration.parse(it)) }.getOrNull()
}

/**
 * Every day one occurrence occupies.
 *
 * An end landing exactly on midnight belongs to the day before it: a `P1D` all-day event starting
 * at `2026-08-08T00:00:00` ends at `2026-08-09T00:00:00` and is on one day, not two.
 */
private fun daysSpanned(start: LocalDateTime, duration: String?): List<LocalDate> {
    val end = start.plusWireDuration(duration) ?: start
    val last =
        if (end > start && end.toLocalTime() == LocalTime.MIDNIGHT) end.toLocalDate().minusDays(1)
        else end.toLocalDate()

    return generateSequence(start.toLocalDate()) { it.plusDays(1) }
        .takeWhile { it <= maxOf(last, start.toLocalDate()) }
        .toList()
}

private operator fun CalendarWindow.contains(day: LocalDate): Boolean = day >= from && day < to

/**
 * Whether the server is holding this event's start as a wall clock rather than as an instant.
 *
 * A `CalendarEvent/get` with no `timeZone` is exactly that and is not the ambiguity it looks like:
 * `CalendarEventWriter` publishes the property only when the stored zone is non-null, nulls the
 * zone of every all-day event, and resolves a null one against UTC — so an event that comes back
 * without a zone was stored as its own wall clock, whatever the calendar's zone is. (What *display*
 * does with an absent zone is a different question, and `CalendarEventEntity.timeZone` answers it
 * the other way round on purpose.)
 *
 * `showWithoutTime` is checked as well rather than trusted to imply it, because the one thing that
 * must never happen here is an all-day event being asked about in a converted window.
 */
private val CalendarEvent.isFloating: Boolean
    get() = timeZone == null || showWithoutTime

private fun Calendar.toEntity(accountKey: String) =
    CalendarEntity(
        uid = StoreKey.objectKey(accountKey, id.value),
        accountKey = accountKey,
        calendarId = id.value,
        name = name,
        color = color,
        sortOrder = sortOrder,
        isVisible = isVisible,
        isDefault = isDefault,
        timeZone = timeZone,
        role = role,
        isSynced = isSynced,
        mayReadItems = myRights.mayReadItems,
        mayAddItems = myRights.mayAddItems,
        mayUpdateAll = myRights.mayUpdateAll,
        mayRemoveItems = myRights.mayRemoveItems,
    )

private fun CalendarEvent.toEntity(accountKey: String) =
    CalendarEventEntity(
        uid = StoreKey.objectKey(accountKey, id.value),
        accountKey = accountKey,
        eventId = id.value,
        calendarKey = StoreKey.objectKey(accountKey, calendarId?.value.orEmpty()),
        calendarId = calendarId?.value.orEmpty(),
        eventUid = uid,
        title = title.orEmpty(),
        description = description,
        start = start,
        duration = duration,
        timeZone = timeZone,
        isAllDay = showWithoutTime,
        // The server keeps at most one place, filed under a key of its own
        // choosing -- so the key means nothing and the first value is the place.
        location = locations.values.firstNotNullOfOrNull { it.name },
        status = status,
        isRecurring = isRecurring,
        sequence = sequence,
        recurrenceOverrides =
            recurrenceOverrides
                .takeIf { it.isNotEmpty() }
                ?.let { Json.encodeToString(JsonObject.serializer(), JsonObject(it)) },
    )

/**
 * The days a cached one-off occupies, from the row itself.
 *
 * What makes a create or an edit visible before any refresh. Only ever called for an event the
 * server has said is *not* recurring — for one that is, where the occurrences land is a question
 * only the server can answer.
 */
private fun CalendarEventEntity.placeFromItself(): List<CalendarOccurrenceEntity> {
    val at = start?.asLocalDateTime() ?: return emptyList()

    return daysSpanned(at, duration).map { day ->
        CalendarOccurrenceEntity(
            uid = StoreKey.occurrence(uid, day.toString(), at.toWire()),
            eventKey = uid,
            accountKey = accountKey,
            calendarKey = calendarKey,
            date = day.toString(),
            startLocal = at.toWire(),
            endLocal = at.plusWireDuration(duration)?.toWire(),
            // See the same line in `place`: an all-day occurrence is
            // deliberately zone-less.
            zoneId = timeZone.takeUnless { isAllDay },
            isAllDay = isAllDay,
        )
    }
}

/**
 * `P1D` for an all-day event rather than `PT24H`.
 *
 * `Duration.toString()` only ever produces the time-based spelling, and the wire's all-day events
 * are `P1D`. The two mean the same number of seconds and are not the same value to a server that
 * round-trips what it was given.
 */
private fun EventDraft.wireDuration(): String =
    if (isAllDay) "P${maxOf(1L, duration.toDays())}D" else duration.toString()

private fun EventDraft.toNewEvent(calendarId: CalendarId) =
    NewCalendarEvent(
        calendarId = calendarId,
        title = title,
        start = start.toWire(),
        duration = wireDuration(),
        description = description,
        timeZone = timeZone,
        showWithoutTime = isAllDay,
        location = location,
        status = status,
        recurrenceRule = recurrenceRule,
    )

private fun EventDraft.toPatch(): CalendarEventPatch {
    val draft = this

    return CalendarEventPatch.build {
        title(draft.title)
        description(draft.description)
        start(draft.start.toWire())
        duration(draft.wireDuration())
        showWithoutTime(draft.isAllDay)
        location(draft.location)
        status(draft.status)
        draft.timeZone?.let { timeZone(it) }
    }
}

private fun EventDraft.toEntity(
    uid: String,
    accountKey: String,
    eventId: String,
    calendarKey: String,
    calendarId: String,
    calendarZone: String?,
    eventUid: String?,
    isRecurring: Boolean,
    sequence: Int,
    recurrenceOverrides: String? = null,
) =
    CalendarEventEntity(
        uid = uid,
        accountKey = accountKey,
        eventId = eventId,
        calendarKey = calendarKey,
        calendarId = calendarId,
        eventUid = eventUid,
        title = title,
        description = description,
        start = start.toWire(),
        duration = wireDuration(),
        // The zone as it will be *read* back. Floating comes back as absent too
        // -- a get cannot tell the two apart -- so inheriting the calendar's
        // here is the same answer the next refresh writes.
        timeZone = (timeZone as? EventTimeZone.Zone)?.name ?: calendarZone,
        isAllDay = isAllDay,
        location = location,
        status = status,
        isRecurring = isRecurring,
        sequence = sequence,
        recurrenceOverrides = recurrenceOverrides,
    )

/**
 * What to show somebody about a refusal.
 *
 * The description where the server gave one, the refused properties where it named them, and the
 * bare type otherwise — several of this surface's refusals carry nothing else, and
 * "invalidArguments" on screen is at least a string somebody can search for.
 */
private fun SetError.readable(): String =
    description
        ?: properties
            ?.takeIf { it.isNotEmpty() }
            ?.let { "The server refused: ${it.joinToString()}" }
        ?: type
