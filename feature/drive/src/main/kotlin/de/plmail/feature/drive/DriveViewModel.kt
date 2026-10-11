package de.plmail.feature.drive

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.DriveRepository
import de.plmail.jmap.drive.DriveFile
import de.plmail.jmap.drive.DriveFolder
import de.plmail.jmap.drive.DriveListing
import de.plmail.jmap.drive.DriveShare
import de.plmail.jmap.protocol.JmapError
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DriveUiState(
    val folderId: Long? = null,
    val listing: DriveListing? = null,
    val isLoading: Boolean = true,
    /** Set when the folder could not be loaded at all; drawn instead of the list. */
    val loadError: String? = null,
    val upload: UploadProgress? = null,
    /** The file whose links are being managed, with its current links. */
    val sharing: SharingState? = null,
    /** True when the server has no Drive capability. */
    val unavailable: Boolean = false,
    /** A file being fetched before it can be opened or sent. */
    val busyFileId: Long? = null,
)

data class UploadProgress(val done: Int, val total: Int, val currentName: String)

data class SharingState(
    val file: DriveFile,
    val shares: List<DriveShare> = emptyList(),
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
)

sealed interface DriveEvent {
    data class Message(val text: String) : DriveEvent

    /** A downloaded copy is ready to open in another app. */
    data class Open(val file: File, val type: String) : DriveEvent

    /** A downloaded copy is ready to send through the share sheet. */
    data class SendFile(val file: File, val type: String) : DriveEvent

    /** A share link was made; the screen copies it to the clipboard. */
    data class LinkReady(val url: String) : DriveEvent
}

@HiltViewModel
class DriveViewModel @Inject constructor(private val drive: DriveRepository) : ViewModel() {

    private val _state = MutableStateFlow(DriveUiState())
    val state: StateFlow<DriveUiState> = _state.asStateFlow()

