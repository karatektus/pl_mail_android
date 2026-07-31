package de.plmail.feature.mail.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.feature.mail.R
import de.plmail.feature.mail.asListDate

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
            TopAppBar(
                title = {
                    Text(
                        text =
                            state.subject?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.no_subject),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                HorizontalDivider()
            }
        }
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

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.email.fromName ?: message.email.fromAddress.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!message.isExpanded) {
                    Text(
                        text = message.email.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text = (message.email.receivedAt ?: 0L).asListDate(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!message.isExpanded) return@Column

        if (body == null) {
            Text(
                text = stringResource(R.string.body_not_downloaded),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return@Column
        }

        Row(modifier = Modifier.padding(horizontal = 8.dp)) {
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
    }
}
