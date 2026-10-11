package de.plmail.feature.drive

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.DriveRepository
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.jmap.drive.DriveFile
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DriveLinkPickerState(
    val files: List<DriveFile>? = null,
    val isWorking: Boolean = false,
    val error: String? = null,
    /** The finished pick: a file name and a link anyone can open. */
    val picked: Pair<String, String>? = null,
)

/**
 * Picks a Drive file (or uploads one from the phone) and hands back a share link — for the composer
 * and the calendar's event editor. An existing open link is reused; otherwise a new one is made
 * with no password and no expiry.
 */
@HiltViewModel
class DriveLinkPickerViewModel @Inject constructor(private val drive: DriveRepository) :
    ViewModel() {

    private val _state = MutableStateFlow(DriveLinkPickerState())
    val state: StateFlow<DriveLinkPickerState> = _state.asStateFlow()

    fun load() {
        _state.value = DriveLinkPickerState()
        viewModelScope.launch {
            runCatching { drive.allFiles() }
                .onSuccess { files -> _state.update { it.copy(files = files) } }
                .onFailure { error ->
                    _state.update { it.copy(files = emptyList(), error = error.readable()) }
                }
        }
    }

    fun reset() {
        _state.value = DriveLinkPickerState()
    }

    fun pick(file: DriveFile) = work { file.name to drive.linkFor(file) }

    fun uploadAndPick(uri: Uri) = work {
        val file = drive.upload(uri, null)
        file.name to drive.linkFor(file)
    }

    private fun work(block: suspend () -> Pair<String, String>) {
        if (_state.value.isWorking) return
        _state.update { it.copy(isWorking = true, error = null) }
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { picked -> _state.update { it.copy(isWorking = false, picked = picked) } }
                .onFailure { error ->
                    _state.update { it.copy(isWorking = false, error = error.readable()) }
                }
        }
    }
}

@Composable
fun DriveLinkPicker(
    onDismiss: () -> Unit,
    onPicked: (name: String, url: String) -> Unit,
    viewModel: DriveLinkPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.load() }
    DisposableEffect(Unit) { onDispose { viewModel.reset() } }

    LaunchedEffect(state.picked) {
        val (name, url) = state.picked ?: return@LaunchedEffect
        onPicked(name, url)
    }

    val upload =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.uploadAndPick(uri)
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drive_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small)) {
                Text(
                    stringResource(R.string.drive_pick_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = PlMailTheme.colors.inkMuted,
                )
                TextButton(onClick = { upload.launch(arrayOf("*/*")) }, enabled = !state.isWorking) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null)
                    Text(
                        stringResource(R.string.drive_pick_upload),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.drive_pick_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let {
                    Text(it, color = PlMailTheme.colors.danger, style = MaterialTheme.typography.bodySmall)
                }
                val files = state.files
                when {
                    files == null || state.isWorking ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(Modifier.padding(PlMailTheme.spacing.medium))
                        }
                    else -> {
                        val shown = files.filter { it.name.contains(query.trim(), ignoreCase = true) }
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                            items(shown, key = { it.id }) { file ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { viewModel.pick(file) }
                                        .padding(vertical = PlMailTheme.spacing.small),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.InsertDriveFile,
                                        contentDescription = null,
                                        tint = PlMailTheme.colors.inkSoft,
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOfNotNull(file.folderName, file.size.asFileSize())
                                                .joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = PlMailTheme.colors.inkMuted,
                                        )
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) }
        },
    )
}
