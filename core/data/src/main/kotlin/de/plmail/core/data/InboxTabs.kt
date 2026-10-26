package de.plmail.core.data

import de.plmail.core.database.AccountEntity
import de.plmail.core.database.InboxTabEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.database.ThreadEntity
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.mail.EmailFilter
import de.plmail.jmap.mail.InboxTab
import de.plmail.jmap.methods.EmailQuery
import de.plmail.jmap.methods.EmailQueryResult
import de.plmail.jmap.methods.InboxTabChanges
import de.plmail.jmap.methods.InboxTabGet
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.JmapError
import de.plmail.jmap.protocol.MailboxId
import de.plmail.jmap.protocol.MethodHandle
import de.plmail.jmap.protocol.MethodResults
import de.plmail.jmap.protocol.RequestBuilder
import de.plmail.jmap.protocol.StateToken
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One inbox tab, as the app draws and files into it.
 *
 * Either one of the five built-in categories — [builtIn] set, named and drawn by the app in its own
 * language — or one the reader made on the web, which has only the [name] they gave it. The same
 * shape for both, so the sidebar, the digest above Primary and "Move to" have one list to walk
 * rather than a `MailCategory` loop with the reader's own tabs bolted on after it.
 *
 * On a server without `urn:plmail:params:jmap:inboxtabs` the list is [BUILT_IN]: the five, in the
 * server's own order, which is exactly what the app drew before the extension existed.
 */
data class MailTab(
    /** The tab key: a built-in's wire token, or the reader's own (`c12`). */
    val key: String,
    val builtIn: MailCategory? = null,
    /** The reader's name for a tab of their own. Null for a built-in, which the app names. */
    val name: String? = null,
    val sortOrder: Int = 0,
) {
    /**
     * The list this tab opens. A built-in stays a [MailView.Category], so nothing about it moves.
     */
    val view: MailView
        get() = builtIn?.let(MailView::Category) ?: MailView.Tab(key, name.orEmpty())

    /** The feed this tab's rows are persisted under. See [tabFeedId]. */
    val feedId: String
        get() = tabFeedId(key)

    companion object {
        /** The strip a server without the extension has: the five, in their own order. */
        val BUILT_IN: List<MailTab> =
            MailCategory.entries.map {
                MailTab(key = it.wire, builtIn = it, sortOrder = it.ordinal)
            }
    }
}

/**
 * The feed a tab's rows live in.
 *
 * A built-in keeps the id it has always had — `category.promotions` — so a list paged before the
 * extension is the same list after it. The reader's own are `tab.<key>`.
 */
fun tabFeedId(key: String): String = MailCategory.fromWire(key)?.feedId ?: "$TAB_FEED_PREFIX$key"

/** What the feed id of every tab the reader made starts with. */
internal const val TAB_FEED_PREFIX = "tab."

/**
 * Which tab a conversation is in, given the tabs its account has.
 *
 * The one place that rule is written on the device, and it is the server's
 * (`InboxTabMapper::tabOf`) restated against a cache that can be a moment behind it:
 * - [enabled] null is a server without the extension. The category is the tab, and a token this
 *   build cannot name is no tab at all — the behaviour from before the extension, unchanged.
 * - Otherwise `Thread.inboxTab` where it names a tab that is still on.
 * - A tab that is not — one of the reader's own they deleted, a built-in they switched off since
 *   the conversation was last read — falls back the way the server re-resolves it: to the category
 *   where that is on, and to Primary where it is not.
 * - Null when the conversation was never categorised, which is no tab, exactly as on the web.
 */
fun tabKeyOf(inboxTab: String?, category: String?, enabled: Set<String>?): String? {
    if (enabled == null) return MailCategory.fromWire(category)?.wire

    if (inboxTab == null && category == null) return null

    return inboxTab?.takeIf { it in enabled }
        ?: category?.takeIf { it in enabled }
        ?: MailCategory.PRIMARY.wire
}

/** [tabKeyOf] for a cached conversation. */
fun ThreadEntity.tabKey(enabled: Set<String>?): String? = tabKeyOf(inboxTab, category, enabled)

/**
 * The keys of the tabs this account has switched on, or null where its server has none.
 *
 * Read from the cache, so it costs one small query and no round trip; the projection and the local
 * half of every mail action both need it inside their own transactions.
 */
internal suspend fun PlMailDatabase.enabledTabKeys(accountKey: String): Set<String>? =
    inboxTabs().forAccount(accountKey).takeIf { it.isNotEmpty() }?.enabledKeys()

private fun List<InboxTabEntity>.enabledKeys(): Set<String> = filter {
    it.isEnabled
}
    .mapTo(mutableSetOf()) { it.tabId }

/**
 * Whether the user wants this account's inbox in tabs — the Session's `inboxTabs` hint.
 *
 * True for an account the cache does not hold, which is what every server older than the switch
 * means.
 */
