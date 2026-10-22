package de.plmail.feature.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.jmap.methods.NoteDraft
import de.plmail.jmap.methods.NoteLink
import de.plmail.jmap.methods.NoteObject

/**
 * "Notes" on the screen of the thing a note is about — an event, a contact, a file, a mail.
 *
 * Lists the notes that link to ([type], [id]) and offers "Add note", which opens the editor with
 * that link already attached and [title] as the label. Tapping a note opens it in a full-screen
 * dialog, so the host screen needs no navigation of its own. Draws nothing at all when the server
 * has no notes, so a screen can embed it unconditionally.
 */
@Composable
fun LinkedNotesSection(
    type: String,
    id: String,
    title: String,
    modifier: Modifier = Modifier,
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val available by viewModel.available.collectAsStateWithLifecycle()
    if (!available) return

    val notes by
        remember(type, id) { viewModel.linkedTo(type, id) }
            .collectAsStateWithLifecycle(initialValue = emptyList())
    var editing by remember { mutableStateOf<NoteObject?>(null) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.notes_linked_header),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        notes.forEach { note ->
            Row(
                Modifier.fillMaxWidth().clickable { editing = note }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        note.title.ifEmpty { stringResource(R.string.notes_untitled) },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val preview = note.body.ifBlank { note.items.joinToString(", ") { it.text } }
                    if (preview.isNotBlank()) {
                        Text(
                            preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        TextButton(onClick = { adding = true }) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Text(
                stringResource(R.string.notes_linked_add),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    val open = editing
    if (open != null || adding) {
        Dialog(
            onDismissRequest = {
                editing = null
                adding = false
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize()) {
                NoteEditor(
                    existing = open?.let { viewModel.find(it.id) ?: it },
                    seed =
                        if (open == null) NoteDraft(links = listOf(NoteLink(type, id, title)))
                        else null,
                    onClose = {
                        editing = null
                        adding = false
                    },
                    viewModel = viewModel,
                )
            }
        }
    }
}

/**
 * [LinkedNotesSection] in a dialog, for screens with no room to embed it — a file's menu. Shows
 * nothing but the section, so on a server without notes it is an empty dialog the user dismisses;
 * callers only offer it where notes exist.
 */
@Composable
fun LinkedNotesDialog(type: String, id: String, title: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.notes_close)) }
        },
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = { LinkedNotesSection(type = type, id = id, title = title) },
    )
}
