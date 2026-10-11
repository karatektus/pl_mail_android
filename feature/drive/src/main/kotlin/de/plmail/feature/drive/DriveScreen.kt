package de.plmail.feature.drive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailEmptyState
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.jmap.drive.DriveFile
import de.plmail.jmap.drive.DriveFolder
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Drive on the phone.
 *
 * Browse folders, upload from the phone (or from any app's share sheet, via [incoming]), open or
 * save files, and make share links with an optional password and expiry — the same Drive the web
 * explorer shows, through the server's `/jmap/drive` API.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(
    onBack: () -> Unit,
    /** Content URIs shared into "plMail Drive" from another app, to upload straight away. */
    incoming: List<String> = emptyList(),
    onIncomingHandled: () -> Unit = {},
    viewModel: DriveViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    val copiedText = stringResource(R.string.drive_links_copied)
    val openWith = stringResource(R.string.drive_open_with)
    val sendWith = stringResource(R.string.drive_send_with)

    var isNamingFolder by remember { mutableStateOf(false) }
    var renamingFile by remember { mutableStateOf<DriveFile?>(null) }
    var renamingFolder by remember { mutableStateOf<DriveFolder?>(null) }
    var deletingFile by remember { mutableStateOf<DriveFile?>(null) }
    var deletingFolder by remember { mutableStateOf<DriveFolder?>(null) }
    var movingFile by remember { mutableStateOf<DriveFile?>(null) }
    var savingFile by remember { mutableStateOf<DriveFile?>(null) }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            viewModel.upload(uris)
        }

    val saver =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val file = savingFile
            savingFile = null
            if (uri != null && file != null) viewModel.saveTo(file, uri)
        }

    // The ViewModel outlives the screen (it is scoped to the activity), so
    // re-read the folder each time the screen is shown.
    LaunchedEffect(Unit) { viewModel.reload() }

    LaunchedEffect(incoming) {
        if (incoming.isEmpty()) return@LaunchedEffect
        viewModel.upload(incoming.map(Uri::parse))
        onIncomingHandled()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DriveEvent.Message -> {
                    snackbars.currentSnackbarData?.dismiss()
                    snackbars.showSnackbar(event.text)
                }
                is DriveEvent.Open -> context.handOff(event.file, event.type, send = false, openWith)
                is DriveEvent.SendFile -> context.handOff(event.file, event.type, send = true, sendWith)
                is DriveEvent.LinkReady -> {
                    context.copyToClipboard(event.url)
                    snackbars.currentSnackbarData?.dismiss()
                    snackbars.showSnackbar(copiedText)
                }
            }
        }
    }

    BackHandler {
        when {
            state.sharing != null -> viewModel.closeLinks()
            !viewModel.up() -> onBack()
        }
    }

    val listing = state.listing
    val title = listing?.folder?.name ?: stringResource(R.string.drive_title)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PlMailTheme.colors.surface,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = PlMailTheme.colors.surface,
                        scrolledContainerColor = PlMailTheme.colors.surface,
                        titleContentColor = PlMailTheme.colors.ink,
                        navigationIconContentColor = PlMailTheme.colors.inkSoft,
                        actionIconContentColor = PlMailTheme.colors.inkSoft,
                    ),
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.up()) onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.drive_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { isNamingFolder = true }, enabled = !state.unavailable) {
                        Icon(
                            Icons.Filled.CreateNewFolder,
                            contentDescription = stringResource(R.string.drive_new_folder),
                        )
                    }
                    IconButton(onClick = viewModel::reload) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.drive_refresh),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.unavailable) {
                FloatingActionButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    containerColor = PlMailTheme.colors.accent,
                    contentColor = PlMailTheme.colors.onAccent,
                ) {
                    Icon(
                        Icons.Filled.CloudUpload,
                        contentDescription = stringResource(R.string.drive_upload),
                    )
                }
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            state.upload?.let { upload ->
                Column(Modifier.fillMaxWidth().padding(horizontal = PlMailTheme.spacing.gutter)) {
                    Text(
                        stringResource(R.string.drive_uploading, upload.done + 1, upload.total),
                        style = MaterialTheme.typography.labelMedium,
                        color = PlMailTheme.colors.inkMuted,
                    )
                    LinearProgressIndicator(
                        progress = { (upload.done.toFloat() + 0.5f) / upload.total },
                        modifier = Modifier.fillMaxWidth().padding(vertical = PlMailTheme.spacing.tiny),
                    )
                }
            }

            when {
                state.unavailable ->
                    PlMailEmptyState(
                        icon = Icons.Filled.CloudOff,
                        title = stringResource(R.string.drive_unavailable_title),
                        body = stringResource(R.string.drive_unavailable_body),
                    )
                state.isLoading && listing == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                state.loadError != null && listing == null ->
                    PlMailEmptyState(
                        icon = Icons.Filled.CloudOff,
                        title = stringResource(R.string.drive_error_title),
                        body = state.loadError,
                        action = {
                            TextButton(onClick = viewModel::reload) {
                                Text(stringResource(R.string.drive_retry))
                            }
                        },
                    )
                listing != null -> {
                    Breadcrumbs(
                        trail = listing.breadcrumbs,
                        onOpen = viewModel::open,
                    )
                    Usage(used = listing.usedBytes, quota = listing.quotaBytes)
                    PlMailDivider()

                    if (listing.folders.isEmpty() && listing.files.isEmpty()) {
                        PlMailEmptyState(
                            icon = Icons.Filled.FolderOpen,
                            title = stringResource(R.string.drive_empty_title),
                            body = stringResource(R.string.drive_empty_body),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 88.dp),
                        ) {
                            items(listing.folders, key = { "d${it.id}" }) { folder ->
                                FolderRow(
                                    folder = folder,
                                    onOpen = { viewModel.open(folder.id) },
                                    onRename = { renamingFolder = folder },
                                    onDelete = { deletingFolder = folder },
                                )
                            }
                            items(listing.files, key = { "f${it.id}" }) { file ->
                                FileRow(
                                    file = file,
                                    isBusy = state.busyFileId == file.id,
                                    onOpen = { viewModel.openFile(file) },
                                    onSend = { viewModel.sendFile(file) },
                                    onSave = {
                                        savingFile = file
                                        saver.launch(file.name)
                                    },
                                    onLinks = { viewModel.manageLinks(file) },
                                    onRename = { renamingFile = file },
                                    onMove = { movingFile = file },
                                    onDelete = { deletingFile = file },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ dialogs

    if (isNamingFolder) {
        NameDialog(
            title = stringResource(R.string.drive_new_folder),
            initial = "",
            onDismiss = { isNamingFolder = false },
            onConfirm = {
                isNamingFolder = false
                viewModel.createFolder(it)
            },
        )
    }

    renamingFolder?.let { folder ->
        NameDialog(
            title = stringResource(R.string.drive_rename),
            initial = folder.name,
            onDismiss = { renamingFolder = null },
            onConfirm = {
                renamingFolder = null
                viewModel.renameFolder(folder, it)
            },
        )
    }

    renamingFile?.let { file ->
        NameDialog(
            title = stringResource(R.string.drive_rename),
            initial = file.name,
            onDismiss = { renamingFile = null },
            onConfirm = {
                renamingFile = null
                viewModel.renameFile(file, it)
            },
        )
    }

    deletingFile?.let { file ->
        ConfirmDialog(
            title = stringResource(R.string.drive_delete_file_title, file.name),
            body = stringResource(R.string.drive_delete_file_body),
            onDismiss = { deletingFile = null },
            onConfirm = {
                deletingFile = null
                viewModel.deleteFile(file)
            },
        )
    }

    deletingFolder?.let { folder ->
        ConfirmDialog(
            title = stringResource(R.string.drive_delete_folder_title, folder.name),
            body = stringResource(R.string.drive_delete_folder_body),
            onDismiss = { deletingFolder = null },
            onConfirm = {
                deletingFolder = null
                viewModel.deleteFolder(folder)
            },
        )
    }

    movingFile?.let { file ->
        MoveDialog(
            file = file,
            load = viewModel::foldersIn,
            onDismiss = { movingFile = null },
            onMove = { target ->
                movingFile = null
                viewModel.moveFile(file, target)
            },
        )
    }

    state.sharing?.let { sharing ->
        LinksDialog(
            sharing = sharing,
            onCreate = viewModel::createLink,
            onCopy = { url ->
                context.copyToClipboard(url)
            },
            onShare = { url -> context.shareText(url, sendWith) },
            onRevoke = viewModel::revokeLink,
            onDismiss = viewModel::closeLinks,
        )
    }
}

// ---------------------------------------------------------------------- rows

@Composable
private fun Breadcrumbs(trail: List<DriveFolder>, onOpen: (Long?) -> Unit) {
    if (trail.isEmpty()) return
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = PlMailTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onOpen(null) }) { Text(stringResource(R.string.drive_root)) }
        trail.forEach { folder ->
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = PlMailTheme.colors.inkFaint,
                modifier = Modifier.size(16.dp),
            )
            TextButton(onClick = { onOpen(folder.id) }) {
                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Usage(used: Long, quota: Long?) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = PlMailTheme.spacing.gutter, vertical = PlMailTheme.spacing.small)
    ) {
        Text(
            text =
                if (quota != null) {
                    stringResource(R.string.drive_usage, used.asFileSize(), quota.asFileSize())
                } else {
                    stringResource(R.string.drive_usage_unlimited, used.asFileSize())
                },
            style = MaterialTheme.typography.labelMedium,
            color = PlMailTheme.colors.inkMuted,
        )
        if (quota != null && quota > 0) {
            LinearProgressIndicator(
                progress = { (used.toFloat() / quota).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = PlMailTheme.spacing.tiny),
            )
        }
    }
}

@Composable
private fun FolderRow(
    folder: DriveFolder,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = PlMailTheme.spacing.gutter, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.medium),
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = PlMailTheme.colors.accent)
        Text(
            folder.name,
            style = MaterialTheme.typography.bodyLarge,
            color = PlMailTheme.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.drive_more, folder.name),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drive_rename)) },
                    onClick = {
                        menu = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drive_delete)) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    file: DriveFile,
    isBusy: Boolean,
    onOpen: () -> Unit,
    onSend: () -> Unit,
    onSave: () -> Unit,
    onLinks: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = PlMailTheme.spacing.gutter, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.medium),
    ) {
        if (isBusy) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Icon(iconFor(file.type), contentDescription = null, tint = PlMailTheme.colors.inkSoft)
        }
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyLarge,
                color = PlMailTheme.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small)) {
                Text(
                    listOfNotNull(file.size.asFileSize(), file.updatedAt.asShortDate())
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = PlMailTheme.colors.inkMuted,
                )
                if (file.shareCount > 0) {
                    Icon(
                        Icons.Filled.Link,
                        contentDescription = null,
                        tint = PlMailTheme.colors.accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        stringResource(R.string.drive_shared, file.shareCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = PlMailTheme.colors.accent,
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.drive_more, file.name),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf(
                        R.string.drive_open to onOpen,
                        R.string.drive_links to onLinks,
                        R.string.drive_send to onSend,
                        R.string.drive_save to onSave,
                        R.string.drive_rename to onRename,
                        R.string.drive_move to onMove,
                        R.string.drive_delete to onDelete,
                    )
                    .forEach { (label, action) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            onClick = {
                                menu = false
                                action()
                            },
                        )
                    }
            }
        }
    }
}