internal suspend fun PlMailDatabase.wantsInboxTabs(accountKey: String): Boolean =
    accounts().byUid(accountKey)?.wantsInboxTabs ?: true

/**
 * Whether the user wants the inbox in tabs, from the account rows the Session wrote.
 *
 * Every row carries the same user-level answer; `all` rather than `first` only so that an account
 * written by an older Session cannot turn tabs back on. No accounts at all is "yes", the default.
 */
internal fun List<AccountEntity>.wantInboxTabs(): Boolean = all { it.wantsInboxTabs }

/**
 * The feed an inbox conversation is listed in, as the tab strip divides the inbox — or Primary
 * where it is not divided.
 *
 * The one rule the projection and the local half of every mail action place a row by, so a row a
 * sync puts in a list is the row an archive takes out of it:
 * - [wantsTabs] false is the user's "one list": Primary holds every inbox conversation, as the
 *   web's single list does, whatever each one's category says.
 * - Otherwise the tab [tabKeyOf] names, against the account's [enabled] tabs.
 * - And where it names none on a server that has never classified anything ([classifies] false),
 *   Primary again, because there Primary *is* the inbox — see [MailView].
 */
internal fun ThreadEntity.inboxFeedId(
    enabled: Set<String>?,
    classifies: Boolean,
    wantsTabs: Boolean,
): String? =
    when {
        !wantsTabs -> MailCategory.PRIMARY.feedId
        else ->
            tabKey(enabled)?.let(::tabFeedId) ?: MailCategory.PRIMARY.feedId.takeIf { !classifies }
    }

/**
 * Refreshes one account's tabs. A seam rather than the repository, for the reason
 * [ReachableAccounts] is one: the push path needs this one method and none of the rest.
 */
fun interface InboxTabRefresh {
    suspend fun refresh(accountKey: String)
}

/**
 * The reader's inbox tabs: read from the cache, refreshed from `InboxTab/changes`.
 *
 * Kept the way labels are — a full `InboxTab/get` beside `Mailbox/get` on every directory refresh,
 * see `FeedRepository.refreshBindings` — and additionally on a push naming a new `InboxTab` state,
 * which is the one way a tab made or switched off on the web reaches a phone that is not being
 * opened. The tabs are a handful of rows, so the cost that matters is round trips, and
 * `InboxTab/changes` answers "nothing moved" in one.
 *
 * **Managing tabs is the web's.** There is no create, rename, switch or reorder here, on purpose:
 * the settings page that does those is one place, and a second one on a phone would be a second set
 * of rules about which tabs may exist.
 */
@Singleton
class InboxTabRepository
@Inject
constructor(
    private val database: PlMailDatabase,
    private val clients: AccountClients,
    private val mail: MailRepository,
) : InboxTabRefresh {

    /**
     * Every tab, switched-off ones included, collapsed across accounts, in the reader's order.
     *
     * Empty on a server without the extension — not [MailTab.BUILT_IN], so a caller that needs to
     * know whether the reader's arrangement is available can tell. [strip] is the one to draw.
     */
    private val cached: Flow<List<InboxTabEntity>> =
        database.inboxTabs().observeAll().map { rows -> rows.distinctBy { it.tabId } }

    /**
     * Whether the user wants the inbox in tabs at all — the Session's `inboxTabs` hint.
     *
     * False is the web's "one list": nothing in the app divides the inbox then, and nothing offers
     * to move mail between tabs either.
     */
    val wantsTabs: Flow<Boolean> =
        database.accounts().observeAll().map { it.wantInboxTabs() }.distinctUntilChanged()

    /**
     * The tabs to draw, in order: the enabled ones the server listed, or the five built-ins where
     * it lists none.
     */
    val strip: Flow<List<MailTab>> =
        cached
            .map { rows -> if (rows.isEmpty()) MailTab.BUILT_IN else rows.toStrip() }
            .distinctUntilChanged()

    /**
     * The tabs the server listed and the reader has on, or empty where it lists none.
     *
     * What "Move to" offers. Not [strip]: on a server without the extension there is no `Thread/set
     * { inboxTab }` to send, so there is nothing to move a conversation into.
     */
    val movable: Flow<List<MailTab>> =
        combine(cached, wantsTabs) { rows, wanted -> if (wanted) rows.toStrip() else emptyList() }
            .distinctUntilChanged()

    /**
     * Brings one account's tabs up to date from its stored state.
     *
     * Nothing at all for an account whose server lacks the extension — no stored state and no
     * cached rows mean the directory refresh never found the capability, and asking a server for a
     * method it has never heard of is a request it refuses whole.
     */
    override suspend fun refresh(accountKey: String) {
        val account = database.accounts().byUid(accountKey) ?: return
        val client = clients.forAccount(accountKey) ?: return
        val accountId = AccountId(account.accountId)

        if (!client.session().supportsInboxTabs(accountId)) {
            mail.replaceInboxTabs(accountKey, emptyList(), state = null)
            return
        }

        val since = account.inboxTabState

        if (since != null) {
            try {
                val request = RequestBuilder(using = Capability.USING_INBOX_TABS)
                val handle = request.add(InboxTabChanges(accountId, StateToken(since)))
                // Whether each tab holds mail, in the same request (#52): the
                // tabs are the cached ones, which is what they still are when
                // the answer is "nothing changed".
                val probes = database.addTabProbes(request, accountKey, accountId)
                val results = client.send(request)
                val changes = results.result(handle)

                // The common answer, and the reason this asks /changes first: one
                // request that says "nothing", rather than re-reading every tab
                // on every push that happened to name the type.
                if (changes.isEmpty) {
                    database.accounts().setInboxTabState(accountKey, changes.newState)
                    database.applyTabProbes(results, probes)
                    return
                }
            } catch (resync: JmapError) {
                if (!resync.requiresResync) throw resync
            }
        }

        // A full read whenever anything moved. A reorder reports every tab as
        // updated anyway, and replacing the set is what removes one the reader
        // deleted -- the same reasoning `replaceMailboxes` gives.
        val request = RequestBuilder(using = Capability.USING_INBOX_TABS)
        val handle = request.add(InboxTabGet(accountId))
        val result = client.send(request).result(handle)

        mail.replaceInboxTabs(accountKey, result.list, result.state)
        database.probeInboxTabs(client, accountKey, accountId)
    }

    private fun List<InboxTabEntity>.toStrip(): List<MailTab> = filter {
        it.isEnabled
    }
        .sortedBy { it.sortOrder }
        .map { it.toMailTab() }
}

