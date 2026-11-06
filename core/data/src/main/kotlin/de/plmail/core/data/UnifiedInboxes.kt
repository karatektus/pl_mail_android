package de.plmail.core.data

import androidx.room.withTransaction
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.database.UnifiedInboxEntity
import de.plmail.core.datastore.CredentialStore
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.mail.UnifiedInbox
import de.plmail.jmap.methods.UnifiedInboxGet
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.RequestBuilder
import de.plmail.jmap.protocol.Session
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One of the reader's unified inboxes as the app draws it (pl_mail#265).
 *
 * [accountKeys] are only the accounts this device knows, so an inbox never lists an account the
 * phone cannot page. [name] null means the app's own word: "Inbox", or "Other" for [isOther].
 */
data class MailInbox(
    val id: String,
    val name: String?,
    val icon: String,
    val isOther: Boolean,
    val accountKeys: Set<String>,
)

/**
 * The inboxes the navigation draws, one entry each, or empty for one inbox over every account.
 *
 * Empty on a server without the extension, and also where only one inbox is left to draw: that one
 * then holds every account, so it is the inbox the app has always shown and nothing about it
 * changes — not its lists, not its rows, not the feed they are kept under.
 */
fun List<MailInbox>.navigable(): List<MailInbox> = takeIf { it.size > 1 }.orEmpty()

/**
 * The feed id an inbox list is kept under when it covers one unified inbox only.
 *
 * A list of its own per inbox rather than the shared one filtered on the way out, because the
 * mediator refreshes a feed by clearing it: a shared Primary would lose the Work rows every time
 * Personal was pulled down. Only inbox lists are ever scoped — labels, Starred, Snoozed and the
 * rest stay across every account, as on the web — so every scoped feed is an inbox feed.
 */
fun scopedFeedId(inboxId: String, base: String): String = "$INBOX_SCOPE_PREFIX$inboxId/$base"

/** The list a scoped feed is a part of — `category.primary` for `ui.12/category.primary`. */
fun baseFeedId(feedId: String): String =
    if (feedId.startsWith(INBOX_SCOPE_PREFIX)) feedId.substringAfter('/') else feedId

/** The inbox a scoped feed belongs to, or null for a feed over every account. */
fun inboxOfFeed(feedId: String): String? =
    feedId
        .takeIf { it.startsWith(INBOX_SCOPE_PREFIX) }
        ?.removePrefix(INBOX_SCOPE_PREFIX)
        ?.substringBefore('/')

internal const val INBOX_SCOPE_PREFIX = "ui."

private const val INBOX_ROLE = "inbox"

/** The one method of [UnifiedInboxRepository] the push path needs. */
fun interface UnifiedInboxRefresh {
    suspend fun refresh()
}

