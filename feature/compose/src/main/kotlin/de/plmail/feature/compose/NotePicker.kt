package de.plmail.feature.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.jmap.methods.NoteObject

/** Pick one of your notes to put into the message as text. */
@Composable
internal fun NotePickerDialog(
    load: suspend () -> List<NoteObject>,
    onPick: (NoteObject) -> Unit,
    onDismiss: () -> Unit,
) {
    var notes by remember { mutableStateOf<List<NoteObject>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { notes = runCatching { load() }.getOrDefault(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compose_note_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.compose_note_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = notes
                when {
                    list == null ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(Modifier.padding(PlMailTheme.spacing.medium))
                        }
                    list.isEmpty() ->
                        Text(
                            stringResource(R.string.compose_note_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PlMailTheme.colors.inkMuted,
                        )
                    else -> {
                        val q = query.trim()
                        val shown = list.filter {
                            q.isBlank() ||
                                it.title.contains(q, ignoreCase = true) ||
                                it.body.contains(q, ignoreCase = true) ||
                                it.items.any { item -> item.text.contains(q, ignoreCase = true) }
                        }
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                            items(shown, key = { it.id }) { note ->
                                val preview =
                                    if (note.items.isNotEmpty()) {
                                        note.items.joinToString(" · ") { it.text }
                                    } else {
                                        note.body.replace('\n', ' ')
                                    }
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { onPick(note) }
                                        .padding(vertical = PlMailTheme.spacing.small),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement =
                                        Arrangement.spacedBy(PlMailTheme.spacing.small),
                                ) {
                                    Icon(
                                        Icons.Outlined.Description,
                                        contentDescription = null,
                                        tint = PlMailTheme.colors.inkSoft,
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            note.title.ifBlank { preview.take(60).ifBlank { "—" } },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (note.title.isNotBlank() && preview.isNotBlank()) {
                                            Text(
                                                preview,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = PlMailTheme.colors.inkMuted,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.compose_note_cancel)) }
        },
    )
}
