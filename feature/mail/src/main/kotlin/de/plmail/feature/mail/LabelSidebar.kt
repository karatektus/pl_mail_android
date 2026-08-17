package de.plmail.feature.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.core.data.Label
import de.plmail.core.data.MailCategory
import de.plmail.core.data.MailView
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailLabelColor
import de.plmail.core.designsystem.PlMailSurface
import de.plmail.core.designsystem.PlMailSurfaceKind
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
 * **The inbox categories live here, in the Inbox row's place**, and that placement is Gmail's
 * rather than an invention. Gmail on Android does not put its tabs over the list — that is the web
 * — it puts them in the drawer at the top, above the other system labels, and the product owner's
 * brief was to follow it rather than design something new. It also happens to be the only place
 * they can go here: this app's navigation *is* a list of destinations, and a tab strip over the
 * list would be a second navigation control disagreeing with the first about where the user is.
 *
 * **There is no whole-inbox row where the categories are drawn**, which is the part that changed.
 * It used to sit above them, indented them under itself, and be where the app opened — so every
 * launch landed on the one list the tabs exist to break up. Gmail's Inbox *becomes* Primary when
 * tabs are on and so does this one: see [MailView], and
 * [de.plmail.core.data.FeedRepository.category] for how Primary stays honest on a plMail that
 * classifies nothing. On such a server there are no category rows and the Inbox label keeps its
 * own, which is the same destination under the name that is true there.
 */
@Composable
fun LabelSidebar(
    labels: List<Label>,
    /**
     * Whether to draw the category rows at all.
     *
     * False on a plMail that predates the extension, where every conversation's category is null
     * and all five destinations would be permanently empty. Five dead rows in a drawer is worse
     * than no rows: it says the server has a feature it does not have, and the only way to find out
     * is to open each one. The Inbox row is drawn instead, and means the same list.
     */
    showCategories: Boolean,
    /**
     * Which categories hold mail, as the local cache sees it.
     *
     * The web's own rule, and worth copying exactly so the phone and the browser show the same tabs
     * rather than each being defensibly different: Primary is always drawn, a category is drawn
     * while it holds a conversation, and the one being *read* survives its own emptying until the
     * user navigates away — otherwise archiving the last promotion pulls the list out from under
     * the person reading it.
     */
    populatedCategories: Set<MailCategory>,
    /**
     * Which categories have mail that has arrived and never been looked at.
     *
     * Drawn as a dot rather than a number, for the reason the rows carry no count: the device can
     * honestly say *that* something arrived without being able to say how much the server thinks
     * there is. The same signal [de.plmail.core.data.CategoryDigest] puts at the top of Primary.
     */
    newCategories: Set<MailCategory>,
    selected: MailView,
    onSelect: (MailView) -> Unit,
    onCreate: () -> Unit,
    /**
     * Null where this install has no calendar, and then the row is not drawn at all.
     *
     * A callback rather than a boolean, because that is what makes the absence unforgeable: an
     * instance that publishes no calendars capability has nothing for the row to open, and a
     * disabled entry would say the feature is somewhere else rather than absent. `:feature:mail`
     * deliberately learns nothing about calendars beyond whether there is one — the screen behind
     * this is its own module, and a dependency from one feature onto another is what the module
     * boundary exists to prevent.
     */
    onCalendar: (() -> Unit)?,
    onPush: () -> Unit,
    onNotifications: () -> Unit,
    onDiagnostics: () -> Unit,
    onAppearance: () -> Unit,
    onAccounts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The user's sidebar density, where they have chosen one that differs from the
    // overall setting. Wrapping the whole drawer rather than its rows: the group
    // headings, the divider inset and the "New label" button all read the same
    // spacing scale, and a density that moved only the rows would leave them
    // sitting at gaps chosen for a different one.
    PlMailSurface(PlMailSurfaceKind.SIDEBAR) {
        SidebarContent(
            labels = labels,
            showCategories = showCategories,
            populatedCategories = populatedCategories,
            newCategories = newCategories,
            selected = selected,
            onSelect = onSelect,
            onCreate = onCreate,
            onCalendar = onCalendar,
            onPush = onPush,
            onNotifications = onNotifications,
            onDiagnostics = onDiagnostics,
            onAppearance = onAppearance,
            onAccounts = onAccounts,
            modifier = modifier,
        )
    }
}

