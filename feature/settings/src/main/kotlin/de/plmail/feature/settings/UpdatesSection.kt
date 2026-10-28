package de.plmail.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.data.AvailableUpdate
import de.plmail.core.designsystem.PaneTone
import de.plmail.core.designsystem.PlMailTheme

/**
 * The installed version and, on a build that can update itself, the way to the next one.
 *
 * At the top of diagnostics because "which version is this?" is the first question about any bug,
 * and because the update notification lands here: somebody who tapped it should not have to scroll
 * past the push state to find what they came for.
 */
@Composable
internal fun UpdatesSection(viewModel: UpdatesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Section(stringResource(R.string.updates_title)) {
        Fact(label = stringResource(R.string.updates_installed), value = state.installedVersion)

        if (!state.isSupported) {
            Text(
                text = stringResource(R.string.updates_not_supported),
                style = MaterialTheme.typography.bodySmall,
                color = PlMailTheme.colors.inkMuted,
            )
            return@Section
        }

        when (val phase = state.phase) {
            UpdatePhase.Idle -> CheckButton(isChecking = false, onCheck = viewModel::check)
            UpdatePhase.Checking -> CheckButton(isChecking = true, onCheck = viewModel::check)
            UpdatePhase.UpToDate -> {
                Note(text = stringResource(R.string.updates_up_to_date), tone = PaneTone.SUNKEN)
                CheckButton(isChecking = false, onCheck = viewModel::check)
            }
            is UpdatePhase.Available -> {
                Release(phase.update)
                TextButton(onClick = { viewModel.install(phase.update) }) {
                    Text(stringResource(R.string.updates_install))
                }
            }
            is UpdatePhase.Downloading -> {
                Release(phase.update)
                Text(
                    text = stringResource(R.string.updates_downloading, phase.percent),
                    style = MaterialTheme.typography.bodySmall,
                    color = PlMailTheme.colors.inkMuted,
                )
                LinearProgressIndicator(
                    progress = { phase.percent / 100f },
                    color = PlMailTheme.colors.accent,
                    trackColor = PlMailTheme.colors.sunken,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is UpdatePhase.Ready -> {
                Release(phase.update)
                TextButton(onClick = { viewModel.reopenInstaller(phase.apk) }) {
                    Text(stringResource(R.string.updates_open_installer))
                }
            }
            is UpdatePhase.Failed -> {
                phase.update?.let { Release(it) }
                Note(
                    text = stringResource(R.string.updates_failed, phase.message),
                    tone = PaneTone.DANGER,
                    isMonospace = true,
                )
                if (phase.update != null) {
                    TextButton(onClick = { viewModel.install(phase.update) }) {
                        Text(stringResource(R.string.updates_retry))
                    }
                } else {
                    CheckButton(isChecking = false, onCheck = viewModel::check)
                }
            }
        }

        AutoCheckRow(isOn = state.isAutoCheckOn, onChange = viewModel::setAutoCheck)
    }
}

@Composable
private fun Release(update: AvailableUpdate) {
    Fact(label = stringResource(R.string.updates_available), value = update.versionName)

    // GitHub's own notes, as written: Markdown read as plain text is still
    // readable, and a renderer is a dependency for one paragraph. Capped,
    // because generated notes list every pull request since the last tag.
    if (update.notes.isNotBlank()) {
        Note(text = update.notes.trim(), tone = PaneTone.SUNKEN, maxLines = NOTES_MAX_LINES)
    }
}

@Composable
private fun CheckButton(isChecking: Boolean, onCheck: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
        ) {
            TextButton(onClick = onCheck, enabled = !isChecking) {
                Text(stringResource(R.string.updates_check))
            }

            if (isChecking) {
                CircularProgressIndicator(
                    color = PlMailTheme.colors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Text(
            text = stringResource(R.string.updates_check_explains),
            style = MaterialTheme.typography.bodySmall,
            color = PlMailTheme.colors.inkMuted,
        )
    }
}

@Composable
private fun AutoCheckRow(isOn: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row is the control, so the label is what a screen reader
    // announces with the state and the target is the row, not the thumb.
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .toggleable(value = isOn, role = Role.Switch, onValueChange = onChange)
                .padding(vertical = PlMailTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.updates_auto_check),
            style = MaterialTheme.typography.bodyMedium,
            color = PlMailTheme.colors.ink,
            modifier = Modifier.weight(1f),
        )

        Switch(
            checked = isOn,
            onCheckedChange = null,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = PlMailTheme.colors.onAccent,
                    checkedTrackColor = PlMailTheme.colors.accent,
                    uncheckedTrackColor = PlMailTheme.colors.sunken,
                    uncheckedBorderColor = PlMailTheme.colors.line,
                ),
        )
    }
}

private const val NOTES_MAX_LINES = 12