// ---------------------------------------------------------------------- dialogs

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.drive_name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.drive_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) }
        },
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.drive_delete), color = PlMailTheme.colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) }
        },
    )
}

/** Browse folders to pick where [file] goes. */
@Composable
private fun MoveDialog(
    file: DriveFile,
    load: suspend (Long?) -> List<DriveFolder>?,
    onDismiss: () -> Unit,
    onMove: (Long?) -> Unit,
) {
    var at by remember { mutableStateOf<DriveFolder?>(null) }
    var trail by remember { mutableStateOf(listOf<DriveFolder>()) }
    var folders by remember { mutableStateOf<List<DriveFolder>?>(null) }

    LaunchedEffect(at) {
        folders = null
        folders = load(at?.id) ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drive_move_title, file.name)) },
        text = {
            Column {
                Text(
                    (listOf(stringResource(R.string.drive_root)) + trail.map { it.name })
                        .joinToString(" › "),
                    style = MaterialTheme.typography.labelMedium,
                    color = PlMailTheme.colors.inkMuted,
                )
                if (at != null) {
                    TextButton(
                        onClick = {
                            trail = trail.dropLast(1)
                            at = trail.lastOrNull()
                        }
                    ) {
                        Text(stringResource(R.string.drive_move_up))
                    }
                }
                val list = folders
                if (list == null) {
                    CircularProgressIndicator(Modifier.padding(PlMailTheme.spacing.medium))
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(list, key = { it.id }) { folder ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        trail = trail + folder
                                        at = folder
                                    }
                                    .padding(vertical = PlMailTheme.spacing.small),
                                horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Folder, null, tint = PlMailTheme.colors.accent)
                                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onMove(at?.id) }, enabled = at?.id != file.folderId) {
                Text(stringResource(R.string.drive_move_here))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) }
        },
    )
}