/** The reader's unified inboxes, cached per server and read again when they move. */
@Singleton
class UnifiedInboxRepository
@Inject
constructor(
    private val database: PlMailDatabase,
    private val clients: AccountClients,
    private val credentials: CredentialStore,
) : UnifiedInboxRefresh {

    private val refreshing = Mutex()

    /**
     * Every inbox worth an entry, in the reader's order.
     *
     * Other is left out while none of its accounts is on this device, as the web leaves it out
     * while none is switched on: a switched-off account is not in the Session, so it never reaches
     * the accounts table. A named inbox stays even when empty, as on the web.
     */
    val inboxes: Flow<List<MailInbox>> =
        combine(database.unifiedInboxes().observeAll(), database.accounts().observeAll()) {
                rows,
                accounts ->
                val known = accounts.groupBy({ it.serverId }, { it.uid })

                rows
                    .filter { it.serverId in known }
                    .map { it.toMailInbox(known.getValue(it.serverId).toSet()) }
                    .filterNot { it.isOther && it.accountKeys.isEmpty() }
            }
            .distinctUntilChanged()

    /** [inboxes] as the navigation draws them — see [navigable]. */
    val navigable: Flow<List<MailInbox>> = inboxes.map { it.navigable() }.distinctUntilChanged()

    /**
     * Unread conversations in each inbox, by inbox id: the Inbox of its accounts, as the web's
     * badge counts them.
     */
    val unread: Flow<Map<String, Int>> =
        combine(inboxes, database.mailboxes().observeAll()) { all, mailboxes ->
                val byAccount =
                    mailboxes
                        .filter { it.role == INBOX_ROLE }
                        .groupBy { it.accountKey }
                        .mapValues { (_, boxes) -> boxes.sumOf { it.unreadThreads } }

                all.associate { inbox ->
                    inbox.id to inbox.accountKeys.sumOf { byAccount[it] ?: 0 }
                }
            }
            .distinctUntilChanged()

    /** The accounts one inbox lists, or null when no such inbox is cached. */
    suspend fun accountsOf(server: String, inboxId: String): Set<String>? {
        val known = database.accounts().all().filter { it.serverId == server }.map { it.uid }
        val row = database.unifiedInboxes().byId(server, inboxId) ?: return null

        return row.toMailInbox(known.toSet()).accountKeys
    }

    /** Reads the inboxes again, as a push naming `UnifiedInbox` asks. */
    override suspend fun refresh() {
        val server = credentials.connection.first()?.address?.origin ?: return
        val client = clients.current() ?: return
        val session = runCatching { client.session() }.getOrNull() ?: return

        fetch(client, server, session)
    }

    /**
     * One `UnifiedInbox/get` against the first account that serves it, or a clear-out where none
     * does. Single-flighted: a push names the type on every account at once.
     */
    suspend fun fetch(client: JmapClient, server: String, session: Session) = refreshing.withLock {
        fetchLocked(client, server, session)
    }

    private suspend fun fetchLocked(client: JmapClient, server: String, session: Session) {
        val asked = session.accountIds.firstOrNull { session.supportsUnifiedInboxes(it) }

        if (asked == null) {
            store(server, null)
            return
        }

        val request = RequestBuilder(using = Capability.USING_UNIFIED_INBOXES)
        val get = request.add(UnifiedInboxGet(asked))
        val answer = runCatching { client.send(request).resultOrNull(get) }.getOrNull() ?: return

        store(server, answer.list)
    }

    /**
     * Replaces what [server]'s inboxes are, and forgets the lists whose accounts changed.
     *
     * An inbox that gained or lost an account had its lists paged over the old set: the lost one's
     * rows would stay in them, and the gained one's would only appear below everything already
     * paged. So those lists are emptied, and the next pager built over them pages them afresh. Null
     * is a server that stopped advertising the extension.
     */
    suspend fun store(server: String, inboxes: List<UnifiedInbox>?) {
        val fresh = inboxes.orEmpty().map { it.toEntity(server) }

        database.withTransaction {
            val before = database.unifiedInboxes().forServer(server)
            val was = before.associate { it.inboxId to it.accountKeys }
            val now = fresh.associate { it.inboxId to it.accountKeys }

            (was.keys + now.keys)
                .filter { was[it] != now[it] }
                .forEach { database.feed().clearFeedsStartingWith("$INBOX_SCOPE_PREFIX$it/") }

            database.unifiedInboxes().deleteForServer(server)
            database.unifiedInboxes().upsert(fresh)
        }
    }
}

private fun UnifiedInbox.toEntity(server: String): UnifiedInboxEntity =
    UnifiedInboxEntity(
        uid = "$server#$id",
        serverId = server,
        inboxId = id,
        name = name?.takeIf { it.isNotBlank() },
        icon = icon,
        sortOrder = sortOrder,
        isOther = isOther,
        accountKeys = accountIds.joinToString(",") { StoreKey.account(server, it) },
    )

private fun UnifiedInboxEntity.toMailInbox(known: Set<String>): MailInbox =
    MailInbox(
        id = inboxId,
        name = name,
        icon = icon,
        isOther = isOther,
        accountKeys =
            accountKeys
                .split(",")
                .filter { it.isNotBlank() && it in known }
                .toCollection(LinkedHashSet()),
    )
