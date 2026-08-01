package de.plmail.feature.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import de.plmail.core.data.Label
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailTheme

/**
 * The label list, as the app's navigation.
 *
 * One row per label rather than per mailbox: the collapsing happens in `:core:data`, so what
 * arrives here is already the list the user believes they have. See `Labels.kt` for why that must
 * never be done by matching names.
 *
 * Flat, with nested labels drawn as `Work/Invoices`. A tree with disclosure triangles hides the
 * label somebody is looking for behind a control they have to remember to open, and mail labels are
 * two deep at most in practice.
 */
@Composable
fun LabelSidebar(
    labels: List<Label>,
    selected: Label?,
    onSelect: (Label) -> Unit,
    onCreate: () -> Unit,
    onDiagnostics: () -> Unit,
    onAppearance: () -> Unit,
    onAccounts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = PlMailTheme.values

    // Before the first sync there is no list to draw, and an empty drawer with a
    // "New label" button under it reads as an account with no mailboxes rather
    // than as one that has not been read yet.
    if (labels.isEmpty()) {
        EmptySidebar(modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = theme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.hair),
    ) {
        item {
            Text(
                text = stringResource(R.string.drawer_title),
                style = MaterialTheme.typography.titleLarge,
                color = theme.colors.ink,
                modifier =
                    Modifier.padding(
                        horizontal = theme.spacing.large,
                        vertical = theme.spacing.large,
                    ),
            )
        }

        items(items = labels, key = { it.key }) { label ->
            // The rule between the system labels and the user's own. The
            // sidebar's order is a product decision -- Inbox, Sent, Drafts,
            // Spam, Trash, Archive -- and without a break the user's first
            // custom label looks like one more of them.
            if (labels.isFirstCustom(label)) {
                PlMailDivider(
                    modifier = Modifier.padding(vertical = theme.spacing.small),
                    startIndent = theme.spacing.large,
                )
            }

            NavigationDrawerItem(
                selected = label.key == selected?.key,
                onClick = { onSelect(label) },
                icon = { Icon(imageVector = label.icon(), contentDescription = null) },
                label = {
                    Text(
                        text = label.path,
                        maxLines = 1,
                        // Both ends kept. A nested label needs its leaf to say
                        // what it is and its parent to say which one -- an
                        // ordinary trailing ellipsis drops the leaf, which is
                        // the half being looked for.
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                },
                badge = {
                    if (label.unreadThreads > 0) {
                        Text(
                            text = label.unreadThreads.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.colors.accent,
                        )
                    }
                },
                colors =
                    NavigationDrawerItemDefaults.colors(
                        selectedIconColor = theme.colors.accent,
                        selectedTextColor = theme.colors.accent,
                        selectedContainerColor = theme.colors.accentSoft,
                        unselectedIconColor = theme.colors.inkMuted,
                        unselectedTextColor = theme.colors.inkSoft,
                        unselectedContainerColor = theme.colors.surface,
                    ),
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = theme.spacing.small),
                horizontalArrangement = Arrangement.Start,
            ) {
                TextButton(onClick = onCreate) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.label_new),
                        modifier = Modifier.padding(start = theme.spacing.small),
                    )
                }
            }
        }

        item {
            // Below the labels and below the rule, because neither of these is
            // one. Appearance sits above diagnostics because it is the one
            // people go looking for; diagnostics is the one they need at the
            // moment something is wrong, which is not a moment they browse for.
            PlMailDivider(
                modifier = Modifier.padding(vertical = theme.spacing.small),
                startIndent = theme.spacing.large,
            )

            NavigationDrawerItem(
                selected = false,
                onClick = onAccounts,
                icon = {
                    Icon(imageVector = Icons.Outlined.AccountCircle, contentDescription = null)
                },
                label = { Text(stringResource(R.string.accounts)) },
                colors =
                    NavigationDrawerItemDefaults.colors(
                        unselectedIconColor = theme.colors.inkMuted,
                        unselectedTextColor = theme.colors.inkSoft,
                        unselectedContainerColor = theme.colors.surface,
                    ),
            )

            NavigationDrawerItem(
                selected = false,
                onClick = onAppearance,
                icon = { Icon(imageVector = Icons.Outlined.Palette, contentDescription = null) },
                label = { Text(stringResource(R.string.appearance)) },
                colors =
                    NavigationDrawerItemDefaults.colors(
                        unselectedIconColor = theme.colors.inkMuted,
                        unselectedTextColor = theme.colors.inkSoft,
                        unselectedContainerColor = theme.colors.surface,
                    ),
            )

            NavigationDrawerItem(
                selected = false,
                onClick = onDiagnostics,
                icon = {
                    Icon(imageVector = Icons.Outlined.MonitorHeart, contentDescription = null)
                },
                label = { Text(stringResource(R.string.diagnostics)) },
                colors =
                    NavigationDrawerItemDefaults.colors(
                        unselectedIconColor = theme.colors.inkMuted,
                        unselectedTextColor = theme.colors.inkSoft,
                        unselectedContainerColor = theme.colors.surface,
                    ),
            )
        }
    }
}

/** Where the user's own labels start, so exactly one rule is drawn above the first of them. */
private fun List<Label>.isFirstCustom(label: Label): Boolean =
    !label.isSystem && firstOrNull { !it.isSystem }?.key == label.key && any { it.isSystem }

/**
 * The glyph for a label.
 *
 * By role, never by name — a label called "Trash" that the user made is one of their labels, and
 * giving it the bin icon says the app will empty it.
 */
private fun Label.icon(): ImageVector =
    when (role) {
        "inbox" -> Icons.Outlined.Inbox
        "sent" -> Icons.AutoMirrored.Outlined.Send
        "drafts" -> Icons.Outlined.Drafts
        "junk" -> Icons.Outlined.Report
        "trash" -> Icons.Outlined.Delete
        "archive" -> Icons.Outlined.Archive
        "snoozed" -> Icons.Outlined.Bedtime
        "flagged" -> Icons.Outlined.Star
        else -> Icons.AutoMirrored.Outlined.Label
    }

/** A column of nothing, for the moment before the first sync has any labels to show. */
@Composable
internal fun EmptySidebar(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(PlMailTheme.spacing.large)) {
        Text(
            text = stringResource(R.string.labels_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = PlMailTheme.colors.inkMuted,
        )
    }
}
