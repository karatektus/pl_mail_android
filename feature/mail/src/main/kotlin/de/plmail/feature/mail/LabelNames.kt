package de.plmail.feature.mail

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.plmail.core.data.Label
import de.plmail.core.data.MailCategory
import de.plmail.core.data.MailView

/**
 * What a label is called on screen.
 *
 * The server sends `"Inbox"`, `"Drafts"`, `"Trash"` — in English, always, because those names come
 * out of `LabelRole` rather than out of a translation catalogue. plMail's *web* client does not
 * draw them: `templates/` renders `sidebar.nav.inbox`, and `messages.de.yaml` translates that to
 * "Posteingang". So an Android app that drew the wire name showed a German user an English sidebar
 * while the same account on the same server showed them a German one — which is the two surfaces
 * disagreeing about the product, not the client being faithful to the wire.
 *
 * **By `role`, never by name**, for the same reason the sidebar's icons are chosen that way: a
 * label the user made and called "Trash" is one of their labels, and renaming it to "Papierkorb"
 * for them would be the app editing their data on screen. A role is the server saying what a
 * mailbox *is*.
 *
 * An unknown role falls through to [Label.path], which is also what a user's own label gets. A role
 * this version has never heard of is a mailbox with a name the server chose, and drawing that name
 * is strictly better than inventing one.
 */
@Composable
internal fun Label.displayName(): String =
    roleName()
        // The path rather than the leaf name: a list shows several labels at
        // once, and "Invoices" on its own does not say which parent it belongs
        // to when two parents both have one.
        ?: path

/**
 * The same name, for a place that shows exactly one label.
 *
 * The leaf, not the path, and that distinction predates this file: a screen title has no siblings
 * to be confused with, so "Work/Invoices" above a list is noise rather than disambiguation.
 *
 * Never the raw name for a system role, though, which is what this exists to fix — the app bar over
 * the inbox said "Inbox" in a German build for exactly as long as the sidebar did.
 */
@Composable internal fun Label.displayTitle(): String = roleName() ?: name

/**
 * What an inbox category is called on screen.
 *
 * Translated here rather than taken from the wire, for exactly the reason the system roles are: the
 * server's `MessageCategory` values are wire tokens, not display names, and the web renders them
 * through its own catalogue. A German device saying "promotions" beside "Posteingang" would be the
 * two surfaces disagreeing again.
 *
 * The German words are Gmail's own — Allgemein, Soziale Netzwerke, Werbung, Benachrichtigungen,
 * Foren — because these tabs *are* Gmail's, and somebody arriving from Gmail should not have to
 * work out that "Aktionen" and "Werbung" are the same tab.
 */
@Composable
internal fun MailCategory.displayName(): String =
    when (this) {
        MailCategory.PRIMARY -> stringResource(R.string.category_primary)
        MailCategory.SOCIAL -> stringResource(R.string.category_social)
        MailCategory.PROMOTIONS -> stringResource(R.string.category_promotions)
        MailCategory.UPDATES -> stringResource(R.string.category_updates)
        MailCategory.FORUMS -> stringResource(R.string.category_forums)
    }

/**
 * The title over whichever list is showing.
 *
 * Primary is titled **Inbox** where the server classifies nothing, because that is what it holds
 * there and what the sidebar row that reaches it is called — see
 * [de.plmail.core.data.FeedRepository.category]. Naming it "Primary" would put a word over the list
 * that names a distinction the server is not making, and send the reader looking for the other
 * four.
 */
@Composable
internal fun MailView.displayTitle(hasCategories: Boolean): String =
    when (this) {
        is MailView.Category ->
            if (category == MailCategory.PRIMARY && !hasCategories)
                stringResource(R.string.inbox_title)
            else category.displayName()

        is MailView.Labelled -> label.displayTitle()
    }

/** The app's own word for a system role, or null for a label the user made. */
@Composable
private fun Label.roleName(): String? =
    when (role) {
        "inbox" -> stringResource(R.string.role_inbox)
        "drafts" -> stringResource(R.string.role_drafts)
        "sent" -> stringResource(R.string.role_sent)
        "trash" -> stringResource(R.string.role_trash)
        "junk" -> stringResource(R.string.role_junk)
        "archive" -> stringResource(R.string.role_archive)
        "snoozed" -> stringResource(R.string.role_snoozed)
        "flagged" -> stringResource(R.string.role_flagged)
        // A role this version has never heard of is a mailbox whose name the
        // server chose, and drawing that name is strictly better than inventing
        // one — which is also what a label the user made gets.
        else -> null
    }
