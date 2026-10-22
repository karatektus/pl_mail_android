package de.plmail.feature.notes

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.plmail.jmap.methods.NoteDraft
import de.plmail.jmap.methods.NoteItem
import de.plmail.jmap.methods.NoteLink
import de.plmail.jmap.methods.NoteMedia
import de.plmail.jmap.methods.NoteObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun linkIcon(type: String): ImageVector =
    when (type) {
        NoteLink.EVENT -> Icons.Outlined.Event
        NoteLink.CONTACT -> Icons.Outlined.Contacts
        NoteLink.FILE -> Icons.Outlined.Description
        NoteLink.MAIL -> Icons.Outlined.Email
        else -> Icons.Outlined.Link
    }

/**
 * The note editor: title, free text or a checklist, colour, pin, archive, bin, and links.
 *
 * Saves as you type, as Keep does — a moment after the last change, and again on close — so there
 * is no Save button. A new note is created on the first change that gives it content; a note left
 * empty is never written. Undo and redo walk snapshots of the editor's state. [existing] is the
 * note being edited, [seed] starts a new one (an event's "add note" arrives with its link already
 * attached).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteEditor(
    existing: NoteObject?,
    seed: NoteDraft?,
    onClose: () -> Unit,
    viewModel: NotesViewModel,
    onAddToCalendar: ((title: String, description: String) -> Unit)? = null,
    onOpenLink: ((NoteLink) -> Unit)? = null,
) {
    val start =
        existing
            ?: NoteObject(
                id = "",
                title = seed?.title.orEmpty(),
                body = seed?.body.orEmpty(),
                items = seed?.items.orEmpty(),
                color = seed?.color,
                pinned = seed?.pinned ?: false,
                archived = seed?.archived ?: false,
                links = seed?.links.orEmpty(),
            )
    val trashed = existing?.trashed == true

    var title by remember { mutableStateOf(start.title) }
    var body by remember { mutableStateOf(TextFieldValue(start.body)) }
    var labelsText by remember { mutableStateOf(start.labels.joinToString(", ")) }
    var formatting by remember { mutableStateOf(false) }
    val items = remember { mutableStateListOf<NoteItem>().apply { addAll(start.items) } }
    var checklist by remember { mutableStateOf(start.items.isNotEmpty()) }
    var color by remember { mutableStateOf(start.color) }
    var pinned by remember { mutableStateOf(start.pinned) }
    var archived by remember { mutableStateOf(start.archived) }
    val links = remember { mutableStateListOf<NoteLink>().apply { addAll(start.links) } }
    var picking by remember { mutableStateOf(false) }
    var coloring by remember { mutableStateOf(false) }
    var reminderAt by remember { mutableStateOf(start.reminderAt) }
    var reminding by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val media = remember { mutableStateListOf<NoteMedia>().apply { addAll(start.media) } }
    var drawing by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var mediaError by remember { mutableStateOf<String?>(null) }
    val recorderRef = remember { mutableStateOf<VoiceRecorder?>(null) }
    DisposableEffect(Unit) { onDispose { recorderRef.value?.cancel() } }
    val context = LocalContext.current
    val shareChooser = stringResource(R.string.notes_share_chooser)
    fun exportText() = NoteExport.text(title, body.text, items.toList(), checklist)
    val saveTxt =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
            uri ->
            if (uri != null) NoteExport.writeText(context, uri, exportText())
        }

    fun parsedLabels(): List<String> =
        labelsText
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(20)

    fun current(trashedNow: Boolean = trashed) =
        start.copy(
            title = title.trim(),
            body = body.text,
            labels = parsedLabels(),
            items = if (checklist) items.filter { it.text.isNotBlank() } else emptyList(),
            color = color,
            pinned = pinned,
            archived = archived,
            trashed = trashedNow,
            links = links.toList(),
            reminderAt = reminderAt,
            media = media.toList(),
        )

    // ── Saving ───────────────────────────────────────────────────────────
    var savedId by remember { mutableStateOf(existing?.id) }
    var lastWritten by remember { mutableStateOf(existing ?: start) }
    var creating by remember { mutableStateOf(false) }

    fun NoteObject.sameAs(other: NoteObject) =
        copy(id = "", createdAt = null, updatedAt = null) ==
            other.copy(id = "", createdAt = null, updatedAt = null)

    // `this.` throughout: the editor's own title/body/items would shadow the note's.
    fun NoteObject.isBlank() =
        this.title.isEmpty() && this.body.isBlank() && this.items.isEmpty() && this.media.isEmpty()

    fun persist(trashedNow: Boolean = trashed) {
        val note = current(trashedNow)
        val id = savedId
        if (id == null) {
            if (note.isBlank() || creating) return
            creating = true
            viewModel.create(
                NoteDraft(
                    title = note.title,
                    body = note.body,
                    items = note.items,
                    color = note.color,
                    pinned = note.pinned,
                    archived = note.archived,
                    links = note.links,
                    labels = note.labels,
                )
            ) { created ->
                creating = false
                if (created != null) {
                    savedId = created.id
                    lastWritten = created
                    // Typing went on while the create was out, or the editor closed with
                    // the note in the bin: write what the editor holds now.
                    val latest = current(trashedNow).copy(id = created.id)
                    if (!latest.sameAs(created)) {
                        viewModel.save(latest)
                        lastWritten = latest
                    }
                }
            }
            return
        }
        val candidate = note.copy(id = id)
        if (candidate.sameAs(lastWritten)) return
        viewModel.save(candidate)
        lastWritten = candidate
    }

    fun finish(trashedNow: Boolean = trashed) {
        if (recording) {
            recording = false
            recorderRef.value?.cancel()
        }
        persist(trashedNow)
        onClose()
    }

    // ── Pictures, drawings, voice notes ─────────────────────────────────
    // A file needs a note to belong to, so a new note is created first (even blank).
    fun withNoteId(then: (String) -> Unit) {
        savedId?.let {
            then(it)
            return
        }
        if (creating) return
        creating = true
        val note = current()
        viewModel.create(
            NoteDraft(
                title = note.title,
                body = note.body,
                items = note.items,
                color = note.color,
                pinned = note.pinned,
                archived = note.archived,
                links = note.links,
                labels = note.labels,
            )
        ) { created ->
            creating = false
            if (created != null) {
                savedId = created.id
                lastWritten = created
                then(created.id)
            }
        }
    }

    fun upload(kind: String, name: String, type: String, bytes: suspend () -> ByteArray) {
        withNoteId { id ->
            viewModel.addMedia(id, kind, name, type, bytes) { added, error ->
                if (added != null) media.add(added) else mediaError = error
            }
        }
    }

    val pickImages =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) {
            uris ->
            uris.forEach { uri ->
                val type = context.contentResolver.getType(uri) ?: "image/jpeg"
                upload(NoteMedia.IMAGE, uri.lastPathSegment ?: "picture", type) {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: ByteArray(0)
                    }
                }
            }
        }

    fun startRecording() {
        val recorder = recorderRef.value ?: VoiceRecorder(context).also { recorderRef.value = it }
        runCatching { recorder.start() }
            .onSuccess { recording = true }
            .onFailure { mediaError = it.message }
    }

    fun stopRecording() {
        recording = false
        val file = recorderRef.value?.stop() ?: return
        upload(NoteMedia.AUDIO, "voice-note.m4a", "audio/mp4") {
            withContext(Dispatchers.IO) { file.readBytes().also { file.delete() } }
        }
    }

    val micDenied = stringResource(R.string.notes_media_mic_denied)
    val askMic =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording() else mediaError = micDenied
        }

    // ── Undo / redo ──────────────────────────────────────────────────────
    data class Snap(
        val title: String,
        val body: String,
        val items: List<NoteItem>,
        val checklist: Boolean,
        val color: String?,
        val labels: String,
    )

    fun snap() = Snap(title, body.text, items.toList(), checklist, color, labelsText)

    val history = remember { mutableStateListOf(snap()) }
    var position by remember { mutableIntStateOf(0) }

    fun restore(to: Snap) {
        title = to.title
        body = TextFieldValue(to.body)
        items.clear()
        items.addAll(to.items)
        checklist = to.checklist
        color = to.color
        labelsText = to.labels
    }

    val now = snap()
    LaunchedEffect(now) {
        delay(AUTOSAVE_DELAY_MS)
        if (now != history.getOrNull(position)) {
            while (history.size > position + 1) history.removeAt(history.lastIndex)
            history.add(now)
            if (history.size > HISTORY_LIMIT) history.removeAt(0)
            position = history.lastIndex
        }
        if (!trashed) persist()
    }

    BackHandler { finish() }

    val container = noteContainer(color)
    val content = noteContent(color)

    Scaffold(
        containerColor = container,
        topBar = {
            TopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = container,
                        titleContentColor = content,
                        navigationIconContentColor = content,
                        actionIconContentColor = content,
                    ),
                title = {},
                navigationIcon = {
                    IconButton(onClick = { finish() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.notes_back),
                        )
                    }
                },
                actions = {
                    if (trashed) {
                        IconButton(
                            onClick = {
                                viewModel.restore(existing)
                                onClose()
                            }
                        ) {
                            Icon(
                                Icons.Outlined.RestoreFromTrash,
                                stringResource(R.string.notes_restore),
                            )
                        }
                        IconButton(
                            onClick = {
                                viewModel.destroy(existing)
                                onClose()
                            }
                        ) {
                            Icon(
                                Icons.Outlined.DeleteForever,
                                stringResource(R.string.notes_delete_forever),
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                pinned = !pinned
                                if (pinned) archived = false
                            }
                        ) {
                            Icon(
                                Icons.Outlined.PushPin,
                                stringResource(
                                    if (pinned) R.string.notes_unpin else R.string.notes_pin
                                ),
                                tint = if (pinned) MaterialTheme.colorScheme.primary else content,
                            )
                        }
                        IconButton(
                            onClick = {
                                archived = !archived
                                if (archived) pinned = false
                                finish()
                            }
                        ) {
                            Icon(
                                if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                                stringResource(
                                    if (archived) R.string.notes_unarchive
                                    else R.string.notes_archive
                                ),
                            )
                        }
                        Box {
                            IconButton(onClick = { sharing = true }) {
                                Icon(Icons.Outlined.Share, stringResource(R.string.notes_share))
                            }
                            DropdownMenu(
                                expanded = sharing,
                                onDismissRequest = { sharing = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.notes_share_text)) },
                                    onClick = {
                                        sharing = false
                                        NoteExport.shareText(
                                            context,
                                            title,
                                            exportText(),
                                            shareChooser,
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.notes_share_png)) },
                                    onClick = {
                                        sharing = false
                                        NoteExport.sharePng(
                                            context,
                                            title,
                                            body.text,
                                            items.toList(),
                                            checklist,
                                            noteColorArgb(color, dark = false)?.let { Color(it) }
                                                ?: Color.White,
                                            shareChooser,
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.notes_save_txt)) },
                                    onClick = {
                                        sharing = false
                                        saveTxt.launch("${title.ifBlank { "note" }.take(60)}.txt")
                                    },
                                )
                            }
                        }
                        if (existing != null) {
                            IconButton(onClick = { finish(trashedNow = true) }) {
                                Icon(Icons.Outlined.Delete, stringResource(R.string.notes_delete))
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!trashed) {
                // A Scaffold's bottomBar is drawn edge to edge: without these the
                // toolbar sat under the gesture/navigation bar, and under the
                // keyboard while typing.
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    if (formatting && !checklist) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            listOf(
                                    Triple(
                                        "**",
                                        Icons.Outlined.FormatBold,
                                        R.string.notes_format_bold,
                                    ),
                                    Triple(
                                        "*",
                                        Icons.Outlined.FormatItalic,
                                        R.string.notes_format_italic,
                                    ),
                                    Triple(
                                        "__",
                                        Icons.Outlined.FormatUnderlined,
                                        R.string.notes_format_underline,
                                    ),
                                    Triple(
                                        "# ",
                                        Icons.Outlined.Title,
                                        R.string.notes_format_heading,
                                    ),
                                    Triple(
                                        "- ",
                                        Icons.AutoMirrored.Outlined.FormatListBulleted,
                                        R.string.notes_format_bullets,
                                    ),
                                )
                                .forEach { (mark, icon, label) ->
                                    IconButton(onClick = { body = applyMark(body, mark) }) {
                                        Icon(icon, stringResource(label), tint = content)
                                    }
                                }
                        }
                    }
                    if (coloring) {
                        ColorRow(
                            selected = color,
                            onPick = {
                                color = it
                                coloring = false
                            },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (!checklist) {
                            IconButton(onClick = { formatting = !formatting }) {
                                Icon(
                                    Icons.Outlined.TextFormat,
                                    stringResource(R.string.notes_format),
                                    tint = content,
                                )
                            }
                        }
                        IconButton(onClick = { coloring = !coloring }) {
                            Icon(
                                Icons.Outlined.Palette,
                                stringResource(R.string.notes_color),
                                tint = content,
                            )
                        }
                        IconButton(
                            onClick = {
                                if (!checklist) {
                                    if (items.isEmpty() && body.text.isNotBlank()) {
                                        items.addAll(
                                            body.text
                                                .lines()
                                                .filter { it.isNotBlank() }
                                                .map { NoteItem(it, false) }
                                        )
                                        body = TextFieldValue("")
                                    }
                                    if (items.isEmpty()) items.add(NoteItem())
                                    checklist = true
                                } else {
                                    body =
                                        TextFieldValue(items.joinToString("\n") { it.text }.trim())
                                    items.clear()
                                    checklist = false
                                }
                            }
                        ) {
                            Icon(
                                Icons.Outlined.CheckBox,
                                stringResource(
                                    if (checklist) R.string.notes_hide_checkboxes
                                    else R.string.notes_show_checkboxes
                                ),
                                tint = content,
                            )
                        }
                        IconButton(
                            onClick = {
                                pickImages.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        ) {
                            Icon(
                                Icons.Outlined.Image,
                                stringResource(R.string.notes_media_add_image),
                                tint = content,
                            )
                        }
                        IconButton(onClick = { drawing = true }) {
                            Icon(
                                Icons.Outlined.Brush,
                                stringResource(R.string.notes_media_draw),
                                tint = content,
                            )
                        }
                        IconButton(
                            onClick = {
                                when {
                                    recording -> stopRecording()
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO,
                                    ) == PackageManager.PERMISSION_GRANTED -> startRecording()
                                    else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        ) {
                            Icon(
                                if (recording) Icons.Filled.Stop else Icons.Outlined.Mic,
                                stringResource(
                                    if (recording) R.string.notes_media_stop
                                    else R.string.notes_media_record
                                ),
                                tint = if (recording) MaterialTheme.colorScheme.error else content,
                            )
                        }
                        IconButton(onClick = { reminding = true }) {
                            Icon(
                                Icons.Outlined.NotificationsNone,
                                stringResource(R.string.notes_reminder),
                                tint = content,
                            )
                        }
                        IconButton(onClick = { picking = true }) {
                            Icon(
                                Icons.Outlined.Link,
                                stringResource(R.string.notes_add_link),
                                tint = content,
                            )
                        }
                        IconButton(
                            enabled = position > 0,
                            onClick = {
                                position -= 1
                                restore(history[position])
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Undo,
                                stringResource(R.string.notes_undo),
                                tint = content.copy(alpha = if (position > 0) 1f else 0.35f),
                            )
                        }
                        IconButton(
                            enabled = position < history.lastIndex,
                            onClick = {
                                position += 1
                                restore(history[position])
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Redo,
                                stringResource(R.string.notes_redo),
                                tint =
                                    content.copy(
                                        alpha = if (position < history.lastIndex) 1f else 0.35f
                                    ),
                            )
                        }
                        if (onAddToCalendar != null) {
                            IconButton(
                                onClick = {
                                    val text =
                                        if (checklist) items.joinToString("\n") { "• ${it.text}" }
                                        else body.text
                                    onAddToCalendar(title.trim(), text)
                                }
                            ) {
                                Icon(
                                    Icons.Outlined.EventAvailable,
                                    stringResource(R.string.notes_add_to_calendar),
                                    tint = content,
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            val colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = content,
                    unfocusedTextColor = content,
                    disabledTextColor = content,
                )

            NoteMediaStrip(
                media = media.toList(),
                editable = !trashed,
                load = { item -> savedId?.let { viewModel.mediaFile(it, item) } },
                onRemove = { item ->
                    media.remove(item)
                    savedId?.let { viewModel.removeMedia(it, item.id) }
                },
            )
            mediaError?.let { message ->
                Text(
                    stringResource(R.string.notes_media_failed, message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable { mediaError = null }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            TextField(
                value = title,
                onValueChange = { title = it.replace("\n", " ") },
                readOnly = trashed,
                placeholder = { Text(stringResource(R.string.notes_title_hint)) },
                textStyle = MaterialTheme.typography.titleLarge,
                colors = colors,
                modifier = Modifier.fillMaxWidth(),
            )

            if (checklist) {
                items.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = item.done,
                            enabled = !trashed,
                            onCheckedChange = { items[index] = item.copy(done = it) },
                        )
                        TextField(
                            value = item.text,
                            onValueChange = {
                                items[index] = item.copy(text = it.replace("\n", " "))
                            },
                            readOnly = trashed,
                            placeholder = { Text(stringResource(R.string.notes_item_hint)) },
                            colors = colors,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions =
                                KeyboardActions(onNext = { items.add(index + 1, NoteItem()) }),
                            modifier = Modifier.weight(1f),
                        )
                        if (!trashed) {
                            IconButton(onClick = { items.removeAt(index) }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    stringResource(R.string.notes_delete),
                                    tint = content,
                                )
                            }
                        }
                    }
                }
                if (!trashed) {
                    Row(
                        Modifier.fillMaxWidth().clickable { items.add(NoteItem()) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "+  " + stringResource(R.string.notes_add_item),
                            color = content.copy(alpha = 0.7f),
                        )
                    }
                }
            } else {
                TextField(
                    value = body,
                    onValueChange = { body = it },
                    readOnly = trashed,
                    placeholder = { Text(stringResource(R.string.notes_body_hint)) },
                    colors = colors,
                    visualTransformation = NoteMarkupTransformation(content.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            reminderAt?.let { at ->
                InputChip(
                    selected = false,
                    onClick = { if (!trashed) reminding = true },
                    label = {
                        Text(
                            NoteReminderTimes.label(at),
                            textDecoration =
                                if (NoteReminderTimes.isPast(at)) TextDecoration.LineThrough
                                else null,
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.NotificationsNone, null, Modifier.size(18.dp))
                    },
                    trailingIcon =
                        if (trashed) null
                        else {
                            {
                                Icon(
                                    Icons.Outlined.Close,
                                    stringResource(R.string.notes_reminder_remove),
                                    Modifier.size(18.dp).clickable {
                                        reminderAt = null
                                        persist()
                                    },
                                )
                            }
                        },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (links.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    links.toList().forEach { link ->
                        InputChip(
                            selected = false,
                            onClick = { onOpenLink?.invoke(link) },
                            label = {
                                Text(
                                    link.title ?: link.id,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = { Icon(linkIcon(link.type), null, Modifier.size(18.dp)) },
                            trailingIcon =
                                if (trashed) null
                                else {
                                    {
                                        Icon(
                                            Icons.Outlined.Close,
                                            stringResource(R.string.notes_remove_link),
                                            Modifier.size(18.dp).clickable { links.remove(link) },
                                        )
                                    }
                                },
                        )
                    }
                }
            }
            TextField(
                value = labelsText,
                onValueChange = { labelsText = it.replace("\n", " ") },
                readOnly = trashed,
                placeholder = { Text(stringResource(R.string.notes_labels_hint)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, null, tint = content) },
                singleLine = true,
                colors = colors,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (drawing) {
        DrawingDialog(
            onSave = { png ->
                drawing = false
                upload(NoteMedia.DRAWING, "drawing.png", "image/png") { png }
            },
            onDismiss = { drawing = false },
        )
    }

    if (reminding) {
        NoteReminderDialog(
            hasReminder = reminderAt != null,
            onPick = { picked ->
                reminderAt = picked
                reminding = false
                persist()
            },
            onDismiss = { reminding = false },
        )
    }

    if (picking) {
        LinkPickerDialog(
            onPick = { picked ->
                links.removeAll { it.type == picked.type && it.id == picked.id }
                links.add(picked)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun ColorRow(selected: String?, onPick: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (listOf<String?>(null) + NoteColorNames).forEach { name ->
            Box(
                Modifier.size(28.dp)
                    .background(noteContainer(name), CircleShape)
                    .border(
                        width = if (name == selected) 3.dp else 1.dp,
                        color =
                            if (name == selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    )
                    .clickable { onPick(name) }
            )
        }
    }
}

/** How long after the last keystroke the editor saves (and records an undo step). */
private const val AUTOSAVE_DELAY_MS = 700L

/** Undo steps kept per editing session. */
private const val HISTORY_LIMIT = 100
