package de.plmail.feature.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.jmap.methods.NoteDraft
import de.plmail.jmap.methods.NoteItem
import de.plmail.jmap.methods.NoteLink
import de.plmail.jmap.methods.NoteObject

/** A new note to open straight away: "Save as note" from a mail arrives with its link attached. */
data class NoteSeed(
    val title: String = "",
    val body: String = "",
    val links: List<NoteLink> = emptyList(),
    /** Start the new note as a checklist (the widget's "New list"). */
    val checklist: Boolean = false,
    /** Open this existing note instead of starting one (a tap on a widget card). */
    val openId: String? = null,
)

private sealed interface Editing {
    data class Existing(val id: String) : Editing

    data class New(val seed: NoteDraft?) : Editing
}

/**
 * The notes wall: pinned notes first, then the rest, as colour-coded cards two to a row; filter
 * chips for Notes / Archive / Bin; search over titles, text, checklist lines and link titles.
 * Tapping a card opens the editor in place, and the module owns its own back between the two — what
 * :app decides is only whether notes or mail is on screen.
 *
 * [onAddToCalendar] and [onOpenLink] are :app's to supply, because this module knows nothing of the
 * calendar or contacts screens: add-to-calendar turns a note into a new event, and opening a link
 * switches to the area it points into.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    onBack: () -> Unit,
    seed: NoteSeed? = null,
    onSeedHandled: () -> Unit = {},
    onAddToCalendar: ((title: String, description: String) -> Unit)? = null,
    onOpenLink: ((NoteLink) -> Unit)? = null,
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Editing?>(null) }
    var searching by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val failedMessage = stringResource(R.string.notes_failed)

    LaunchedEffect(seed, state.loaded) {
        val incoming = seed ?: return@LaunchedEffect
        val openId = incoming.openId
        if (openId != null) {
            // Wait for the first read: the note may not be in memory yet on a cold start.
            if (!state.loaded) return@LaunchedEffect
            if (viewModel.find(openId) != null) editing = Editing.Existing(openId)
        } else {
            editing =
                Editing.New(
                    NoteDraft(
                        title = incoming.title,
                        body = incoming.body,
                        links = incoming.links,
                        items = if (incoming.checklist) listOf(NoteItem()) else emptyList(),
                    )
                )
        }
        onSeedHandled()
    }
    LaunchedEffect(state.failedWrite) {
        if (state.failedWrite) {
            snackbar.showSnackbar(failedMessage)
            viewModel.dismissFailure()
        }
    }

    val open = editing
    if (open != null) {
        val existing = (open as? Editing.Existing)?.let { viewModel.find(it.id) }
        if (open is Editing.Existing && existing == null) {
            editing = null
        } else {
            NoteEditor(
                existing = existing,
                seed = (open as? Editing.New)?.seed,
                onClose = { editing = null },
                viewModel = viewModel,
                onAddToCalendar = onAddToCalendar,
                onOpenLink = onOpenLink,
            )
        }
        return
    }

    BackHandler(enabled = searching) {
        searching = false
        viewModel.setQuery("")
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.notes_back),
                        )
                    }
                },
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.notes_search)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(stringResource(R.string.notes_title))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            searching = !searching
                            if (!searching) viewModel.setQuery("")
                        }
                    ) {
                        Icon(
                            if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                            stringResource(R.string.notes_search),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.filter == NotesFilter.NOTES) {
                FloatingActionButton(onClick = { editing = Editing.New(null) }) {
                    Icon(Icons.Filled.Add, stringResource(R.string.notes_new))
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                        NotesFilter.NOTES to R.string.notes_filter_notes,
                        NotesFilter.ARCHIVE to R.string.notes_filter_archive,
                        NotesFilter.BIN to R.string.notes_filter_bin,
                    )
                    .forEach { (filter, label) ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                            label = { Text(stringResource(label)) },
                        )
                    }
            }
            if (state.labels.isNotEmpty()) {
                Row(
                    Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.labels.forEach { name ->
                        FilterChip(
                            selected = state.label.equals(name, ignoreCase = true),
                            onClick = { viewModel.toggleLabel(name) },
                            label = { Text(name) },
                        )
                    }
                }
            }

            if (state.error && !state.loaded) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.notes_error))
                    TextButton(onClick = viewModel::refresh) { Text("↻") }
                }
            } else if (state.notes.isEmpty() && state.loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            when (state.filter) {
                                NotesFilter.NOTES -> R.string.notes_empty
                                NotesFilter.ARCHIVE -> R.string.notes_empty_archive
                                NotesFilter.BIN -> R.string.notes_empty_bin
                            }
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val pinned = state.notes.filter { it.pinned }
                val others = state.notes.filter { !it.pinned }
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (pinned.isNotEmpty()) {
                        item(span = StaggeredGridItemSpan.FullLine) {
                            SectionLabel(R.string.notes_pinned)
                        }
                        items(pinned, key = { it.id }) { note ->
                            NoteCard(
                                note,
                                onOpen = { editing = Editing.Existing(note.id) },
                                viewModel,
                            )
                        }
                        if (others.isNotEmpty()) {
                            item(span = StaggeredGridItemSpan.FullLine) {
                                SectionLabel(R.string.notes_others)
                            }
                        }
                    }
                    items(others, key = { it.id }) { note ->
                        NoteCard(note, onOpen = { editing = Editing.Existing(note.id) }, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(label: Int) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun NoteCard(note: NoteObject, onOpen: () -> Unit, viewModel: NotesViewModel) {
    val content = noteContent(note.color)
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = noteContainer(note.color),
                contentColor = content,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (note.title.isNotEmpty()) {
                    Text(
                        note.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Box(Modifier.weight(1f))
                }
                if (note.pinned && !note.trashed) {
                    Icon(Icons.Outlined.PushPin, null, Modifier.size(16.dp))
                }
            }
            if (note.body.isNotEmpty()) {
                Text(
                    noteAnnotated(note.body),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val open = note.items.withIndex().filter { !it.value.done }
            val done = note.items.count { it.done }
            open.take(6).forEach { (index, item) ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier =
                        Modifier.clickable(enabled = !note.trashed) {
                            viewModel.toggleItem(note, index)
                        },
                ) {
                    Icon(Icons.Outlined.CheckBoxOutlineBlank, null, Modifier.size(16.dp))
                    Text(
                        item.text,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            if (open.size > 6) {
                Text("+${open.size - 6}", style = MaterialTheme.typography.labelSmall)
            }
            if (done > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckBox, null, Modifier.size(14.dp))
                    Text(
                        stringResource(R.string.notes_done_items, done),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            if (note.links.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    note.links.take(4).forEach {
                        Icon(linkIcon(it.type), it.type, Modifier.size(14.dp))
                    }
                    if (note.links.size > 4)
                        Text("+${note.links.size - 4}", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (note.labels.isNotEmpty()) {
                Text(
                    note.labels.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
