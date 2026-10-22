package de.plmail.feature.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.CalendarRepository
import de.plmail.core.data.ContactsRepository
import de.plmail.jmap.methods.NoteLink
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the picker can offer, each already shaped as the link it would become. */
data class LinkCandidates(
    val events: List<NoteLink> = emptyList(),
    val contacts: List<NoteLink> = emptyList(),
    val loading: Boolean = true,
)

/**
 * Loads the things a note can link to: upcoming events from the calendar cache and the address
 * book. Each source is best effort — a server without contacts just has an empty tab — so one
 * failing never empties the other.
 */
@HiltViewModel
class LinkPickerViewModel
@Inject
constructor(
    private val calendar: CalendarRepository,
    private val contacts: ContactsRepository,
) : ViewModel() {

    private val _candidates = MutableStateFlow(LinkCandidates())
    val candidates: StateFlow<LinkCandidates> = _candidates

    init {
        viewModelScope.launch {
            val events = runCatching {
                calendar
                    .agenda(LocalDate.now(), 200)
                    .first()
                    .distinctBy { it.eventId }
                    .map { NoteLink(NoteLink.EVENT, it.eventId, "${it.title} · ${it.date}") }
            }
                .getOrDefault(emptyList())
            _candidates.value = _candidates.value.copy(events = events)
        }
        viewModelScope.launch {
            val people = runCatching {
                contacts.list().map {
                    NoteLink(NoteLink.CONTACT, it.id.value, it.displayName.ifBlank { null })
                }
            }
                .getOrDefault(emptyList())
            _candidates.value = _candidates.value.copy(contacts = people, loading = false)
        }
    }
}

/** A dialog to pick an event or contact to link a note to. */
@Composable
internal fun LinkPickerDialog(
    onPick: (NoteLink) -> Unit,
    onDismiss: () -> Unit,
    viewModel: LinkPickerViewModel = hiltViewModel(),
) {
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }

    val tabs =
        listOf(
            stringResource(R.string.notes_link_event) to candidates.events,
            stringResource(R.string.notes_link_contact) to candidates.contacts,
        )
    val shown =
        tabs[tab].second.filter { query.isBlank() || it.title.orEmpty().contains(query, true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.notes_close)) }
        },
        title = { Text(stringResource(R.string.notes_add_link)) },
        text = {
            Column {
                // TabRow rather than PrimaryTabRow, for the material3 this project pins.
                @Suppress("DEPRECATION")
                TabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { index, (label, _) ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.notes_link_search)) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                if (candidates.loading && shown.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                } else if (shown.isEmpty()) {
                    Text(stringResource(R.string.notes_link_none))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(shown, key = { it.type + it.id }) { link ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { onPick(link) }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(
                                    link.title ?: link.id,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
    LaunchedEffect(tab) { query = "" }
}
