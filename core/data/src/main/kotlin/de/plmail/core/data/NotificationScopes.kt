package de.plmail.core.data

import de.plmail.core.datastore.NotificationPrefs
import de.plmail.jmap.mail.Email

/**
 * One thing notifications can be switched on or off for.
 *
 * Two kinds, because the app already has two kinds of mail list and they are genuinely different
 * objects — see [MailView], which draws the same distinction for the same reason. A category has no
 * bindings, no name on the server and nothing to rename; a label has all three. Modelling
 * categories as pseudo-labels here would mean a preference key that no `Label` can ever resolve.
 *
 * [key] is what is stored, and the format is deliberately the one [MailView.toKey] already uses, so
 * a preference and a saved destination read the same in a database browser. The two are not shared
 * code: this vocabulary must stay stable for as long as somebody's preferences file does, and the
 * saved-destination one may be changed by whoever next touches navigation.
 */
sealed interface NotifyScope {

    val key: String

    /**
     * Whether this interrupts before the user has said anything at all.
     *
     * The Gmail default, and the entire point of the feature: Primary speaks and nothing else does.
     */
    val isOnByDefault: Boolean

    data class Category(val category: MailCategory) : NotifyScope {
        override val key: String
            get() = CATEGORY_PREFIX + category.wire

        override val isOnByDefault: Boolean
            get() = category == MailCategory.PRIMARY
    }

    /**
     * A label, by its collapse key — the same user-scoped `labelId` the sidebar groups on, so one
     * switch covers the label in every account that binds it. Keying on a *binding* would give the
     * user three identical rows and let them disagree.
     */
    data class Labelled(val labelKey: String) : NotifyScope {
        override val key: String
            get() = LABEL_PREFIX + labelKey

        override val isOnByDefault: Boolean
            get() = false
    }

    companion object {
        const val CATEGORY_PREFIX = "category:"
        const val LABEL_PREFIX = "label:"

        /** The one scope that is on out of the box. */
        val PRIMARY: NotifyScope = Category(MailCategory.PRIMARY)
    }
}

/**
 * Whether one scope key may interrupt.
 *
 * Three states rather than two, and the third is the reason [NotificationPrefs] holds two sets:
 * "never said" is not the same as "said no". Only the first is allowed to fall through to the
 * default, which is why switching Primary off stays off across a restart instead of being helpfully
 * restored by a store that could not tell the difference.
 */
fun NotificationPrefs.allows(key: String): Boolean =
    when {
        key in disabled -> false
        key in enabled -> true
        else -> key == NotifyScope.PRIMARY.key
    }

/**
 * Every scope one arriving message falls under.
 *
 * A **set**, and then matched with `any` rather than fanned out — which is what makes a message
 * carrying three switched-on labels produce one notification instead of three. Dedup by
 * construction, at the only point where the alternative was ever tempting.
 *
 * ## What "Primary" means here
 *
 * A message gets a category scope only when it is **in the inbox**, and the category is the
 * conversation's — [de.plmail.jmap.mail.MailThread.category], which the sync already fetches beside
 * every message for snooze, so this costs no extra request. Per-conversation rather than
 * per-message is also the right answer rather than the cheap one: the notification is about the
 * newest message in a thread, and the server folds a thread's categories most-recent-wins, so a
 * newsletter somebody replied to has become a conversation and is announced as one.
 *
 * **An unclassified conversation counts as Primary, and that is a deliberate disagreement with the
 * tabs.** `MailThread.category` documents that null is not Primary, because a tab drawn that way
 * would show mail the web's own Primary tab does not have. Notifications invert that argument
 * exactly: a tab that is missing a conversation is visible — the user is looking at it — whereas a
 * notification that never arrives is indistinguishable from mail that never came, and this app's
 * users have to be able to work out why their phone is quiet. A plMail that predates the
 * classifier, or one whose backfill has not run, reports null for every conversation it has; on the
 * tab reading that means one empty tab, and on this reading it would mean **silence for every
 * message the user owns**. The same fall-through covers a category a newer server invents that this
 * build cannot name. So the rule is not "category equals primary" but the one Gmail's own tab
 * states:
 *
 * > in the inbox, and not classified into one of the non-primary categories this build knows.
 *
 * Label scopes are separate and do not require the inbox. That is what makes per-label
 * notifications worth having: a server-side rule that files mail under Work and skips the inbox is
 * exactly the case somebody switches Work on for. Nothing has to exclude Sent or Drafts to keep
 * that safe — those carry no category scope because they are not in the inbox, and their label
 * scopes are never offered to the user, so nothing can switch them on.
 */
internal fun notifyScopeKeys(
    email: Email,
    inboxMailboxId: String?,
    threadCategory: String?,
    bindingKeys: Map<String, String>,
): Set<String> {
    val scopes = mutableSetOf<String>()

    val boxes = email.mailboxes.map { it.value }

    if (inboxMailboxId != null && inboxMailboxId in boxes) {
        val category = MailCategory.fromWire(threadCategory) ?: MailCategory.PRIMARY

        scopes += NotifyScope.Category(category).key
    }

    boxes.forEach { box ->
        bindingKeys[box]?.let { scopes += NotifyScope.Labelled(it).key }
    }

    return scopes
}

/**
 * Roles that are never offered a notification switch.
 *
 * Not a filter on the announcing path — nothing there needs one, because a scope the settings
 * screen does not draw is a scope nobody can switch on. This is about not asking a question whose
 * only sensible answer is no: mail is in Sent because the user sent it, in Drafts because they are
 * writing it, and in Archive or Trash because they have already dealt with it. A switch for
 * "interrupt me when I send something" is a switch that makes the screen look untrustworthy.
 *
 * Junk is here for a different reason worth stating: spam that could ring the phone is spam that
 * has won, and a user who wants to watch their junk folder wants a list, not an interruption.
 */
internal val NEVER_NOTIFIABLE_ROLES =
    setOf("sent", "drafts", "trash", "junk", "archive", "all", "snoozed")
