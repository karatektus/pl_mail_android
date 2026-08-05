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
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
            "A window of more than $MAX_DAYS days would cost more than $MAX_DAYS one-day probe " +
                "queries to place its recurring events. Refresh what is on screen."
        }
    }

    val days: List<LocalDate>
        get() = generateSequence(from) { it.plusDays(1) }.takeWhile { it < to }.toList()

    /** The wire's inclusive lower bound: a LocalDateTime, no offset and no trailing `Z`. */
    internal val after: String
        get() = from.atStartOfDay().toWire()

    /** The wire's exclusive upper bound. */
    internal val before: String
        get() = to.atStartOfDay().toWire()

    companion object {
        /**
         * A year and a day.
         *
         * Not a performance cap so much as an honesty one: every day in a window costs a probe
         * query once the window holds a recurring event, so a caller asking for a decade is asking
         * for thousands of method calls against a machine that advertises four concurrent requests.
         * A calendar shows a month.
         */
        const val MAX_DAYS = 366L

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
        /** Round trips spent. The number that says the day probes were batched, not fanned out. */
        val requests: Int,
        /**
         * Whether the server may have answered part of this window from a partial index.
         *
         * True does not mean anything is missing; it means the client cannot promise nothing is,
         * which is a different sentence and the only honest one available. See
         * `CalendarRepository.mayBeOutsideHorizon`.
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
 * Recurring events are placed by asking the server which days they fall on, one query per day,
 * batched. That is deliberately more traffic than expanding the rule on the device would cost:
 * client-side expansion is forbidden by the client specification, and the reason is that the phone
 * and the web UI would then disagree at a DST boundary — the same event, an hour apart, on two
 * screens of the same product. `docs/SERVER_REQUESTS.md` carries the ask that would make one query
 * do it.
 */
@Singleton
class CalendarRepository
@Inject
constructor(
    private val database: PlMailDatabase,
    private val clients: AccountClients,
    private val credentials: CredentialStore,
    private val clock: Clock,
) {

    /**
     * The window most recently refreshed.
     *
     * Held because a write involving recurrence has to re-ask the server where the occurrences
     * landed, and the range worth re-asking about is the one on screen. Reconstructing them locally
     * from a rule is the one thing this class may not do.
     */
    @Volatile private var lastRefreshed: CalendarWindow? = null

    /** Every calendar, invisible ones included — see `CalendarDao.observeAll`. */
    fun calendars(): Flow<List<CalendarEntity>> = database.calendars().observeAll()

    /** Everything from [from] onwards, earliest first. The agenda. */
    fun agenda(from: LocalDate, limit: Int = AGENDA_LIMIT): Flow<List<AgendaRow>> =
        database.calendarEvents().observeAgenda(from.toString(), limit)

    /** Everything inside one window, for a week or a month grid. */
    fun occurrences(window: CalendarWindow): Flow<List<AgendaRow>> =
        database.calendarEvents().observeBetween(window.from.toString(), window.to.toString())

    /**
     * Re-runs the window and reconciles the cache to the answer.
     *
     * Four steps, and for a window with no recurring events in it, one round trip:
     * 1. `Calendar/get` and the first page of `CalendarEvent/query`, back-referenced into one
     *    `CalendarEvent/get`, in a single request.
     * 2. Further query pages while the reported `total` says there are more, chunked by the
     *    account's `maxEventsInGet` — **100**, not core's `maxObjectsInGet` of 500.
     * 3. One-day probe queries, for the recurring series only, batched into a request each.
     * 4. One transaction: replace the calendars, upsert the series, replace the window's
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

        var requests = 0

        val first = request(session)
        val calendarsHandle = first.add(CalendarGet(accountId))
        val queryHandle = first.add(query(accountId, window.after, window.before, 0, pageSize))
        val eventsHandle = first.add(hydrate(accountId, queryHandle.reference("/ids")))

        val answers = client.send(first).also { requests++ }
        val calendars = answers.result(calendarsHandle).list
        val firstPage = answers.result(queryHandle)
        val events = answers.result(eventsHandle).list.toMutableList()

        var position = firstPage.ids.size
        val total = firstPage.total ?: position

        // Paged rather than asked for in one go, because the get is capped at
        // a hundred ids and a back-referenced get handed three hundred is
        // refused outright -- the refusal being of the whole call, so a busy
        // month would draw nothing rather than drawing its first hundred.
        while (position < total) {
            val page = request(session)
            val pageQuery =
                page.add(query(accountId, window.after, window.before, position, pageSize))
            val pageGet = page.add(hydrate(accountId, pageQuery.reference("/ids")))

            val paged = client.send(page).also { requests++ }
            val ids = paged.result(pageQuery).ids

            events += paged.result(pageGet).list

            if (ids.isEmpty()) break

            position += ids.size
        }

        // Only when there is something recurring to place. A month of one-off
        // appointments has to cost the one request above and no more; spending
        // thirty-one queries to learn what `start` already said would be the
        // whole cost of this design with none of its reason.
        val recurring = events.filter { it.isRecurring }.map { it.id }.toSet()
        val membership =
            if (recurring.isEmpty()) emptyMap()
            else probeDays(client, session, accountId, window, recurring) { requests++ }

        val calendarRows = calendars.map { it.toEntity(accountKey) }
        val zones = calendars.associate { it.id.value to it.timeZone }
        val eventRows = events.map { it.toEntity(accountKey) }

        val occurrences = events.flatMap { event ->
            placeSeries(
                event = event,
                accountKey = accountKey,
                window = window,
                // The event's own zone, then the calendar's. A get cannot
                // tell an absent zone from an explicit null, so a genuinely
                // floating event inherits here -- see
                // `CalendarEventEntity.timeZone`.
                zone = event.timeZone ?: event.calendarId?.let { zones[it.value] },
                days = if (event.isRecurring) membership[event.id].orEmpty() else null,
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
            database
                .calendarEvents()
                .clearOccurrencesBetween(window.from.toString(), window.to.toString())
            database.calendarEvents().upsertOccurrences(occurrences)

            database.calendarEvents().deleteUnplacedEvents()
        }

        lastRefreshed = window

        return CalendarRefresh.Refreshed(
            events = eventRows.size,
            occurrences = occurrences.size,
            requests = requests,
            mayBeIncomplete = window.mayBeOutsideHorizon(),
            horizon = limits?.materialisedHorizon ?: MaterialisedHorizon(),
        )
    }

    /**
     * Which days each recurring series actually falls on, asked one day at a time.
     *
     * The one thing this client may not do is expand a recurrence rule, so day membership is the
     * server's answer: a one-day window either returns the series or it does not. Verified against
     * the running stack on 2026-08-05 — a Mon/Wed/Fri weekly event comes back in the Friday and the
     * Monday windows and not in the Thursday one.
     *
     * Batched at one call short of the session's `maxCallsInRequest` rather than exactly at it. The
     * spare slot is not politeness: a request sitting on the ceiling is refused *entirely* the day
     * anything else has to travel with it, and the refusal is of the whole batch rather than of the
     * one extra call.
     */
    private suspend fun probeDays(
        client: JmapClient,
        session: Session,
        accountId: AccountId,
        window: CalendarWindow,
        wanted: Set<CalendarEventId>,
        onRequest: () -> Unit,
    ): Map<CalendarEventId, Set<LocalDate>> {
        val perRequest = (session.core.maxCallsInRequest - 1).coerceAtLeast(1)
        val membership = mutableMapOf<CalendarEventId, MutableSet<LocalDate>>()

        window.days.chunked(perRequest).forEach { chunk ->
            val batch = request(session)
            val handles = chunk.map { day ->
                day to
                    batch.add(
                        query(
                            accountId = accountId,
                            after = day.atStartOfDay().toWire(),
                            before = day.plusDays(1).atStartOfDay().toWire(),
                        )
                    )
            }

            val answers = client.send(batch)

            onRequest()

            handles.forEach { (day, handle) ->
                answers.result(handle).ids.forEach { id ->
                    if (id in wanted) membership.getOrPut(id) { mutableSetOf() } += day
                }
            }
        }

        return membership
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
    suspend fun create(calendarKey: String, draft: EventDraft): CalendarWriteResult {
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
    suspend fun update(eventKey: String, draft: EventDraft): CalendarWriteResult {
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
    suspend fun delete(eventKey: String): CalendarWriteResult {
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

    /** Re-asks the server where a recurring series now lands, over the range on screen. */
    private suspend fun refreshLastWindow() {
        lastRefreshed?.let { refresh(it) }
    }

    /**
     * Places one series' occurrences inside [window].
     *
     * [days] is null for a one-off — its days come from its own `start` and `duration`, which is
     * reading published data rather than expanding anything — and is the server's answer for a
     * recurring series.
     */
    private fun placeSeries(
        event: CalendarEvent,
        accountKey: String,
        window: CalendarWindow,
        zone: String?,
        days: Set<LocalDate>?,
    ): List<CalendarOccurrenceEntity> {
        val start = event.start?.asLocalDateTime() ?: return emptyList()
        val eventKey = StoreKey.objectKey(accountKey, event.id.value)
        val calendarKey = StoreKey.objectKey(accountKey, event.calendarId?.value.orEmpty())

        fun row(day: LocalDate, at: LocalDateTime, duration: String?, title: String?) =
            CalendarOccurrenceEntity(
                uid = StoreKey.occurrence(eventKey, day.toString()),
                eventKey = eventKey,
                accountKey = accountKey,
                calendarKey = calendarKey,
                date = day.toString(),
                startLocal = at.toWire(),
                endLocal = at.plusWireDuration(duration)?.toWire(),
                // An all-day event has no zone on the wire and is given none
                // here either. Inheriting the calendar's would let a reader in
                // another zone resolve midnight-in-Berlin to the previous
                // evening -- which is the whole thing all-day events exist to
                // not do.
                zoneId = zone.takeUnless { event.showWithoutTime },
                isAllDay = event.showWithoutTime,
                titleOverride = title,
            )

        if (days == null) {
            return daysSpanned(start, event.duration)
                .filter { it in window }
                .map {
                    row(it, start, event.duration, title = null)
                }
        }

        val overrides = Overrides.of(event.recurrenceOverrides)

        return days.sorted().mapNotNull { day ->
            val resolved = overrides.resolve(day, start.toLocalTime()) ?: return@mapNotNull null

            row(day, resolved.at, resolved.duration ?: event.duration, resolved.title)
        }
    }

    /**
     * Whether the client can promise this window is complete.
     *
     * It cannot say more than "maybe not", and that is the server's shape rather than a shortcut:
     * `materialisedHorizon` is published as PHP relative-date expressions — `-1 year`, `+2 years` —
     * which are opaque strings the client is forbidden from parsing. So this is the client's *own*
     * line, drawn conservatively at a year either side of today, and the server's words travel
     * beside it so the UI can quote those rather than this constant.
     *
     * The direction of the error is deliberate. On this server the future horizon is two years, so
     * a window in the second year is flagged as possibly-incomplete when it is in fact complete —
     * and saying "there may be more" about a full month is a smaller lie than saying nothing about
     * an empty one.
     */
    private fun CalendarWindow.mayBeOutsideHorizon(): Boolean {
        val today = LocalDate.now(clock)

        return from < today.minusDays(GUARANTEED_DAYS) || to > today.plusDays(GUARANTEED_DAYS)
    }

    private fun query(
        accountId: AccountId,
        after: String,
        before: String,
        position: Int = 0,
        limit: Int? = null,
    ) =
        CalendarEventQuery(
            accountId = accountId,
            filter = CalendarEventFilter(after = after, before = before),
            position = position,
            limit = limit,
        )

    private fun hydrate(accountId: AccountId, ids: ResultReference) =
        CalendarEventGet.byReference(
            accountId = accountId,
            queryReference = ids,
            properties = CACHED_PROPERTIES,
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

        /** See `mayBeOutsideHorizon`. The client's own conservative line, not the server's. */
        const val GUARANTEED_DAYS = 365L

        /**
         * Exactly the properties the cache keeps.
         *
         * Asked for by name rather than taking the whole object: an event carries arbitrary
         * JSCalendar — participants, alerts, links, whatever an import brought — and a month of a
         * busy calendar is a lot of JSON to move over a domestic uplink in order to store none of
         * it. `recurrenceOverrides` is on the list because it is what says an occurrence moved or
         * was cancelled, which is the one thing a day-probe answer cannot say on its own.
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
 * The overrides of one series, indexed the two ways a day can be asked about.
 *
 * An override is keyed by the occurrence's **original** start and may carry a `start` that moves it
 * — possibly onto a different day. So a day matches either because an override was keyed on it, or
 * because an override moved an occurrence onto it, and those answer different questions about the
 * same day. Reading these is reading published data; nothing here derives an occurrence the server
 * did not report.
 */
private class Overrides(
    private val byOriginalDate: Map<LocalDate, JsonObject>,
    private val movedOntoDate: Map<LocalDate, JsonObject>,
) {

    /** Where this day's occurrence actually is, or null when there is not one. */
    fun resolve(day: LocalDate, baseTime: LocalTime): Resolved? {
        byOriginalDate[day]?.let { keyed ->
            // `{"excluded": true}` is the only way to cancel one occurrence of a
            // series, and it round-trips. The server also drops an excluded
            // occurrence from the query, so this is the second line of defence
            // -- and the one that matters after a local write, when the cache
            // holds the new override and no refresh has run yet.
            if (keyed.excluded) return null

            val moved = keyed.startAt

            // Moved onto a different day. This day has nothing on it; the day it
            // moved to has its own probe answer and picks it up below.
            if (moved != null && moved.toLocalDate() != day) return null

            return Resolved(moved ?: day.atTime(baseTime), keyed.overriddenDuration, keyed.title)
        }

        movedOntoDate[day]?.let { arrived ->
            return Resolved(
                arrived.startAt ?: day.atTime(baseTime),
                arrived.overriddenDuration,
                arrived.title,
            )
        }

        return Resolved(day.atTime(baseTime), duration = null, title = null)
    }

    data class Resolved(val at: LocalDateTime, val duration: String?, val title: String?)

    companion object {
        fun of(raw: Map<String, JsonObject>): Overrides {
            val byOriginal = mutableMapOf<LocalDate, JsonObject>()
            val movedOnto = mutableMapOf<LocalDate, JsonObject>()

            raw.forEach { (recurrenceId, patch) ->
                val original = recurrenceId.asLocalDateTime() ?: return@forEach

                byOriginal[original.toLocalDate()] = patch

                patch.startAt
                    ?.takeIf { it.toLocalDate() != original.toLocalDate() }
                    ?.let {
                        movedOnto[it.toLocalDate()] = patch
                    }
            }

            return Overrides(byOriginal, movedOnto)
        }
    }
}

private val JsonObject.excluded: Boolean
    get() = (this["excluded"] as? JsonPrimitive)?.booleanOrNull == true

private val JsonObject.startAt: LocalDateTime?
    get() = (this["start"] as? JsonPrimitive)?.content?.asLocalDateTime()

private val JsonObject.overriddenDuration: String?
    get() = (this["duration"] as? JsonPrimitive)?.content

private val JsonObject.title: String?
    get() = (this["title"] as? JsonPrimitive)?.content

/**
 * The wire's LocalDateTime spelling: `2026-08-03T10:00:00`, no offset and no trailing `Z`.
 *
 * An explicit pattern rather than `ISO_LOCAL_DATE_TIME`, which drops the seconds when they are zero
 * — `2026-08-03T10:00` — while the server's validator names the format it wants down to the second.
 */
private val WIRE_LOCAL_DATE_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

internal fun LocalDateTime.toWire(): String = format(WIRE_LOCAL_DATE_TIME)

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
            uid = StoreKey.occurrence(uid, day.toString()),
            eventKey = uid,
            accountKey = accountKey,
            calendarKey = calendarKey,
            date = day.toString(),
            startLocal = at.toWire(),
            endLocal = at.plusWireDuration(duration)?.toWire(),
            // See the same line in `placeSeries`: an all-day occurrence is
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
