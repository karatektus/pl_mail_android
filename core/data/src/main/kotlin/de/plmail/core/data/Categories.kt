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
 * Two cases rather than a nullable [Label], which is what this replaced. Categories are genuinely a
 * second kind of destination: they have no bindings, no name on the server, nothing to rename and
 * nothing to delete, and they are not labels dressed up as one. Modelling them as pseudo-labels was
 * the tempting shortcut and would have put a `Label` with an empty binding list through
 * `LabelRepository`, where every method assumes a binding to write to.
 *
 * ## There is no "whole inbox" destination, and that is the point
 *
 * There used to be one, and it was where the app opened: every category at once, in one
 * undifferentiated list. That is precisely what the tabs exist to stop — a Gmail user opens on
 * Primary and hears about the rest when there is something to hear about, which is what
 * [de.plmail.core.data.CategoryDigest] is for. So [Category] of [MailCategory.PRIMARY] is [START]
 * and the whole-inbox view is gone from the navigation.
 *
 * **On a server with no classifier, Primary *is* the whole inbox**, and that is what makes one
 * start destination safe. The obvious objection to folding the two together is real — the server
 * leaves an unclassified conversation out of every category, so a Primary that queried
 * `threadCategory = primary` against a plMail that has never classified anything would show an
 * empty list where the mail is. The answer is not a second destination but a narrower query: see
 * [FeedRepository.category] and [FeedProjection], which both drop the category condition where the
 * device has never seen a classified conversation. The user gets a list called Inbox holding their
 * inbox; the app has one place it opens either way, decided without waiting on a database read.
 *
 * [Feed.UNIFIED_INBOX] outlives the destination, because it is still where every inbox conversation
 * is filed — which is what an archive has to clear a row out of.
 */
sealed interface MailView {

    /** The feed id this view's rows are persisted under. */
    val feedId: String

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
            is Category -> CATEGORY_PREFIX + category.wire
            is Labelled -> LABEL_PREFIX + label.key
        }

    companion object {
        private const val CATEGORY_PREFIX = "category:"
        private const val LABEL_PREFIX = "label:"

        /**
         * The key the retired whole-inbox destination was saved under.
         *
         * Still read, never written. A saved destination outlives the version that saved it — this
         * is what `rememberSaveable` hands back after a process death, and what an install
         * upgrading from a build that opened on the inbox will present exactly once. Resolving it
         * to [START] is the migration, and it costs one line rather than a store to rewrite.
         */
        private const val RETIRED_INBOX_KEY = "inbox"

        /**
         * Where the app opens: Primary.
         *
         * A constant rather than a function of whether the server classifies mail, and that is
         * deliberate. The classifier is a fact the device learns from its own cache, so a start
         * view that waited on it would draw the wrong list for the frame before the answer arrived
         * — and the wrong list here is the whole inbox, which is the one this change exists to stop
         * anybody seeing. Primary answers correctly on both kinds of server; the query behind it is
         * what adapts.
         */
        val START: MailView = Category(MailCategory.PRIMARY)

        /**
         * Turns a saved key back into a view, against the labels the app now has.
         *
         * Falls back to [START] for anything that no longer resolves — a label somebody deleted on
         * the web, a category this build has stopped knowing about. Restoring to Primary is the one
         * answer that is always valid, and the alternative is a list paging a mailbox the server
         * has forgotten and reporting it as an unreachable account.
         */
        fun restore(key: String?, labels: List<Label>): MailView =
            when {
                key == null || key == RETIRED_INBOX_KEY -> START
                key.startsWith(CATEGORY_PREFIX) ->
                    MailCategory.fromWire(key.removePrefix(CATEGORY_PREFIX))?.let(::Category)
                        ?: START
                key.startsWith(LABEL_PREFIX) ->
                    labels.firstOrNull { it.key == key.removePrefix(LABEL_PREFIX) }?.let(::Labelled)
                        ?: START
                else -> START
            }
    }
}

/**
 * Whether this view is where the app opens, under either of the two names it has.
 *
 * [MailView.START] is Primary, and on a plMail that classifies nothing the sidebar draws an Inbox
 * *label* row that reaches the same list — see [MailView] and `FeedRepository.category`. So "am I
 * at the start destination" has two spellings, and code that checked only the first got them wrong
 * in ways that are individually small and all in the same direction: the feed layer would have
 * restarted a pager for a list it was already showing, and back would have been a dead press on the
 * one screen where back has to leave.
 *
 * One function rather than the same `role == "inbox"` test written out wherever it is needed,
 * because the two places that need it are a pager and a back handler and nothing would have
 * connected them.
 */
val MailView.isStartDestination: Boolean
    get() = this == MailView.START || (this is MailView.Labelled && label.role == START_ROLE)

private const val START_ROLE = "inbox"
