package de.plmail.core.data

/**
 * plMail's Gmail-style inbox categories.
 *
 * The server classifies every inbox conversation into one of these and the web has drawn a tab bar
 * over them for a long time; `Thread.category` is how the value reaches a client, and
 * `EmailFilter.ThreadCategory` is how a list is narrowed to one.
 *
 * The order is the server's own `MessageCategory`, which is also the order the web's tab bar uses,
 * so the phone and the browser present the same list in the same sequence.
 *
 * **Wire strings rather than a mapping.** These are sent back as filter values and compared against
 * what came off `Thread/get`, so a rename here would silently produce a tab that queries for a
 * category no server has.
 */
enum class MailCategory(val wire: String) {
    PRIMARY("primary"),
    SOCIAL("social"),
    PROMOTIONS("promotions"),
    UPDATES("updates"),
    FORUMS("forums");

    /** The feed id this category's list is persisted under. Stable — it keys the feed table. */
    val feedId: String
        get() = "category.$wire"

    companion object {
        /**
         * Null for anything this build does not know.
         *
         * A server that grows a sixth category must not crash a sidebar that predates it; the
         * conversation simply appears in no tab here, which is the same thing that happens to an
         * unclassified one and is the state the whole feature degrades to.
         */
        fun fromWire(value: String?): MailCategory? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Which list the app is showing — the one thing the sidebar selects and the feed layer pages.
 *
 * Three cases rather than a nullable [Label], which is what this replaced. Categories are genuinely
 * a third kind of destination: they have no bindings, no name on the server, nothing to rename and
 * nothing to delete, and they are not labels dressed up as one. Modelling them as pseudo-labels was
 * the tempting shortcut and would have put a `Label` with an empty binding list through
 * `LabelRepository`, where every method assumes a binding to write to.
 *
 * [Inbox] stays a case of its own rather than becoming `Category(PRIMARY)`, and that is the
 * decision that keeps this safe. The server puts an *unclassified* conversation in no tab at all —
 * the web's own inbox query does the same — so a phone whose Inbox entry were Primary would hide
 * every conversation on a plMail that has never run the category backfill. Inbox is the whole
 * inbox, exactly as it was; Primary narrows it, exactly as Gmail's does under "All inboxes".
 */
sealed interface MailView {

    /** The feed id this view's rows are persisted under. */
    val feedId: String

    /** Every account's inbox, unfiltered. The product's default view. */
    data object Inbox : MailView {
        override val feedId: String
            get() = Feed.UNIFIED_INBOX.id
    }

    data class Category(val category: MailCategory) : MailView {
        override val feedId: String
            get() = category.feedId
    }

    data class Labelled(val label: Label) : MailView {
        override val feedId: String
            get() = label.feedId
    }

    /**
     * The label this view is browsing, or null.
     *
     * What a row's chips are filtered against — every conversation in the Work list carries Work,
     * so a column of identical chips down the side of it says nothing. A category list has no such
     * label, which is correct: the conversations in Promotions carry whatever the user put on them,
     * and none of those names is the name of the list.
     */
    val browsedLabel: Label?
        get() = (this as? Labelled)?.label

    /**
     * A primitive that survives a process death, for `rememberSaveable`.
     *
     * A [Label] cannot be saved: it carries its bindings and its counts, both of which change under
     * it on every sync, so a restored copy would address a binding list that no longer exists.
     * Restoring resolves the key against the current sidebar instead — see [restore].
     */
    fun toKey(): String =
        when (this) {
            Inbox -> INBOX_KEY
            is Category -> CATEGORY_PREFIX + category.wire
            is Labelled -> LABEL_PREFIX + label.key
        }

    companion object {
        private const val INBOX_KEY = "inbox"
        private const val CATEGORY_PREFIX = "category:"
        private const val LABEL_PREFIX = "label:"

        /**
         * Turns a saved key back into a view, against the labels the app now has.
         *
         * Falls back to [Inbox] for anything that no longer resolves — a label somebody deleted on
         * the web, a category this build has stopped knowing about. Restoring to the inbox is the
         * one answer that is always valid, and the alternative is a list paging a mailbox the
         * server has forgotten and reporting it as an unreachable account.
         */
        fun restore(key: String?, labels: List<Label>): MailView =
            when {
                key == null -> Inbox
                key.startsWith(CATEGORY_PREFIX) ->
                    MailCategory.fromWire(key.removePrefix(CATEGORY_PREFIX))?.let(::Category)
                        ?: Inbox
                key.startsWith(LABEL_PREFIX) ->
                    labels.firstOrNull { it.key == key.removePrefix(LABEL_PREFIX) }?.let(::Labelled)
                        ?: Inbox
                else -> Inbox
            }
    }
}