/**
 * Asks whether each of an account's enabled tabs holds inbox mail (#52): one `Email/query` per tab,
 * the conditions its list pages with, `limit: 1`. Primary is not asked; it is always listed.
 *
 * Added to [request] so the question can ride along with another one; [applyTabProbes] stores the
 * answers. At most [MAX_TAB_PROBES], so the request stays inside the call limit a server sets.
 */
internal suspend fun PlMailDatabase.addTabProbes(
    request: RequestBuilder,
    accountKey: String,
    accountId: AccountId,
): Map<String, MethodHandle<EmailQueryResult>> {
    val inbox =
        mailboxes().byRole(accountKey, INBOX_ROLE)?.let {
            EmailFilter.InMailbox(MailboxId(it.mailboxId))
        }

    return inboxTabs()
        .forAccount(accountKey)
        .filter { it.isEnabled && it.tabId != MailCategory.PRIMARY.wire }
        .take(MAX_TAB_PROBES)
        .associate { tab ->
            val inTab = EmailFilter.ThreadInboxTab(tab.tabId)
            val filter = inbox?.let { EmailFilter.And(listOf(it, inTab)) } ?: inTab

            tab.uid to
                request.add(
                    EmailQuery(accountId, filter = filter, limit = 1, collapseThreads = true)
                )
        }
}

/** Stores what [addTabProbes] asked. A query that failed leaves its tab as it was. */
internal suspend fun PlMailDatabase.applyTabProbes(
    results: MethodResults,
    probes: Map<String, MethodHandle<EmailQueryResult>>,
) {
    probes.forEach { (uid, handle) ->
        results.resultOrNull(handle)?.let { inboxTabs().setHasMail(uid, it.ids.isNotEmpty()) }
    }
}

/** [addTabProbes] as a request of its own, for after the tabs were read. Never throws. */
internal suspend fun PlMailDatabase.probeInboxTabs(
    client: JmapClient,
    accountKey: String,
    accountId: AccountId,
) {
    runCatching {
        val request = RequestBuilder(using = Capability.USING_INBOX_TABS)
        val probes = addTabProbes(request, accountKey, accountId)
        if (probes.isEmpty()) return

        applyTabProbes(client.send(request), probes)
    }
        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
}

/** One under the 16 calls a request may carry by default: room for the call it rides with. */
private const val MAX_TAB_PROBES = 15

private const val INBOX_ROLE = "inbox"

internal fun InboxTabEntity.toMailTab(): MailTab =
    MailTab(
        key = tabId,
        // A built-in this build cannot name is drawn as one of the reader's own
        // would be, under its key: better a tab with a plain name than one that
        // vanishes.
        builtIn = MailCategory.fromWire(builtIn),
        name = name ?: tabId.takeIf { MailCategory.fromWire(builtIn) == null },
        sortOrder = sortOrder,
    )

internal fun InboxTab.toEntity(accountKey: String): InboxTabEntity =
    InboxTabEntity(
        uid = StoreKey.objectKey(accountKey, id),
        accountKey = accountKey,
        tabId = id,
        name = name,
        builtIn = builtIn,
        isEnabled = isEnabled,
        sortOrder = sortOrder,
    )
