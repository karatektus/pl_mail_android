package de.plmail.feature.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.core.database.ThreadEntity

/**
 * One conversation in the list.
 *
 * Everything drawn here comes off the row itself — the thread table is denormalised precisely so
 * that fifty of these can scroll at 120fps without a join or a lazy load per row.
 */
@Composable
fun ThreadRow(thread: ThreadEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val unreadWeight = if (thread.isUnread) FontWeight.Bold else FontWeight.Normal

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clearAndSetSemantics { contentDescription = thread.spoken() },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(thread)

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text =
                        thread.participantsSummary.ifBlank { stringResource(R.string.no_sender) },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = unreadWeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (thread.messageCount > 1) {
                    Text(
                        text = thread.messageCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                // "(no subject)" rather than an empty line: a blank row looks
                // like a rendering failure, and mail genuinely arrives without
                // a subject.
                text =
                    thread.subject?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.no_subject),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = unreadWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = thread.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = thread.latestReceivedAt.asListDate(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (thread.hasAttachment) {
                    Icon(
                        imageVector = Icons.Outlined.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (thread.isFlagged) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * A letter avatar coloured from the sender's **address**.
 *
 * Never the display name. People change how their client spells their name — adding a middle
 * initial, switching to lower case — and colouring from that recolours the same person's avatar for
 * no reason the user can see.
 */
@Composable
private fun Avatar(thread: ThreadEntity) {
    val seed = thread.participantsAddress ?: thread.participantsSummary

    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(avatarColour(seed))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = avatarLetter(seed),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}

/** What the screen reader says, as one sentence rather than eight disconnected fragments. */
private fun ThreadEntity.spoken(): String = buildList {
    if (isUnread) add("Unread")
    add(participantsSummary.ifBlank { "Unknown sender" })
    add(subject?.takeIf { it.isNotBlank() } ?: "No subject")
    if (messageCount > 1) add("$messageCount messages")
    if (hasAttachment) add("has attachment")
    if (isFlagged) add("starred")
}
    .joinToString(", ")
