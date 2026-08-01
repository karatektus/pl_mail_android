package de.plmail.feature.mail

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.data.Label
import de.plmail.core.designsystem.PlMailLabelColor
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
 * Rename, recolour, delete, or make a label.
 *
 * A dialog rather than a screen, in both presentations. Naming a label is one field, a row of
 * swatches and two buttons; a full screen for it turns a five-second act into a navigation.
 *
 * **The colour picker offers exactly the server's vocabulary, plus none.** `Mailbox/set` refuses a
 * token it does not know with `invalidProperties` and creates nothing, so a wheel or a hex field
 * would be a control whose most obvious use fails. Nine swatches is also the point of a closed
 * vocabulary rather than a limitation of it: a token resolves per theme, so the label somebody
 * makes blue on a phone in Nord is the same blue on the web in Solarized.
 *
 * "None" is a swatch of its own rather than the absence of a selection, because clearing a colour
 * is something people do — and the patch sends an explicit null for it, since an omitted key on an
 * update means "leave it alone".
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
    // Resolved through the vocabulary rather than kept as the raw token, so a
    // colour this build does not know arrives as "none" in the picker instead of
    // as a swatch nothing can draw. Saving then clears it, which is the honest
    // outcome: the user is looking at a control that does not show the value.
    var color by
        remember(existing?.key) { mutableStateOf(PlMailLabelColor.fromWire(existing?.color)) }
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
                // Hidden for a label the server will not rename. A disabled
                // field carrying "Inbox" reads as a bug; leaving it out and
                // showing only the swatches says what this dialog can actually
                // do to a system label, which is recolour it.
                if (existing == null || existing.mayRename) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.label_name)) },
                        // The server refuses a slash in a leaf name, because
                        // Gmail encodes hierarchy in it and the two conventions
                        // would silently disagree. Said here rather than after a
                        // failed round trip.
                        supportingText = { Text(stringResource(R.string.label_name_hint)) },
                        isError = name.contains('/'),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                ColorPicker(selected = color, onSelect = { color = it })

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
            // A system label has no name field, so the name check must not gate
            // its save: the button would be dead on the one dialog where colour
            // is the only thing on offer.
            val namable = existing == null || existing.mayRename

            TextButton(
                enabled =
                    (!namable || (name.isNotBlank() && !name.contains('/'))) && !state.isWorking,
                onClick = {
                    if (existing == null) viewModel.create(name, color?.wire)
                    else viewModel.save(existing, name, color?.wire)
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

/**
 * Nine colours and "none", as a grid of swatches.
 *
 * Not a dropdown: ten choices whose whole content is *what they look like* is the case a menu is
 * worst at, because a menu shows one at a time and asks the user to remember the other nine.
 *
 * **And not a horizontally scrolling row either**, which is what this was until it was opened on
 * the device. A teal label showed five swatches — none, grey, red, orange, amber — with its own
 * colour off the right edge and no selected mark visible anywhere, so the dialog said the label had
 * no colour while the sidebar row two taps away was teal. A scrolling row has exactly the fault a
 * menu has, plus the indignity of hiding the current value. `FlowRow` wraps instead: every choice
 * is on screen at once, and the height is fixed whatever the density, because the touch target is
 * the one spacing token that never scales.
 *
 * Each swatch is a ring rather than a filled disc, matching the chip it produces: the chip's colour
 * is its outline and its text, never its fill, so a picker of solid dots would promise something
 * the row does not draw. "None" is the same ring in the hairline colour — visibly a choice, visibly
 * not a colour.
 *
 * Selection is a second ring outside the first rather than a tick over it, because a tick has to be
 * drawn in some ink and every ink is wrong on at least one of the ten.
 */
@Composable
private fun ColorPicker(
    selected: PlMailLabelColor?,
    onSelect: (PlMailLabelColor?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = PlMailTheme.values
    val none = stringResource(R.string.label_color_none)

    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.tiny)) {
        Text(
            text = stringResource(R.string.label_color),
            style = MaterialTheme.typography.labelLarge,
            color = theme.colors.inkMuted,
        )

        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(theme.spacing.tiny),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.tiny),
        ) {
            Swatch(
                color = theme.colors.lineStrong,
                isSelected = selected == null,
                description = none,
                onClick = { onSelect(null) },
            )

            PlMailLabelColor.entries.forEach { token ->
                Swatch(
                    color = theme.colors.labelColor(token),
                    isSelected = selected == token,
                    // The token, not a translated colour name. There is no
                    // catalogue for these on either surface, and "blue" spoken
                    // by TalkBack is more use than nothing at all — which is
                    // what an unlabelled swatch is.
                    description = token.wire,
                    onClick = { onSelect(token) },
                )
            }
        }
    }
}

@Composable
private fun Swatch(
    color: Color,
    isSelected: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val theme = PlMailTheme.values

    // The whole 48dp box is the target even though the ring inside it is 24dp.
    // A swatch small enough to look like a swatch is too small to hit, and the
    // ten of them are the densest control in the app.
    Box(
        modifier =
            Modifier.size(theme.spacing.touchTarget)
                .clip(CircleShape)
                .selectable(
                    selected = isSelected,
                    onClick = onClick,
                    role = Role.RadioButton,
                )
                .semantics { contentDescription = description }
                .then(
                    if (isSelected) {
                        Modifier.border(SELECTION_RING, theme.colors.accent, CircleShape)
                    } else {
                        Modifier
                    }
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(SWATCH).border(SWATCH_RING, color, CircleShape))
    }
}

private val SWATCH = 24.dp
private val SWATCH_RING = 3.dp
private val SELECTION_RING = 2.dp

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