@Composable
private fun LinksDialog(
    sharing: SharingState,
    onCreate: (password: String, expiresInDays: Int?) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onRevoke: (de.plmail.jmap.drive.DriveShare) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var expires by remember { mutableStateOf<Int?>(7) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drive_links_title, sharing.file.name)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small)) {
                item {
                    Text(
                        stringResource(R.string.drive_links_new),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                item {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.drive_links_password)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        stringResource(R.string.drive_links_expires),
                        style = MaterialTheme.typography.labelMedium,
                        color = PlMailTheme.colors.inkMuted,
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny),
                    ) {
                        listOf<Int?>(1, 7, 30, null).forEach { days ->
                            androidx.compose.material3.FilterChip(
                                selected = expires == days,
                                onClick = { expires = days },
                                label = {
                                    Text(
                                        when (days) {
                                            null -> stringResource(R.string.drive_links_never)
                                            1 -> stringResource(R.string.drive_links_day)
                                            else -> stringResource(R.string.drive_links_days, days)
                                        }
                                    )
                                },
                            )
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = {
                            onCreate(password, expires)
                            password = ""
                        },
                        enabled = !sharing.isCreating,
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = null)
                        Text(
                            stringResource(R.string.drive_links_create),
                            modifier = Modifier.padding(start = PlMailTheme.spacing.small),
                        )
                    }
                    PlMailDivider()
                }

                if (sharing.isLoading) {
                    item { CircularProgressIndicator(Modifier.padding(PlMailTheme.spacing.medium)) }
                } else if (sharing.shares.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.drive_links_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = PlMailTheme.colors.inkMuted,
                        )
                    }
                }

                items(sharing.shares, key = { it.id }) { share ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            share.bestLink,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                    stringResource(R.string.drive_links_protected)
                                        .takeIf { share.isProtected },
                                    share.expiresAt?.asShortDate()?.let {
                                        stringResource(R.string.drive_links_expires_on, it)
                                    },
                                    stringResource(R.string.drive_links_downloads, share.downloadCount),
                                )
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = PlMailTheme.colors.inkMuted,
                        )
                        Row {
                            TextButton(onClick = { onCopy(share.bestLink) }) {
                                Text(stringResource(R.string.drive_links_copy))
                            }
                            TextButton(onClick = { onShare(share.bestLink) }) {
                                Text(stringResource(R.string.drive_links_share))
                            }
                            TextButton(onClick = { onRevoke(share) }) {
                                Text(
                                    stringResource(R.string.drive_links_revoke),
                                    color = PlMailTheme.colors.danger,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_done)) }
        },
    )
}

