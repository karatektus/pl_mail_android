package de.plmail.feature.mail.reader

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.data.MailAction
import de.plmail.core.database.AttachmentEntity
import de.plmail.core.designsystem.PlMailAvatar
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.ui.R as UiR
import de.plmail.core.ui.asListDate
import de.plmail.feature.mail.R
import de.plmail.feature.mail.SnoozeMenu
import de.plmail.feature.mail.SnoozePicker

/**
 * The letter shown on a sender's avatar. Skips punctuation, so "+ada@…" is still an A.
 *
 * The **display name** is preferred over the address here, which is the opposite of what the avatar
 * *colour* is hashed from and deliberately so. Colour has to be stable for a person, so it comes
 * from the address; the letter is a label for a human reading it, and "Anthropic, PBC" showing an I
 * because the address is `invoice+statements@…` reads as a bug rather than as a hash.
 */
private fun avatarInitial(name: String?, address: String?): String =
    (name.orEmpty() + address.orEmpty()).firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

/**
 * One conversation.
 *
 * Newest expanded, older collapsed. A thread of thirty is otherwise a wall of quoted text, and the
 * newest message is nearly always why it was opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    accountKey: String,
    threadId: String,
    subject: String?,
    onReply: (emailId: String, all: Boolean) -> Unit,
    onForward: (emailId: String) -> Unit,
    /** Null where the list is already on screen beside this, and there is nothing to go back to. */
    onBack: (() -> Unit)? = null,
    /**
     * Applies an action to the conversation on screen.
     *
     * Hoisted rather than owned, and the reason is the undo. Archiving from here closes the reader,
     * so a snackbar hosted *by* the reader would leave with it — taking the way back with it, on
     * exactly the actions where the way back matters most. [MailPane] outlives both panes and is
     * where the announcement belongs.
     */
    onAction: (MailAction) -> Unit = {},
    /** Opens the "Label as" sheet, which is hosted a level up for the same reason. */
    onLabel: () -> Unit = {},
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The *app's* scheme, never the system's. `isSystemInDarkTheme()` was here
    // and it is wrong in both directions once there are six themes: Nord on a
    // phone in light mode drew every message as sent -- a white newsletter in a
    // dark app -- and Solar on a phone in dark mode inverted messages onto a
    // near-black that the light theme around them never uses.
    val colors = PlMailTheme.colors
    val isDark = colors.isDark
    val palette = remember(colors) { MessagePalette.of(colors) }

    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(accountKey, threadId) { viewModel.open(accountKey, threadId, subject) }

    // The attachment the save picker is currently being opened for. Held here
    // rather than passed through the launcher because `CreateDocument` hands
    // back a Uri and nothing else -- there is no room in the contract for
    // "which of the four attachments this was".
    var saving by remember { mutableStateOf<AttachmentEntity?>(null) }

    val savePicker =
        rememberLauncherForActivityResult(
            // The mime type is supplied per launch below; this one is the
            // contract's fallback and is never what the picker actually uses.
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { destination ->
            val attachment = saving
            saving = null

            // Null means the user backed out of the picker, which is not a
            // failure and must not be announced as one.
            if (destination != null && attachment != null) {
                viewModel.saveAttachment(attachment, destination)
            }
        }

    // Collected as events rather than read off the state: opening a file is a
    // one-off, and a rotation must not relaunch the document viewer.
    LaunchedEffect(viewModel) {
        viewModel.open.collect { openable -> context.openExternally(openable) }
    }

    state.failure?.let { failure ->
        val message =
            stringResource(
                when (failure.what) {
                    FailedAt.DOWNLOAD -> R.string.attachment_download_failed
                    FailedAt.SAVE -> R.string.attachment_save_failed
                    FailedAt.SOURCE -> R.string.source_failed
                }
            )

        // Keyed on the id, so two identical failures are two snackbars rather
        // than one -- `showSnackbar` suspends until dismissed, and a key on the
        // message alone would swallow the second.
        LaunchedEffect(failure.id) {
            // The server's own words after ours. This audience runs the server,
            // and "Connection refused" is the diagnosis; a translated sentence
            // on its own tells them only that something they already saw fail
            // has failed.
            snackbars.showSnackbar(
                failure.detail?.takeIf { it.isNotBlank() }?.let { "$message $it" } ?: message
            )
            viewModel.failureShown(failure.id)
        }
    }

    state.source?.let { source ->
        MessageSourceSheet(
            source = source,
            onClose = viewModel::closeSource,
            onShare = { source.text?.let { context.shareText(source.title, it) } },
        )
    }

    // A Scaffold rather than a bare LazyColumn: the reader is a top-level pane
    // and nothing above it applies window insets, so without this the subject
    // renders underneath the status bar.
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ReaderBar(
                subject =
                    state.subject?.takeIf { it.isNotBlank() }
                        ?: stringResource(UiR.string.no_subject),
                isSnoozed = state.snoozedUntil != null,
                onBack = onBack,
                onAction = onAction,
                onLabel = onLabel,
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = {
            // The conversation's newest message, expanded or not. A thread is
            // answered at its end; the per-message row inside each card is what
            // answers a particular one.
            state.messages.lastOrNull()?.let { newest ->
                ReaderActionBar(
                    onReply = { onReply(newest.email.emailId, false) },
                    onForward = { onForward(newest.email.emailId) },
                )
            }
        },
    ) { insets ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(insets)) {
            items(items = state.messages, key = { it.email.uid }) { message ->
                Message(
                    message = message,
                    isDark = isDark,
                    palette = palette,
                    busyAttachments = state.busyAttachments,
                    onToggle = { viewModel.toggleExpanded(message.email.uid) },
                    onShowImages = { viewModel.allowRemoteImages(message.email.uid) },
                    onToggleOriginal = { viewModel.toggleOriginal(message.email.uid) },
                    onDisplayed = { viewModel.markRead(accountKey, message.email.uid) },
                    onOpenAttachment = viewModel::openAttachment,
                    onSaveAttachment = { attachment ->
                        saving = attachment
                        savePicker.launch(attachment.name ?: DEFAULT_SAVE_NAME)
                    },
                    onShowSource = { viewModel.showSource(message) },
                    onReply = { all -> onReply(message.email.emailId, all) },
                    onForward = { onForward(message.email.emailId) },
                )
            }
        }
    }
}

