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
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.ui.unit.dp
import de.plmail.core.data.Label
import de.plmail.core.data.MailCategory
import de.plmail.core.data.MailView
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailLabelColor
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
 *
 * **The inbox categories live here too, indented under Inbox**, and that placement is Gmail's
 * rather than an invention. Gmail on Android does not put its tabs over the list — that is the web
 * — it puts them in the drawer, immediately below the inbox and above the other system labels, and
 * the product owner's brief was to follow it rather than design something new. It also happens to
 * be the only place they can go here: this app's navigation *is* a list of destinations, and a tab
 * strip over the list would be a second navigation control disagreeing with the first about where
 * the user is.
 *
 * The one thing that is not Gmail's is that **Inbox stays the whole inbox**. Gmail's Inbox becomes
 * Primary when tabs are on; here it cannot, because plMail leaves a conversation it has never
 * classified in no category at all — so an Inbox that meant Primary would hide mail on a server
 * whose category backfill has not run. Inbox is "All inboxes" and Primary narrows it, which is the
 * same pair Gmail offers when several accounts are set up.
 */
@Composable
fun LabelSidebar(
    labels: List<Label>,
    /**
     * Whether to draw the category group at all.
     *
     * False on a plMail that predates the extension, where every conversation's category is null
     * and all five destinations would be permanently empty. Five dead rows in a drawer is worse
     * than no rows: it says the server has a feature it does not have, and the only way to find out
     * is to open each one.
     */
    showCategories: Boolean,
    selected: MailView,
    onSelect: (MailView) -> Unit,
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

            SidebarItem(
                selected = selected.selects(label),
                onClick = { onSelect(MailView.Labelled(label)) },
                icon = label.icon(),
                // The label's own colour on its glyph, which is the leading slot
                // and where Gmail puts its colour too. Not on the text: the
                // sidebar already uses ink weight and colour to say which row is
                // selected, and a coloured *label* would be competing with that
                // for the same signal. The glyph is a mark, and a mark is what a
                // colour is for.
                tint = PlMailLabelColor.fromWire(label.color),
                text = label.displayName(),
                badge = label.unreadThreads,
            )

            // Straight under Inbox, before Sent. The list is already ordered
            // with the inbox first, so this is "after the first row" rather than
            // a position anybody has to maintain.
            if (showCategories && label.role == INBOX_ROLE) {
                MailCategory.entries.forEach { category ->
                    SidebarItem(
                        selected = selected == MailView.Category(category),
                        onClick = { onSelect(MailView.Category(category)) },
                        icon = category.icon(),
                        text = category.displayName(),
                        // Indented past the icons above, so they read as
                        // subdivisions of the inbox rather than as five more
                        // top-level places. No count: the only number this device
                        // could show is how many unread of that category it has
                        // *paged*, and a badge that disagreed with the web's
                        // would be worse than no badge -- the server publishes no
                        // per-category total over JMAP.
                        indent = CATEGORY_INDENT,
                    )
                }
            }
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

/**
 * One navigable row, so a label and a category are drawn by the same code.
 *
 * They are different kinds of destination and they must not *look* different: a category row that
 * had its own spacing or its own selected treatment would read as a control rather than as a place
 * to go, which is the whole trouble with bolting tabs onto a drawer.
 */
@Composable
private fun SidebarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    text: String,
    tint: PlMailLabelColor? = null,
    badge: Int = 0,
    indent: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val theme = PlMailTheme.values
    val colored = tint?.let { theme.colors.labelColor(it) }

    NavigationDrawerItem(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(start = indent),
        icon = { Icon(imageVector = icon, contentDescription = null) },
        label = {
            Text(
                text = text,
                maxLines = 1,
                // Both ends kept. A nested label needs its leaf to say what it
                // is and its parent to say which one -- an ordinary trailing
                // ellipsis drops the leaf, which is the half being looked for.
                overflow = TextOverflow.MiddleEllipsis,
            )
        },
        badge = {
            if (badge > 0) {
                Text(
                    text = badge.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.colors.accent,
                )
            }
        },
        colors =
            NavigationDrawerItemDefaults.colors(
                // The label's colour wins over the accent even while selected,
                // and that is deliberate: selection is already said twice, by
                // the container tint and by the text, so the glyph is free to
                // keep saying which label this is. Losing the colour at the
                // moment you are inside the label would be the one place it
                // could not be checked against the row chips.
                selectedIconColor = colored ?: theme.colors.accent,
                selectedTextColor = theme.colors.accent,
                selectedContainerColor = theme.colors.accentSoft,
                unselectedIconColor = colored ?: theme.colors.inkMuted,
                unselectedTextColor = theme.colors.inkSoft,
                unselectedContainerColor = theme.colors.surface,
            ),
    )
}

/**
 * Whether this view is the one that label draws.
 *
 * The Inbox label and the unified inbox are the same mail seen two ways, so browsing the inbox has
 * to light the Inbox row — the feed layer collapses them for the same reason.
 */
private fun MailView.selects(label: Label): Boolean =
    when (this) {
        MailView.Inbox -> label.role == INBOX_ROLE
        is MailView.Labelled -> this.label.key == label.key
        is MailView.Category -> false
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
        INBOX_ROLE -> Icons.Outlined.Inbox
        "sent" -> Icons.AutoMirrored.Outlined.Send
        "drafts" -> Icons.Outlined.Drafts
        "junk" -> Icons.Outlined.Report
        "trash" -> Icons.Outlined.Delete
        "archive" -> Icons.Outlined.Archive
        "snoozed" -> Icons.Outlined.Bedtime
        "flagged" -> Icons.Outlined.Star
        else -> Icons.AutoMirrored.Outlined.Label
    }

/** The glyph for a category, matching what Gmail uses for the same five tabs. */
private fun MailCategory.icon(): ImageVector =
    when (this) {
        MailCategory.PRIMARY -> Icons.Outlined.Person
        MailCategory.SOCIAL -> Icons.Outlined.People
        MailCategory.PROMOTIONS -> Icons.Outlined.LocalOffer
        MailCategory.UPDATES -> Icons.Outlined.Campaign
        MailCategory.FORUMS -> Icons.Outlined.Forum
    }

/**
 * How far the categories sit in from the labels.
 *
 * Fixed rather than scaled by density: what it has to clear is the icon column, which is Material's
 * own and does not move with the spacing scale. A density-scaled indent would leave the five rows
 * almost aligned with Inbox in compact, which reads as a layout mistake rather than a hierarchy.
 */
private val CATEGORY_INDENT = 16.dp

private const val INBOX_ROLE = "inbox"

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
