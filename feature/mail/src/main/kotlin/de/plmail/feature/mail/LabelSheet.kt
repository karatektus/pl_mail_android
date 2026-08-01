package de.plmail.feature.mail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.core.data.ActionTarget
import de.plmail.core.data.Label
import de.plmail.core.data.LabelSelection
import de.plmail.core.designsystem.PlMailTheme

/**
 * "Label as", over one conversation or forty.
 *
 * Three states rather than two, and that is the point of the sheet rather than a menu. A label on
 * three of the five selected conversations is neither on nor off, and a plain checkbox has to pick
 * one of those lies — after which tapping it either silently removes the label from three
 * conversations or silently does nothing to two. The indeterminate state ticks *up*: partly applied
 * becomes fully applied, which is what "label these as Work" means.
 *
 * System labels are absent. Moving mail to Trash or marking it as spam are actions with their own
 * consequences and their own undo, and offering them here as ticks would make "remove the Inbox
 * label" — which is what archiving is — look like an ordinary label change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelSheet(
    labels: List<Label>,
    selection: LabelSelection,
    targets: List<ActionTarget>,
    onToggle: (Label, Boolean) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState()
    val theme = PlMailTheme.values
    val custom = remember(labels) { labels.filterNot { it.isSystem } }

    // Held locally as well as read from the repository. The tick has to move
    // under the thumb; the reread that confirms it comes back a frame or two
    // later, and a checkbox that waits for it feels broken.
    var applied by remember(selection) { mutableStateOf(selection.onAll) }
    var partial by remember(selection) { mutableStateOf(selection.onSome) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = theme.colors.surface,
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = theme.spacing.large)) {
            Text(
                text = stringResource(R.string.labels_apply),
                style = MaterialTheme.typography.titleMedium,
                color = theme.colors.ink,
                modifier =
                    Modifier.padding(
                        horizontal = theme.spacing.gutter,
                        vertical = theme.spacing.small,
                    ),
            )

            if (custom.isEmpty()) {
                Text(
                    text = stringResource(R.string.labels_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.colors.inkMuted,
                    modifier = Modifier.padding(horizontal = theme.spacing.gutter),
                )
            }

            LazyColumn(modifier = Modifier.heightIn(max = SHEET_LIST_MAX)) {
                items(items = custom, key = { it.key }) { label ->
                    val state =
                        when {
                            label.key in applied -> ToggleableState.On
                            label.key in partial -> ToggleableState.Indeterminate
                            else -> ToggleableState.Off
                        }

                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable {
                                    // Indeterminate ticks up. See the note above:
                                    // "label these as Work" cannot mean "remove
                                    // it from the three that already have it".
                                    val next = state != ToggleableState.On

                                    applied = if (next) applied + label.key else applied - label.key
                                    partial = partial - label.key

                                    onToggle(label, next)
                                }
                                .heightIn(min = theme.spacing.touchTarget)
                                .padding(horizontal = theme.spacing.medium),
                        horizontalArrangement = Arrangement.spacedBy(theme.spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriStateCheckbox(state = state, onClick = null)

                        Text(
                            text = label.path,
                            color = theme.colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                }
            }

            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable(onClick = onCreate)
                        .heightIn(min = theme.spacing.touchTarget)
                        .padding(horizontal = theme.spacing.gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.label_new), color = theme.colors.accent)
            }
        }
    }

    // Nothing to label. Can happen when a selection is cleared behind the sheet
    // -- by an undo, or by the list refreshing the rows out from under it.
    LaunchedEffect(targets) { if (targets.isEmpty()) onDismiss() }
}

/**
 * How tall the label list gets before it scrolls inside the sheet.
 *
 * Capped so the "New label" row below it stays reachable. Someone with forty labels would otherwise
 * have to scroll the whole sheet off the screen to find the one control that adds another.
 */
private val SHEET_LIST_MAX = 320.dp
