package de.plmail.feature.calendar

import de.plmail.core.data.CalendarWriteResult
import de.plmail.core.data.EventDraft
import de.plmail.core.data.EventEditing
import de.plmail.core.database.CalendarEventEntity
import de.plmail.jmap.protocol.CalendarEventId
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Which form is on the editor, which is the whole of what this ViewModel decides.
 *
 * It is scoped to the activity, so it outlives every screen that shows it, and the guard that stops
 * a recomposition wiping out half-typed edits is the same guard that let a *second* editor open on
 * the first one's fields. Both directions are here because a fix for either one alone is easy and
 * wrong: reload on every call and typing is impossible, reload on nothing and a saved event comes
 * back offered for saving again.
 *
 * Seen on a device on 2026-08-06 — New, a title, Save, New again, and the form still said "Vom
 * Handy erstell", one tap from a duplicate.
 */
// setMain, resetMain and advanceUntilIdle are all experimental and warnings are errors here.
// Opting in once at the class is the alternative to annotating every test that touches a
// viewModelScope.
@OptIn(ExperimentalCoroutinesApi::class)
class EventEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** Fixed, so "the next whole hour" is a time this file can name. */
    private val clock: Clock =
        Clock.fixed(Instant.parse("2026-08-05T10:07:00Z"), ZoneId.of("Europe/Berlin"))

    private val standup =
        CalendarEventEntity(
            uid = "server#10867",
            accountKey = "server",
            eventId = "10867",
            calendarKey = "server#10542",
            calendarId = "10542",
            eventUid = "10867@plmail",
            title = "Team-Standup",
            description = null,
            start = "2026-08-03T10:00:00",
            duration = "PT15M",
            timeZone = "Europe/Berlin",
            isAllDay = false,
            location = null,
            status = "confirmed",
            isRecurring = true,
            sequence = 0,
        )

    @BeforeTest
    fun useTestDispatcher() {
        // viewModelScope is hard-wired to Dispatchers.Main, so it has to be
        // replaced rather than injected.
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun restoreDispatcher() {
        Dispatchers.resetMain()
    }

    /**
     * The defect, in the order it happened on the phone.
     *
     * Nothing about closing is asserted here on purpose: the editor can leave the screen without
     * being told — the system back gesture is handled by `CalendarScreen` — so the guarantee has to
     * be "the next opening is a new one", not "somebody remembered to tidy up".
     */
    @Test
    fun `a second new event opens on an empty form`() = runTest {
        val editing = RecordingEditor()
        val viewModel = EventEditorViewModel(editing, clock)

        viewModel.open(EditorSession(1, EditorRequest.New), defaultCalendarKey = "server#10542")
        viewModel.edit { it.copy(title = "Vom Handy erstellt") }
        viewModel.save(untitled = "Ohne Titel")
        advanceUntilIdle()

        assertEquals(WriteOutcome.Saved, viewModel.state.value.outcome)
        assertEquals(listOf("Vom Handy erstellt"), editing.created.map { it.title })

        viewModel.open(EditorSession(2, EditorRequest.New), defaultCalendarKey = "server#10542")

        assertEquals("", viewModel.state.value.form?.title)
        assertEquals(
            null,
            viewModel.state.value.outcome,
            "and the previous save's outcome went with it, or the new editor closes itself",
        )
    }

    /**
     * The reason the guard exists at all.
     *
     * The screen calls `open` from a `LaunchedEffect` that re-runs whenever the calendar list it
     * also keys on changes — a refresh landing mid-edit does that — so a version of this that
     * reloaded on every call would delete whatever had been typed by the time somebody saved.
     */
    @Test
    fun `reopening the same editor session leaves the edits alone`() = runTest {
        val viewModel = EventEditorViewModel(RecordingEditor(), clock)
        val session = EditorSession(1, EditorRequest.New)

        viewModel.open(session, defaultCalendarKey = "server#10542")
        viewModel.edit { it.copy(title = "Halb getippt") }

        viewModel.open(session, defaultCalendarKey = "server#10542")

        assertEquals("Halb getippt", viewModel.state.value.form?.title)
    }

    /** Two events opened in a row are two forms, which was already true and stays true. */
    @Test
    fun `opening another event replaces the form`() = runTest {
        val editing = RecordingEditor(events = mapOf(standup.uid to standup))
        val viewModel = EventEditorViewModel(editing, clock)

        viewModel.open(EditorSession(1, EditorRequest.Edit(standup.uid)), defaultCalendarKey = null)
        advanceUntilIdle()

        assertEquals("Team-Standup", viewModel.state.value.form?.title)

        viewModel.open(EditorSession(2, EditorRequest.New), defaultCalendarKey = "server#10542")
        advanceUntilIdle()

        val form = assertNotNull(viewModel.state.value.form)

        assertEquals("", form.title)
        assertEquals(
            "13:00",
            form.startTime.toString(),
            "a new event starts at the next whole hour on the device's own clock",
        )
    }

    /**
     * A read that lands after the user has moved on does not answer into the form they are in.
     *
     * The cache read is a suspending round trip, and an editor opened over the top of a slow one is
     * ordinary rather than exotic: the answer belongs to the session that asked for it.
     */
    @Test
    fun `a slow read does not overwrite the editor opened after it`() = runTest {
        val editing = RecordingEditor(events = mapOf(standup.uid to standup))
        val viewModel = EventEditorViewModel(editing, clock)

        viewModel.open(EditorSession(1, EditorRequest.Edit(standup.uid)), defaultCalendarKey = null)
        viewModel.open(EditorSession(2, EditorRequest.New), defaultCalendarKey = "server#10542")

        advanceUntilIdle()

        assertEquals("", viewModel.state.value.form?.title)
    }

    /** The calendar as this screen uses it, and nothing else. See `EventEditing`. */
    private class RecordingEditor(
        private val events: Map<String, CalendarEventEntity> = emptyMap()
    ) : EventEditing {

        val created = mutableListOf<EventDraft>()

        override suspend fun event(eventKey: String): CalendarEventEntity? = events[eventKey]

        override suspend fun create(
            calendarKey: String,
            draft: EventDraft,
        ): CalendarWriteResult {
            created += draft

            return CalendarWriteResult.Applied("server#10999", CalendarEventId("10999"))
        }

        override suspend fun update(eventKey: String, draft: EventDraft): CalendarWriteResult =
            CalendarWriteResult.Applied(eventKey, CalendarEventId("10867"))

        override suspend fun delete(eventKey: String): CalendarWriteResult =
            CalendarWriteResult.Applied(eventKey, CalendarEventId("10867"))
    }
}