/**
 * Reply and forward, always on screen.
 *
 * **Structural, not decorative.** The reply actions used to live only under the message they
 * answer, which meant reaching them depended on the reader being able to scroll past a body whose
 * height is measured by a WebView. On a message that measured badly — or, before [ReaderWebView],
 * on any message at all, because the body ate the drag — the two verbs a mail client exists for
 * could not be reached and the app was unusable on that message. Pinning them removes the
 * dependency: whatever the body does, these are reachable in one tap.
 *
 * Two buttons, not three. Reply-all stays in the per-message row, where the "would this reach
 * anyone a plain reply would not" test is computed and where there is room for it — three pills of
 * German ("Antworten", "Allen antworten", "Weiterleiten") do not fit a 320dp phone without
 * ellipsis, and a button reading "Allen antwo…" is worse than one more scroll.
 */
@Composable
private fun ReaderActionBar(onReply: () -> Unit, onForward: () -> Unit) {
    val spacing = PlMailTheme.spacing
    val colors = PlMailTheme.colors

    Column(modifier = Modifier.background(colors.surface).navigationBarsPadding()) {
        // The bar is part of the page, separated by a line rather than lifted by
        // a shadow, like every other edge in this app.
        PlMailDivider()

        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = spacing.gutter, vertical = spacing.small),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            val tonal =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = colors.accentSoft,
                    contentColor = colors.accent,
                )

            FilledTonalButton(
                onClick = onReply,
                modifier = Modifier.weight(1f).heightIn(min = spacing.touchTarget),
                colors = tonal,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Reply,
                    contentDescription = null,
                    modifier = Modifier.padding(end = spacing.tiny),
                )
                Text(
                    text = stringResource(R.string.reader_reply),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FilledTonalButton(
                onClick = onForward,
                modifier = Modifier.weight(1f).heightIn(min = spacing.touchTarget),
                colors = tonal,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Forward,
                    contentDescription = null,
                    modifier = Modifier.padding(end = spacing.tiny),
                )
                Text(
                    text = stringResource(R.string.reader_forward),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The reader's chrome: what to do with this conversation, and the way out.
 *
 * The two destructive moves are buttons and everything else is in the overflow, matching the
 * selection bar above the list — the same conversation must not offer a different set of verbs
 * depending on whether it is open. Reply is deliberately *not* here: it lives under the message it
 * answers, because a thread has several and "reply" from an app bar quotes whichever one the code
 * picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderBar(
    subject: String,
    isSnoozed: Boolean,
    onBack: (() -> Unit)?,
    onAction: (MailAction) -> Unit,
    onLabel: () -> Unit,
) {
    var isMenuOpen by remember { mutableStateOf(false) }
    var isSnoozeOpen by remember { mutableStateOf(false) }
    var isPickingTime by remember { mutableStateOf(false) }

    if (isPickingTime) {
        SnoozePicker(
            onDismiss = { isPickingTime = false },
            onChosen = {
                isPickingTime = false
                onAction(MailAction.Snooze(it.toEpochMilli()))
            },
        )
    }

    TopAppBar(
        colors =
            TopAppBarDefaults.topAppBarColors(
                // The bar is part of the page rather than a separate plane, as
                // everywhere else in this app: Material's default tints it as
                // the content scrolls under it, which reintroduces the elevation
                // model this design does not use.
                containerColor = PlMailTheme.colors.surface,
                scrolledContainerColor = PlMailTheme.colors.surface,
                titleContentColor = PlMailTheme.colors.ink,
                navigationIconContentColor = PlMailTheme.colors.inkSoft,
                actionIconContentColor = PlMailTheme.colors.inkSoft,
            ),
        title = { Text(text = subject, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            onBack?.let { back ->
                IconButton(onClick = back) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.reader_back),
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = { onAction(MailAction.Archive) }) {
                Icon(
                    imageVector = Icons.Outlined.Archive,
                    contentDescription = stringResource(R.string.action_archive),
                )
            }
            IconButton(onClick = { onAction(MailAction.Trash) }) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_trash),
                )
            }
            IconButton(onClick = { isMenuOpen = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.more),
                )
            }

            DropdownMenu(expanded = isMenuOpen, onDismissRequest = { isMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_star)) },
                    onClick = {
                        isMenuOpen = false
                        onAction(MailAction.Star(flagged = true))
                    },
                )
                // Marking unread rather than read: the reader has just marked
                // every message it showed as read, so "mark read" here is a
                // control that never does anything.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_unread)) },
                    onClick = {
                        isMenuOpen = false
                        onAction(MailAction.MarkRead(seen = false))
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_spam)) },
                    onClick = {
                        isMenuOpen = false
                        onAction(MailAction.MarkSpam)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.labels_apply)) },
                    onClick = {
                        isMenuOpen = false
                        onLabel()
                    },
                )
                if (isSnoozed) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.unsnooze)) },
                        onClick = {
                            isMenuOpen = false
                            onAction(MailAction.Snooze(null))
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.snooze)) },
                        onClick = {
                            isMenuOpen = false
                            isSnoozeOpen = true
                        },
                    )
                }
            }

            SnoozeMenu(
                isOpen = isSnoozeOpen,
                onDismiss = { isSnoozeOpen = false },
                onChosen = { at -> onAction(MailAction.Snooze(at.toEpochMilli())) },
                onPickExact = { isPickingTime = true },
            )
        },
    )
}

