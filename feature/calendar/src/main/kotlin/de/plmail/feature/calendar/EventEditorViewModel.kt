package de.plmail.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.CalendarRepository
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorState(
    /** Null until the event has been read; immediately present for a new one. */
    val form: EventFormState? = null,
    /**
     * Which request [form] belongs to.
     *
     * Held because this ViewModel is scoped to the activity and therefore outlives the screen: two
     * editors opened in a row are the same instance, and without this the second would open on the
     * first one's fields.
     */
    val loadedFor: EditorRequest? = null,
    /**
     * Whether this event recurs, as the server derived it.
     *
     * What decides between the repeat dropdown and a read-only line. Read from the cache rather
     * than inferred from a rule, because the two disagree exactly on the imported events a user is
     * most likely to have: a rule plMail cannot convert is stored verbatim and expands to a single
     * occurrence, so an event can carry a rule and not recur.
     */
    val isRecurring: Boolean = false,
    val isSaving: Boolean = false,
    val outcome: WriteOutcome? = null,
)

/**
 * One form, for creating and for changing.
 *
 * A create is **server-first** — the repository does that deliberately, because the id the server
 * assigns is what every later edit has to be addressed to — so the progress this screen shows is a
 * real wait rather than a courtesy.
 */
@HiltViewModel
class EventEditorViewModel
@Inject
constructor(private val calendar: CalendarRepository, private val clock: Clock) : ViewModel() {

    private val _state = MutableStateFlow(EditorState())

    val state: StateFlow<EditorState> = _state.asStateFlow()

    /**
     * Opens the form on [request].
     *
     * An existing event is read from the **series** row rather than from the agenda row that was
     * tapped — see `EventFormState.of`. [defaultCalendarKey] is consulted only for a new event: the
     * calendar an existing event is on is the event's own, and defaulting it would silently propose
     * moving somebody's meeting.
     */
    fun open(request: EditorRequest, defaultCalendarKey: String?) {
        if (_state.value.loadedFor == request) return

        when (request) {
            EditorRequest.New ->
                _state.value =
                    EditorState(
                        form =
                            EventFormState.forNewEvent(clock)
                                .copy(calendarKey = defaultCalendarKey),
                        loadedFor = request,
                    )
            is EditorRequest.Edit -> {
                _state.value = EditorState(loadedFor = request)

                viewModelScope.launch {
                    val event = calendar.event(request.eventKey) ?: return@launch

                    _state.update {
                        it.copy(
                            form = EventFormState.of(event, clock),
                            isRecurring = event.isRecurring,
                        )
                    }
                }
            }
        }
    }

    fun edit(change: (EventFormState) -> EventFormState) {
        _state.update { state -> state.copy(form = state.form?.let(change)) }
    }

    /** Sends the form. [untitled] is the localised word JMAP's refusal of an empty title needs. */
    fun save(untitled: String) {
        val form = _state.value.form ?: return
        val calendarKey = form.calendarKey ?: return
        val request = _state.value.loadedFor ?: return

        if (form.endsBeforeStart || _state.value.isSaving) return

        _state.update { it.copy(isSaving = true, outcome = null) }

        viewModelScope.launch {
            val eventKey = (request as? EditorRequest.Edit)?.eventKey
            val draft = form.toDraft(untitled = untitled, isCreating = eventKey == null)
            val result =
                if (eventKey == null) calendar.create(calendarKey, draft)
                else calendar.update(eventKey, draft)

            _state.update {
                it.copy(isSaving = false, outcome = result.asOutcome(WriteOutcome.Saved))
            }
        }
    }

    /**
     * Deletes the event, series and all.
     *
     * A real delete, unlike mail's Trash: there is no calendar bin to recover from, which is why
     * every caller asks first rather than offering an undo afterwards.
     */
    fun delete(eventKey: String) {
        if (_state.value.isSaving) return

        _state.update { it.copy(isSaving = true, outcome = null) }

        viewModelScope.launch {
            val result = calendar.delete(eventKey)

            _state.update {
                it.copy(isSaving = false, outcome = result.asOutcome(WriteOutcome.Deleted))
            }
        }
    }

    /** Clears a failure the screen has shown, so the next attempt starts from nothing. */
    fun acknowledge() {
        _state.update { it.copy(outcome = null) }
    }
}
