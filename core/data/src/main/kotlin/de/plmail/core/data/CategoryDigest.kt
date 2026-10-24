package de.plmail.core.data

import de.plmail.core.database.InboxTabEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.ThreadEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * "Promotions — 3 new", as one row.
 *
 * What the top of Primary shows when mail has landed in a tab the user is not looking at, and the
 * whole reason the whole-inbox view could be retired. Gmail's own answer to the same problem: the
 * tabs stop mail you did not ask about from burying mail you did, and the bundle stops that from
 * becoming mail you never find out about.
 */
data class CategoryArrivals(
    /**
     * Where the mail landed: a built-in category, or one of the reader's own tabs on a server that
     * serves them. One type for both, so the row above Primary does not have to know which.
     */
    val tab: MailTab,
    val count: Int,
    /**
     * Who wrote, newest first, already de-duplicated.
     *
     * Names rather than a count of senders, because "Rail Europe, Duolingo and 2 more" is a row
     * somebody can decide about without opening it, and "4 new" is not. Capped at [MAX_SENDERS] —
     * past three the row stops being readable at a glance, which is the only thing it is for.
     */
    val senders: List<String>,
    /** How many senders did not fit in [senders]. Zero when they all did. */
    val moreSenders: Int,
)

/**
 * Which categories have mail worth mentioning, and which exist at all.
 *
 * ## One fact, both surfaces
 *
 * Newness is **the server's**: `Thread.isNew`, meaning never put in front of this user and arrived
 * inside plMail's own `NEW_WINDOW`. The phone reads the same marker the browser draws its badges
 * from, and reports its own displays back through `Thread/set`, so opening a tab here clears the
 * dot there and the reverse.
 *
 * That is worth stating plainly because it replaced something weaker. While the server published no
 * newness at all, this computed a stand-in — unread mail arriving since a locally recorded "last
 * opened this category" instant, inside a 24-hour window copied from the server's constant. It
 * behaved correctly in isolation and could only agree with the browser by coincidence: a mailbox
 * triaged on the phone opened on the laptop with every conversation still badged, because nothing
 * the phone did could retire a marker. Both halves of that are gone — the local timestamps and the
 * copied window — and neither should come back. If newness ever needs a rule the server does not
 * apply, the rule belongs on the server.
 *
 * **Unread is no longer part of the definition**, and that is a deliberate consequence rather than
 * an oversight. The old proxy needed it: with no record of what had been displayed, unread was the
 * only available evidence that mail had not been dealt with. The server keeps newness and read
 * state as genuinely independent axes — mail read on a laptop is still new to a client that has
 * never drawn its row — and now that the phone can read and retire the real marker, it agrees.
 */
@Singleton
class CategoryDigest @Inject constructor(private val database: PlMailDatabase) {

    /**
     * The tabs this device knows about, per account, as the rows that decide placement.
     *
     * Empty for an account on a server without the extension, which is what [placement] reads as
     * "the category is the tab".
     */
    private val tabRows: Flow<List<InboxTabEntity>> = database.inboxTabs().observeAll()

    /**
     * Which tabs hold a conversation, by key, as the sidebar decides what to draw.
     *
     * The web's rule, copied so the two surfaces agree: Primary is always in the set, and every
     * other tab is in it while it holds mail. Read state is irrelevant here — a tab with fifty read
     * promotions is still a tab.
     *
     * Which tab a conversation holds is [tabKeyOf]'s answer — the reader's own tab where they filed
     * it, Primary for a category they switched off — so a tab is populated on the phone exactly
     * when it is on the web.
     *
     * The cache is one half of the answer and the server the other (#52): a tab is also populated
     * when the server last said it holds inbox mail ([InboxTabEntity.hasMail]). The cache alone
     * only knows a tab once it was opened or new mail arrived in it, and a tab that is never listed
     * can never be opened.
     */
    val populated: Flow<Set<String>> =
        combine(database.threads().observeTabPlacements(), tabRows) { rows, tabs ->
                val placed = placement(tabs)

                rows.mapNotNullTo(mutableSetOf()) {
                    tabKeyOf(it.inboxTab, it.category, placed[it.accountKey])
                } +
                    tabs.filter { it.isEnabled && it.hasMail }.map { it.tabId } +
                    MailCategory.PRIMARY.wire
            }
            .distinctUntilChanged()

    /**
     * The bundles to show above Primary, in the strip's own order.
     *
     * Primary is **excluded by construction**: its mail is the list underneath, and a bundle saying
     * "3 new in Primary" over three unread rows saying the same thing is the app talking to itself.
     * Its own rows carry the marker instead — see `ShownThreads`, which is what retires them.
     */
    val arrivals: Flow<List<CategoryArrivals>> =
        combine(database.threads().observeNew(), tabRows, database.accounts().observeAll()) {
                new,
                tabs,
                accounts ->
                // One list has no "elsewhere" for mail to have landed in: every
                // inbox conversation is already on the list underneath.
                if (accounts.wantInboxTabs()) digest(new, tabs) else emptyList()
            }
            .distinctUntilChanged()

    /** Which tabs carry a dot, by key, which is [arrivals] asked as a yes or no. */
    val hasNew: Flow<Set<String>> =
        arrivals.map { found -> found.mapTo(mutableSetOf()) { it.tab.key } }.distinctUntilChanged()

    private fun digest(
        new: List<ThreadEntity>,
        tabs: List<InboxTabEntity>,
    ): List<CategoryArrivals> {
        val placed = placement(tabs)

        val byTab =
            new.groupBy { it.tabKey(placed[it.accountKey]) }
                // A token this build cannot name is dropped rather than guessed
                // at: it has no row to be tapped through to.
                .filterKeys { it != null && it != MailCategory.PRIMARY.wire }

        // The strip's order rather than the map's, which is the order the web's
        // tab strip and this app's sidebar both use. A digest whose rows moved
        // about between syncs would be a list nobody could learn the shape of.
        return strip(tabs).mapNotNull { tab ->
            val threads = byTab[tab.key]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null

            // Distinct before the cap, so "and 2 more" counts people rather than
            // conversations -- three mails from one shop is one sender.
            val senders =
                threads.map { it.participantsSummary }.filter { it.isNotBlank() }.distinct()

            CategoryArrivals(
                tab = tab,
                count = threads.size,
                senders = senders.take(MAX_SENDERS),
                moreSenders = (senders.size - MAX_SENDERS).coerceAtLeast(0),
            )
        }
    }

    /** Each account's switched-on tab keys. An account with no rows is absent: no extension. */
    private fun placement(tabs: List<InboxTabEntity>): Map<String, Set<String>> =
        tabs
            .groupBy { it.accountKey }
            .mapValues { (_, rows) ->
                rows.filter { it.isEnabled }.mapTo(mutableSetOf()) { it.tabId }
            }

    /** The tabs as the strip draws them, or the five where the server lists none. */
    private fun strip(tabs: List<InboxTabEntity>): List<MailTab> =
        if (tabs.isEmpty()) MailTab.BUILT_IN
        else
            tabs
                .distinctBy { it.tabId }
                .filter { it.isEnabled }
                .sortedBy { it.sortOrder }
                .map { it.toMailTab() }

    private companion object {
        /** Three names and a count. Past that the row is a paragraph. */
        const val MAX_SENDERS = 3
    }
}
