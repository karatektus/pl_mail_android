package de.plmail.feature.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.data.Label
import de.plmail.core.designsystem.PlMailTheme

/** What the editor was opened for. */
sealed interface LabelEditorRequest {
    data object New : LabelEditorRequest

    /**
     * By key rather than by value.
     *
     * The dialog outlives a sync, and a captured [Label] would keep the counts and bindings it had
     * when it was opened — so a rename would be sent against a binding list that may since have
     * changed.
     */
    data class Edit(val key: String) : LabelEditorRequest
}

/**
 * Rename, delete, or make a label.
 *
 * A dialog rather than a screen, in both presentations. Naming a label is one field and two
 * buttons; a full screen for it turns a five-second act into a navigation.
 *
 * **No colour field.** plMail models a colour on the label and does not expose it over JMAP — it is
 * absent from `Mailbox/get`, refused on `Mailbox/set` update, and silently dropped on create. A
 * picker here would let someone choose a colour that goes nowhere. The ask is filed in
 * `docs/SERVER_REQUESTS.md`.
 */
@Composable
fun LabelEditor(
    request: LabelEditorRequest,
    labels: List<Label>,
    onDismiss: () -> Unit,
    onDeleted: (Label) -> Unit,
    viewModel: LabelEditorViewModel = hiltViewModel(),
) {
    val existing =
        when (request) {
            LabelEditorRequest.New -> null
            is LabelEditorRequest.Edit -> labels.firstOrNull { it.key == request.key }
        }

    var name by remember(existing?.key) { mutableStateOf(existing?.name.orEmpty()) }
    var confirmingDelete by remember(existing?.key) { mutableStateOf(false) }

    val state by viewModel.state.collectAsStateWithLifecycle()

    // Dismissal is driven by the ViewModel finishing rather than by the button,
    // so a rename that the server refuses leaves the dialog open with the
    // message in it instead of closing over a change that did not happen.
    LaunchedEffect(state.isDone) {
        if (!state.isDone) return@LaunchedEffect

        val deleted = state.deleted
        if (deleted != null) onDeleted(deleted) else onDismiss()

        viewModel.acknowledge()
    }

    if (confirmingDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.label_delete_title, existing.name)) },
            // Says what is *not* lost. "Delete label" beside a list of mail
            // reads as "delete the mail", and this product has no hard delete
            // for mail at all.
            text = { Text(stringResource(R.string.label_delete_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(existing, labels) }) {
                    Text(stringResource(R.string.label_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.label_cancel))
                }
            },
        )

        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.label_new_title else R.string.label_edit_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_name)) },
                    // The server refuses a slash in a leaf name, because Gmail
                    // encodes hierarchy in it and the two conventions would
                    // silently disagree. Said here rather than after a failed
                    // round trip.
                    supportingText = { Text(stringResource(R.string.label_name_hint)) },
                    isError = name.contains('/'),
                    modifier = Modifier.fillMaxWidth(),
                )

                state.error?.let {
                    Text(text = it, color = PlMailTheme.colors.danger)
                }

                if (state.isWorking) {
                    CircularProgressIndicator(
                        color = PlMailTheme.colors.accent,
                        strokeWidth = 2.dp,
                    )
                }

                if (existing?.mayDelete == true) {
                    TextButton(onClick = { confirmingDelete = true }) {
                        Text(
                            text = stringResource(R.string.label_delete),
                            color = PlMailTheme.colors.danger,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !name.contains('/') && !state.isWorking,
                onClick = {
                    if (existing == null) viewModel.create(name)
                    else viewModel.rename(existing, name)
                },
            ) {
                Text(stringResource(R.string.label_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.label_cancel)) }
        },
    )
}

/** Primitives only, so a rename cannot change what a saved bundle means. */
val LabelEditorSaver: Saver<LabelEditorRequest?, Any> =
    listSaver<LabelEditorRequest?, String>(
        save = {
            when (it) {
                null -> emptyList()
                LabelEditorRequest.New -> listOf("new")
                is LabelEditorRequest.Edit -> listOf("edit", it.key)
            }
        },
        restore = {
            when (it.getOrNull(0)) {
                "new" -> LabelEditorRequest.New
                "edit" -> LabelEditorRequest.Edit(it[1])
                else -> null
            }
        },
    )