    private val _events = Channel<DriveEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        open(null)
    }

    // ---------------------------------------------------------------- browsing

    fun open(folderId: Long?) {
        _state.update { it.copy(folderId = folderId, isLoading = true, loadError = null) }
        reload()
    }

    /** Up one level, or false when already at the root (the screen then closes). */
    fun up(): Boolean {
        val current = _state.value.listing?.folder ?: return false
        open(current.parentId)
        return true
    }

    fun reload() {
        val folderId = _state.value.folderId
        viewModelScope.launch {
            if (!drive.isAvailable()) {
                _state.update { it.copy(isLoading = false, unavailable = true) }
                return@launch
            }
            runCatching { drive.list(folderId) }
                .onSuccess { listing ->
                    _state.update {
                        if (it.folderId != folderId) it
                        else it.copy(listing = listing, isLoading = false, unavailable = false)
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(isLoading = false, loadError = error.readable()) }
                }
        }
    }

    /** Sub-folders of [folderId] for the move picker; null when they could not be read. */
    suspend fun foldersIn(folderId: Long?): List<DriveFolder>? =
        runCatching { drive.list(folderId).folders }.getOrNull()

    // ---------------------------------------------------------------- uploading

    /** Uploads every picked or shared file into the folder on screen, one after another. */
    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val folderId = _state.value.folderId

        viewModelScope.launch {
            var failed = 0
            var lastError: String? = null
            uris.forEachIndexed { index, uri ->
                _state.update {
                    it.copy(upload = UploadProgress(index, uris.size, uri.lastPathSegment ?: ""))
                }
                runCatching { drive.upload(uri, folderId) }
                    .onFailure {
                        failed++
                        lastError = it.readable()
                    }
            }
            _state.update { it.copy(upload = null) }

            val ok = uris.size - failed
            message(
                when {
                    failed == 0 && ok == 1 -> "Uploaded to Drive."
                    failed == 0 -> "Uploaded $ok files to Drive."
                    ok == 0 -> lastError ?: "Upload failed."
                    else -> "Uploaded $ok of ${uris.size}. $lastError"
                }
            )
            reload()
        }
    }

    // ---------------------------------------------------------------- folders

    fun createFolder(name: String) = act("Folder created.") {
        drive.createFolder(name.trim(), _state.value.folderId)
    }

    fun renameFolder(folder: DriveFolder, name: String) = act("Folder renamed.") {
        drive.renameFolder(folder.id, name.trim())
    }

    fun deleteFolder(folder: DriveFolder) = act("Folder deleted.") { drive.deleteFolder(folder.id) }

    // ---------------------------------------------------------------- files

    fun renameFile(file: DriveFile, name: String) = act("Renamed.") {
        drive.renameFile(file.id, name.trim())
    }

    fun moveFile(file: DriveFile, folderId: Long?) = act("Moved.") { drive.moveFile(file.id, folderId) }

    fun deleteFile(file: DriveFile) = act("Deleted.") { drive.deleteFile(file.id) }

    fun openFile(file: DriveFile) = fetch(file) { DriveEvent.Open(it, file.type) }

    fun sendFile(file: DriveFile) = fetch(file) { DriveEvent.SendFile(it, file.type) }

    fun saveTo(file: DriveFile, destination: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busyFileId = file.id) }
            runCatching { drive.downloadTo(file, destination) }
                .onSuccess { message("Saved ${file.name}.") }
                .onFailure { message(it.readable()) }
            _state.update { it.copy(busyFileId = null) }
        }
    }

    // ---------------------------------------------------------------- share links

    fun manageLinks(file: DriveFile) {
        _state.update { it.copy(sharing = SharingState(file)) }
        viewModelScope.launch {
            runCatching { drive.shares(file.id) }
                .onSuccess { shares ->
                    updateSharing { it.copy(shares = shares, isLoading = false) }
                }
                .onFailure { error ->
                    updateSharing { it.copy(isLoading = false) }
                    message(error.readable())
                }
        }
    }

    fun closeLinks() {
        _state.update { it.copy(sharing = null) }
        reload()
    }

    /** [expiresInDays] null = never. */
    fun createLink(password: String, expiresInDays: Int?) {
        val sharing = _state.value.sharing ?: return
        val expires = expiresInDays?.let { LocalDate.now().plusDays(it.toLong()).toString() }

        updateSharing { it.copy(isCreating = true) }
        viewModelScope.launch {
            runCatching { drive.createShare(sharing.file.id, password, expires) }
                .onSuccess { share ->
                    updateSharing { it.copy(shares = listOf(share) + it.shares, isCreating = false) }
                    _events.send(DriveEvent.LinkReady(share.bestLink))
                }
                .onFailure { error ->
                    updateSharing { it.copy(isCreating = false) }
                    message(error.readable())
                }
        }
    }

    fun revokeLink(share: DriveShare) {
        viewModelScope.launch {
            runCatching { drive.revokeShare(share.id) }
                .onSuccess {
                    updateSharing { it.copy(shares = it.shares.filter { s -> s.id != share.id }) }
                    message("Link revoked. It no longer works for anyone.")
                }
                .onFailure { message(it.readable()) }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun updateSharing(change: (SharingState) -> SharingState) {
        _state.update { state -> state.copy(sharing = state.sharing?.let(change)) }
    }

    private fun act(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { message(success) }
                .onFailure { message(it.readable()) }
            reload()
        }
    }

    private fun fetch(file: DriveFile, event: (File) -> DriveEvent) {
        if (_state.value.busyFileId != null) return
        viewModelScope.launch {
            _state.update { it.copy(busyFileId = file.id) }
            runCatching { drive.downloadToCache(file) }
                .onSuccess { _events.send(event(it)) }
                .onFailure { message(it.readable()) }
            _state.update { it.copy(busyFileId = null) }
        }
    }

    private suspend fun message(text: String) = _events.send(DriveEvent.Message(text))
}

internal fun Throwable.readable(): String =
    when (this) {
        is JmapError.RequestRejected -> detail ?: "The server refused that (HTTP $status)."
        is JmapError.Unreachable -> "Could not reach your server. Check your connection."
        is JmapError.NotAuthenticated -> "Your sign-in has expired. Pair the app again."
        else -> message ?: "Something went wrong."
    }