/**
 * Reply, reply-all and forward for one message.
 *
 * Reply-all appears only when it would reach someone the plain reply does not. A button that sends
 * to exactly the same person under a different name teaches people not to trust the difference,
 * which is how a private reply eventually goes to a mailing list.
 */
@Composable
private fun ReplyActions(
    canReplyAll: Boolean,
    onReply: () -> Unit,
    onReplyAll: () -> Unit,
    onForward: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = PlMailTheme.spacing.medium,
                    vertical = PlMailTheme.spacing.tiny,
                ),
        horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
    ) {
        TextButton(onClick = onReply) { Text(stringResource(R.string.reader_reply)) }

        if (canReplyAll) {
            TextButton(onClick = onReplyAll) { Text(stringResource(R.string.reader_reply_all)) }
        }

        TextButton(onClick = onForward) { Text(stringResource(R.string.reader_forward)) }
    }
}

@Composable
private fun Message(
    message: ReaderMessage,
    isDark: Boolean,
    palette: MessagePalette,
    busyAttachments: Set<String>,
    onToggle: () -> Unit,
    onShowImages: () -> Unit,
    onToggleOriginal: () -> Unit,
    onDisplayed: () -> Unit,
    onOpenAttachment: (AttachmentEntity) -> Unit,
    onSaveAttachment: (AttachmentEntity) -> Unit,
    onShowSource: () -> Unit,
    onReply: (all: Boolean) -> Unit,
    onForward: () -> Unit,
) {
    val body = message.body

    val profile = remember(body) { MessageColorProfile.of(body.orEmpty()) }
    val style = if (message.showOriginal) MessageRenderStyle.ORIGINAL else profile.styleFor(isDark)

    // Keyed on the message being expanded, so it fires when the body is
    // actually on screen rather than when the thread was loaded. Read-on-
    // prefetch would clear the unread badge on mail nobody has seen, and the
    // user cannot undo that because they no longer know what they missed.
    LaunchedEffect(message.email.uid, message.isExpanded) {
        if (message.isExpanded) onDisplayed()
    }

    val spacing = PlMailTheme.spacing
    val colors = PlMailTheme.colors

    // A card, in both layouts, and it is the one place this app boxes something
    // the flat layout would otherwise leave on the page. The reason is not
    // decoration: a message body is sender-authored paper with a background of
    // its own, and running it to the screen edge means the sender's colour and
    // the app's chrome meet with nothing between them. That is what the black
    // band down the left was -- the dark style's own page colour showing through
    // the document's padding, against the app's surface, looking like a
    // rendering fault. Bounded and inset, the message reads as an object and the
    // seam has nowhere to appear.
    //
    // radii.control rather than radii.pane, because pane is zero in the flat
    // layout by design and this shape has to exist in both.
    val shape = RoundedCornerShape(PlMailTheme.radii.control)

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = spacing.small, vertical = spacing.tiny)
                .background(colors.raised, shape)
                .border(spacing.hair, colors.line, shape)
                .clip(shape)
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .heightIn(min = spacing.touchTarget)
                    .padding(horizontal = spacing.medium, vertical = spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val seed = message.email.fromAddress ?: message.email.fromName.orEmpty()

            PlMailAvatar(
                seed = seed,
                label = avatarInitial(message.email.fromName, message.email.fromAddress),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.email.fromName ?: message.email.fromAddress.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!message.isExpanded) {
                    Text(
                        text = message.email.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text = (message.email.receivedAt ?: 0L).asListDate(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )

            // Per message, not per conversation, for the same reason reply is:
            // a thread has several messages and "the source" of a conversation
            // is not a thing that exists. Only offered once a message is open,
            // because that is the one the user is looking at.
            if (message.isExpanded && message.email.blobId != null) {
                MessageMenu(onShowSource = onShowSource)
            }
        }

        if (!message.isExpanded) return@Column

        if (body == null) {
            Text(
                text = stringResource(R.string.body_not_downloaded),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                modifier = Modifier.padding(horizontal = spacing.medium, vertical = spacing.small),
            )
            return@Column
        }

        Row(modifier = Modifier.padding(horizontal = spacing.small)) {
            if (message.remoteImages == RemoteImages.BLOCKED && profile.hasImagery) {
                // Named rather than silent. A message with its pictures
                // suppressed and no explanation looks broken, and the reason
                // is one the user should get to weigh.
                TextButton(onClick = onShowImages) {
                    Text(stringResource(R.string.show_remote_images))
                }
            }

            // Offered wherever the rendering was transformed, per the product's
            // rule that a message may always be seen as it was sent.
            if (style.isTransformed || message.showOriginal) {
                TextButton(onClick = onToggleOriginal) {
                    Text(
                        stringResource(
                            if (message.showOriginal) R.string.show_adapted
                            else R.string.show_original
                        )
                    )
                }
            }
        }

        // No horizontal padding, and that is deliberate: the document carries
        // its own 12px inset and the card carries the rest, so the message's
        // paper reaches the card's edge. Padding here would open a strip of card
        // colour between the two that the sender's own background stops short
        // of -- the band this card exists to remove.
        MessageWebView(
            body = body,
            style = style,
            palette = palette,
            remoteImages = message.remoteImages,
            modifier = Modifier.fillMaxWidth(),
        )

        // Under the body and above the reply buttons, which is where the eye
        // arrives after reading: an attachment list above the message competes
        // with the message for the first look, and the message is what was
        // opened.
        Attachments(
            attachments = message.attachments,
            busy = busyAttachments,
            onOpen = onOpenAttachment,
            onSave = onSaveAttachment,
        )

        // Inside the card, under the message they answer. A thread is several
        // messages and "reply" has to mean "to this one" -- replying to the
        // newest when the user was reading the third quotes the wrong text and
        // threads against the wrong id. The pinned bar answers the conversation;
        // this answers a message.
        ReplyActions(
            canReplyAll = message.hasOtherRecipients,
            onReply = { onReply(false) },
            onReplyAll = { onReply(true) },
            onForward = onForward,
        )
    }
}