@Composable
private fun SidebarContent(
    labels: List<Label>,
    showCategories: Boolean,
    populatedCategories: Set<MailCategory>,
    newCategories: Set<MailCategory>,
    selected: MailView,
    onSelect: (MailView) -> Unit,
    onCreate: () -> Unit,
    onCalendar: (() -> Unit)?,
    onPush: () -> Unit,
    onNotifications: () -> Unit,
    onDiagnostics: () -> Unit,
    onAppearance: () -> Unit,
    onAccounts: () -> Unit,
    modifier: Modifier,
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

            // In its place, not above it. The categories *are* the inbox where
            // the server classifies, so a whole-inbox row beside them would be a
            // sixth destination holding the union of the other five -- which is
            // the list this navigation was reorganised to stop opening on.
            if (showCategories && label.role == INBOX_ROLE) {
                MailCategory.entries
                    .filter { it in populatedCategories || selected == MailView.Category(it) }
                    .forEach { category ->
                        SidebarItem(
                            selected = selected == MailView.Category(category),
                            onClick = { onSelect(MailView.Category(category)) },
                            icon = category.icon(),
                            text = category.displayName(),
                            // No count: the only number this device could show is
                            // how many unread of that category it has *paged*, and
                            // a badge that disagreed with the web's would be worse
                            // than no badge -- the server publishes no per-category
                            // total over JMAP. What it shows instead is a dot for
                            // mail that has arrived and not been looked at, which
                            // is a claim the device can actually keep.
                            hasNew = category in newCategories,
                        )
                    }

                return@items
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

            // First under the rule, because it is a *place* like the labels
            // above it and the three below are settings. It is the only entry
            // here that draws mail's peer rather than something about mail.
            onCalendar?.let { open ->
                NavigationDrawerItem(
                    selected = false,
                    onClick = open,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.CalendarMonth,
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(R.string.calendar)) },
                    colors =
                        NavigationDrawerItemDefaults.colors(
                            unselectedIconColor = theme.colors.inkMuted,
                            unselectedTextColor = theme.colors.inkSoft,
                            unselectedContainerColor = theme.colors.surface,
                        ),
                )
            }

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

            // Directly above push, and that pairing is the point. The two
            // answer the opposite halves of "my phone is too quiet": this one
            // is what the user *chose* to hear about, push is whether anything
            // can reach the device at all. Somebody who has just found that
            // every label is switched off should not have to go looking for the
            // other explanation, or the reverse.
            NavigationDrawerItem(
                selected = false,
                onClick = onNotifications,
                icon = {
                    Icon(imageVector = Icons.Outlined.Notifications, contentDescription = null)
                },
                label = { Text(stringResource(R.string.notifications)) },
                colors =
                    NavigationDrawerItemDefaults.colors(
                        unselectedIconColor = theme.colors.inkMuted,
                        unselectedTextColor = theme.colors.inkSoft,
                        unselectedContainerColor = theme.colors.surface,
                    ),
            )

            // Above diagnostics and below appearance: it is a setting people go
            // looking for -- "why is my phone not ringing" is answered by
            // choosing a transport, not by reading a report -- while
            // diagnostics is the screen they need once that has not worked.
            NavigationDrawerItem(
                selected = false,
                onClick = onPush,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsActive,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(R.string.push)) },
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
    /**
     * Whether to mark this row as holding mail that has arrived and not been seen.
     *
     * A dot rather than a count, and it takes the badge slot rather than sitting beside it: two
     * marks in one row would have the reader working out which of them they are being told about. A
     * row never has both — the categories carry no count and the labels carry no dot.
     */
    hasNew: Boolean = false,
) {
    val theme = PlMailTheme.values
    val colored = tint?.let { theme.colors.labelColor(it) }

    // Resolved out here: the badge slot is not a composable scope a string
    // resource can be reached from once it is inside a `semantics` block.
    val newDescription = stringResource(R.string.category_has_new_a11y)

    NavigationDrawerItem(
        selected = selected,
        onClick = onClick,
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
            when {
                badge > 0 ->
                    Text(
                        text = badge.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.colors.accent,
                    )

                hasNew ->
                    Box(
                        modifier =
                            Modifier.size(NEW_DOT)
                                .background(color = theme.colors.accent, shape = CircleShape)
                                .semantics {
                                    contentDescription = newDescription
                                }
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
 * Primary lights the Inbox row, because on a server with no classifier that row is how Primary is
 * reached and what it is called — the feed layer collapses the two for the same reason. Where the
 * categories *are* drawn there is no Inbox row for this to light, so the case costs nothing there.
 */
private fun MailView.selects(label: Label): Boolean =
    when (this) {
        is MailView.Labelled -> this.label.key == label.key
        is MailView.Category -> category == MailCategory.PRIMARY && label.role == INBOX_ROLE
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
internal fun MailCategory.icon(): ImageVector =
    when (this) {
        MailCategory.PRIMARY -> Icons.Outlined.Person
        MailCategory.SOCIAL -> Icons.Outlined.People
        MailCategory.PROMOTIONS -> Icons.Outlined.LocalOffer
        MailCategory.UPDATES -> Icons.Outlined.Campaign
        MailCategory.FORUMS -> Icons.Outlined.Forum
    }

/**
 * The new-mail dot, in the slot a count would occupy.
 *
 * Small enough to read as punctuation rather than as a control: it is not tappable on its own, the
 * whole row is.
 */
private val NEW_DOT = 8.dp

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