// ---------------------------------------------------------------------- helpers

private fun iconFor(type: String): ImageVector =
    when {
        type.startsWith("image/") -> Icons.Filled.Image
        type.startsWith("video/") -> Icons.Filled.Movie
        type.startsWith("audio/") -> Icons.Filled.MusicNote
        type == "application/pdf" -> Icons.Filled.PictureAsPdf
        type.startsWith("text/") || type.contains("document") -> Icons.Filled.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

internal fun Long.asFileSize(): String =
    when {
        this < 1024 -> "$this B"
        this < 1024 * 1024 -> "%.1f KB".format(this / 1024.0)
        this < 1024L * 1024 * 1024 -> "%.1f MB".format(this / (1024.0 * 1024))
        else -> "%.2f GB".format(this / (1024.0 * 1024 * 1024))
    }

private fun String.asShortDate(): String? =
    runCatching {
            OffsetDateTime.parse(this)
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
        .getOrNull()

private fun Context.copyToClipboard(text: String) {
    getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("plMail Drive link", text))
}

private fun Context.shareText(text: String, title: String) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    runCatching {
        startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Opens or sends a downloaded copy through the app's FileProvider (`cache/drive/`). */
private fun Context.handOff(file: File, type: String, send: Boolean, title: String) {
    val uri = FileProvider.getUriForFile(this, "$packageName.blobs", file)
    val intent =
        if (send) {
            Intent(Intent.ACTION_SEND).apply {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, type)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    runCatching {
        startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