/** The per-message overflow. One item today; a menu because the next ones belong beside it. */
@Composable
private fun MessageMenu(onShowSource: () -> Unit) {
    var isOpen by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { isOpen = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.message_more),
            )
        }

        DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.source_show)) },
                onClick = {
                    isOpen = false
                    onShowSource()
                },
            )
        }
    }
}

/**
 * Hands a downloaded file to whatever the user has installed for it.
 *
 * A `content://` URI from the app's own FileProvider, never a `file://` one: since Android 7 the
 * latter is a `FileUriExposedException` rather than a permission failure, so there is no version of
 * this that "mostly works". The grant travels on the intent and expires with it.
 *
 * A chooser rather than the default handler, deliberately. Attachments are frequently the one thing
 * in this product that leaves the user's own server, and silently launching whichever app claimed
 * `application/pdf` first is the moment to give them the choice.
 */
private fun Context.openExternally(openable: OpenableFile) {
    val uri = FileProvider.getUriForFile(this, "$packageName.blobs", openable.file)

    val view =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, openable.type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    val chooser =
        Intent.createChooser(view, getString(R.string.attachment_open_with)).apply {
            // The chooser is started from a non-Activity context here, and
            // without this it is a "Calling startActivity() from outside of an
            // Activity context" crash rather than a chooser.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    // A device with nothing installed for the type is a real outcome -- a
    // `.eml` or a `.ics` on a stripped-down phone -- and it must not take the
    // app down. The chooser itself reports "no apps can perform this action".
    runCatching { startActivity(chooser) }
}

/**
 * Shares the message source as text.
 *
 * `EXTRA_TEXT` rather than a file, because the only sensible destinations are a note, a chat or a
 * bug report, and every one of those wants text it can quote. A `.eml` attachment would arrive as
 * something the recipient has to open in a mail client to read.
 */
private fun Context.shareText(title: String, text: String) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }

    runCatching {
        startActivity(
            Intent.createChooser(send, title).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }
}

/** What the save picker suggests for a part that arrived with no filename. */
private const val DEFAULT_SAVE_NAME = "attachment"
