package de.plmail.feature.mail.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.data.MailAction
import de.plmail.core.designsystem.PlMailAvatar
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.ui.R as UiR
import de.plmail.core.ui.asListDate
import de.plmail.feature.mail.R
import de.plmail.feature.mail.SnoozeMenu
import de.plmail.feature.mail.SnoozePicker

/** The letter shown on a sender's avatar. Skips punctuation, so "+ada@…" is still an A. */
private fun avatarInitial(seed: String): String =
    seed.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

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
    val isDark = isSystemInDarkTheme()

    LaunchedEffect(accountKey, threadId) { viewModel.open(accountKey, threadId, subject) }

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
    ) { insets ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(insets)) {
            items(items = state.messages, key = { it.email.uid }) { message ->
                Message(
                    message = message,
                    isDark = isDark,
                    onToggle = { viewModel.toggleExpanded(message.email.uid) },
                    onShowImages = { viewModel.allowRemoteImages(message.email.uid) },
                    onToggleOriginal = { viewModel.toggleOriginal(message.email.uid) },
                    onDisplayed = { viewModel.markRead(accountKey, message.email.uid) },
                )

                // Under the message they answer, rather than in the app bar.
                // A thread is several messages and "reply" has to mean "to this
                // one" -- replying to the newest when the user was reading the
                // third quotes the wrong text and threads against the wrong id.
                if (message.isExpanded) {
                    ReplyActions(
                        canReplyAll = message.hasOtherRecipients,
                        onReply = { onReply(message.email.emailId, false) },
                        onReplyAll = { onReply(message.email.emailId, true) },
                        onForward = { onForward(message.email.emailId) },
                    )
                }

                PlMailDivider()
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
    onToggle: () -> Unit,
    onShowImages: () -> Unit,
    onToggleOriginal: () -> Unit,
    onDisplayed: () -> Unit,
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

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.small)) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .heightIn(min = spacing.touchTarget)
                    .padding(horizontal = spacing.gutter, vertical = spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val seed = message.email.fromAddress ?: message.email.fromName.orEmpty()

            PlMailAvatar(seed = seed, label = avatarInitial(seed))

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
        }

        if (!message.isExpanded) return@Column

        if (body == null) {
            Text(
                text = stringResource(R.string.body_not_downloaded),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                modifier = Modifier.padding(horizontal = spacing.gutter),
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

        MessageWebView(
            body = body,
            style = style,
            remoteImages = message.remoteImages,
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.small),
        )
    }
}
