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
 * Cases rather than a nullable [Label], which is what this replaced. Categories are genuinely a
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

    /**
     * [inbox] is the unified inbox this tab is shown for (pl_mail#265), or null for every account's
     * — the only kind there is on a server without the extension, or with one inbox. See
     * [scopedFeedId] for why it is a list of its own.
     */
    data class Category(val category: MailCategory, override val inbox: String? = null) : MailView {
        override val feedId: String
            get() = inbox?.let { scopedFeedId(it, category.feedId) } ?: category.feedId
    }

    data class Labelled(val label: Label) : MailView {
        override val feedId: String
            get() = label.feedId
    }

    /**
     * One of the inbox tabs the reader made on the web — "Shipping", "Kita".
     *
     * A third case rather than a [Category] with a looser type, because the five built-ins are
     * named, iconed and notified about by the app and these are not: they arrive with the name the
     * reader gave them and nothing else, and a `MailCategory` that could hold anything would put
     * that difference behind a null check in every place that draws one. Only ever reached on a
     * server with `urn:plmail:params:jmap:inboxtabs`; see [MailTab].
     *
     * [name] rides along for the title over the list. Restored from the cached tabs rather than
     * saved, so a tab renamed on the web is titled by its new name — see [restore].
     */
    data class Tab(val key: String, val name: String, override val inbox: String? = null) :
        MailView {
        override val feedId: String
            get() = inbox?.let { scopedFeedId(it, tabFeedId(key)) } ?: tabFeedId(key)
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

    /** The unified inbox this list belongs to, or null for a list over every account. */
    val inbox: String?
        get() = null

    /**
     * A primitive that survives a process death, for `rememberSaveable`.
     *
     * A [Label] cannot be saved: it carries its bindings and its counts, both of which change under
     * it on every sync, so a restored copy would address a binding list that no longer exists.
     * Restoring resolves the key against the current sidebar instead — see [restore].
     */
    fun toKey(): String =
        when (this) {
            is Category -> CATEGORY_PREFIX + category.wire + inbox.asSuffix()
            is Labelled -> LABEL_PREFIX + label.key
            is Tab -> TAB_PREFIX + key + inbox.asSuffix()
        }

    companion object {
        private const val CATEGORY_PREFIX = "category:"
        private const val LABEL_PREFIX = "label:"
        private const val TAB_PREFIX = "tab:"

        /** Between a tab and the unified inbox it is shown for: `category:social@12`. */
        private const val INBOX_SEPARATOR = '@'

        private fun String?.asSuffix(): String = this?.let { "$INBOX_SEPARATOR$it" }.orEmpty()

        /**
         * Where the app opens, given the unified inboxes the navigation draws: the first one's
         * Primary, or [START] where it draws none.
         */
        fun home(inboxes: List<String>): MailView =
            inboxes.firstOrNull()?.let { Category(MailCategory.PRIMARY, it) } ?: START

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
         * Turns a saved key back into a view, against the labels and tabs the app now has.
         *
         * Falls back to [START] for anything that no longer resolves — a label somebody deleted on
         * the web, a category this build has stopped knowing about, a tab of the reader's own they
         * deleted or one that has not been read off the cache yet.
         *
         * [tabbed] false is the user's "one list": no tab is a destination then, so a saved tab or
         * category other than Primary restores to Primary, which holds the whole inbox. Restoring
         * to Primary is the one answer that is always valid, and the alternative is a list paging a
         * mailbox the server has forgotten and reporting it as an unreachable account.
         */
        fun restore(
            key: String?,
            labels: List<Label>,
            tabs: List<MailTab> = emptyList(),
            tabbed: Boolean = true,
            inboxes: List<String> = emptyList(),
        ): MailView {
            val home = home(inboxes)

            if (key == null || !key.isInboxList()) {
                return restoreOne(key, labels, tabs, tabbed).takeIf { it != START } ?: home
            }

            // An inbox list. Shown for one unified inbox where the navigation
            // draws several, and for every account where it draws none: a key
            // saved under the other arrangement is turned into this one's,
            // and one naming an inbox that is gone opens the first.
            val saved = key.substringAfter(INBOX_SEPARATOR, "").takeIf { it.isNotEmpty() }
            val inbox =
                when {
                    inboxes.isEmpty() -> null
                    saved in inboxes -> saved
                    else -> return home
                }

            return when (
                val view = restoreOne(key.substringBefore(INBOX_SEPARATOR), labels, tabs, tabbed)
            ) {
                is Category -> view.copy(inbox = inbox)
                is Tab -> view.copy(inbox = inbox)
                is Labelled -> view
            }
        }

        private fun String.isInboxList(): Boolean =
            startsWith(CATEGORY_PREFIX) || startsWith(TAB_PREFIX)

        private fun restoreOne(
            key: String?,
            labels: List<Label>,
            tabs: List<MailTab>,
            tabbed: Boolean,
        ): MailView =
            when {
                key == null || key == RETIRED_INBOX_KEY -> START
                !tabbed && (key.startsWith(CATEGORY_PREFIX) || key.startsWith(TAB_PREFIX)) -> START
                key.startsWith(CATEGORY_PREFIX) ->
                    MailCategory.fromWire(key.removePrefix(CATEGORY_PREFIX))
                        // A built-in the reader has since switched off on the web is
                        // no tab any more: it lists nothing there, and here it would
                        // be a selected row over an empty list nothing can explain.
                        ?.takeIf { category ->
                            tabs.isEmpty() || tabs.any { it.builtIn == category }
                        }
                        ?.let(::Category) ?: START
                key.startsWith(LABEL_PREFIX) ->
                    labels.firstOrNull { it.key == key.removePrefix(LABEL_PREFIX) }?.let(::Labelled)
                        ?: START
                key.startsWith(TAB_PREFIX) ->
                    tabs
                        .firstOrNull {
                            it.builtIn == null && it.key == key.removePrefix(TAB_PREFIX)
                        }
                        ?.view ?: START
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

/**
 * Whether this view is where the app opens, given that [home] is — see [MailView.home].
 *
 * Where the navigation draws several unified inboxes, the start is the first one's Primary and
 * nothing else; where it draws none, it is [isStartDestination] with both its spellings.
 */
fun MailView.isHome(home: MailView): Boolean =
    this == home || (home == MailView.START && isStartDestination)

/** The same list, shown for one unified inbox — or for every account where [inbox] is null. */
fun MailView.within(inbox: String?): MailView =
    when (this) {
        is MailView.Category -> copy(inbox = inbox)
        is MailView.Tab -> copy(inbox = inbox)
        is MailView.Labelled -> this
    }

private const val START_ROLE = "inbox"
