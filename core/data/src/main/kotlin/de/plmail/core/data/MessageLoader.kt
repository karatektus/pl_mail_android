package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.datastore.CredentialStore
import de.plmail.jmap.methods.EmailGet
import de.plmail.jmap.methods.ThreadGet
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.RequestBuilder
import de.plmail.jmap.protocol.ResultReference
import de.plmail.jmap.protocol.ThreadId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fetches a message in full, on demand.
 *
 * List pages deliberately do not carry bodies — `EmailGet.LIST_ROW_PROPERTIES` stops short of them
 * because a body can be 400KB and a page of fifty would be twenty megabytes to draw a list nobody
 * has scrolled yet. So the reader asks for the rest when a conversation is opened, and this is what
 * asks.
 *
 * Bodies land in their own table, so a later list query still never faults one in.
 */
@Singleton
class MessageLoader
@Inject
constructor(
    private val database: PlMailDatabase,
    private val credentials: CredentialStore,
    private val clients: AccountClients,
    private val mail: MailRepository,
) {

    /**
     * Downloads one conversation as the server holds it: the messages the device has never seen,
     * and the bodies of those it has, skipping anything already cached.
     *
     * **Every message, not only the cached ones (#62).** A list page is collapsed to one message
     * per conversation, so a thread paged in from the list arrives as its newest message alone, and
     * nothing else ever fetched the rest: a conversation of four opened as one, under its last
     * reply's subject. So a conversation the device does not hold whole is asked what it holds, and
     * its members fetched in the same request.
     *
     * **Nothing at all for what is cached.** The list pages and the sync record how many messages
     * each conversation has; holding that many with their bodies, the reader has everything, and a
     * request would only make it wait on the server for the same answer.
     *
     * Returns silently when there is no connection: the reader shows what it has either way.
     */
    suspend fun loadBodies(accountKey: String, threadId: String) =
        load(accountKey, listOf(threadId), opened = true)

    /**
     * The same for conversations on screen in the list, before anybody opens them: one request for
     * all of them, so the first open of each draws from the cache.
     *
     * Not an open, so nothing here counts as reading for eviction.
     */
    suspend fun prefetch(accountKey: String, threadIds: List<String>) =
        load(accountKey, threadIds.distinct(), opened = false)

    private suspend fun load(accountKey: String, threadIds: List<String>, opened: Boolean) {
        if (threadIds.isEmpty()) return

        BodyDownloads.oneAtATime(accountKey) { loadLocked(accountKey, threadIds, opened) }
    }

    private suspend fun loadLocked(accountKey: String, threadIds: List<String>, opened: Boolean) {
        val stored = threadIds.associateWith { database.emails().inThread(accountKey, it) }

        // A draft counts as missing however much of it is on disk. Everything
        // else in this table is a message that has arrived and will never say
        // anything different, which is what makes caching a body once correct;
        // a draft is the one row somebody is still editing, quite possibly in
        // another window right now. `MailRepository.storeEmails` drops the
        // cached copy whenever a sync touches the row, and this is the other
        // half: a conversation reopened without a sync in between still shows
        // the draft as it stands rather than as it was first seen.
        //
        // So does a row whose cache contradicts it -- see `contradicts`. That
        // is the repair, and it has to be here rather than only in the sync:
        // the mail this happened to was sent weeks ago and the server will
        // never report it as changed again, so a phone already holding a
        // poisoned marker would keep it forever. This is the screen where the
        // blank message is, and it is where it gets fixed.
        val (missing, held) =
            stored.values.flatten().partition { email ->
                val body = database.emails().body(email.uid)
                email.isDraft || body == null || body.contradicts(email)
            }

        val now = System.currentTimeMillis()

        // Opening a conversation is the event eviction should be measured from,
        // and until this it was not measured at all: `fetchedAt` recorded when a
        // body was *downloaded*, so `BodyPrefetcher.prune` would drop a thread
        // reread every week on the sixtieth day after its one fetch. Done before
        // the early return, because a thread whose bodies are all cached is
        // precisely the one being reread.
        if (opened && held.isNotEmpty()) {
            database.emails().touchBodies(held.map { it.uid }, now)
        }

        // How many messages the server last said each conversation holds. The
        // list pages and the sync record it, so when the device holds that
        // many, the conversation is known whole and only its missing bodies
        // are worth a request -- none at all when nothing is missing, which is
        // most opens.
        val partial = threadIds.filter { threadId ->
            val counted = database.threads().byUid(StoreKey.objectKey(accountKey, threadId))
            counted == null || stored.getValue(threadId).size < counted.messageCount
        }
        val missingFromWhole = missing.filter { it.threadId !in partial }

        if (partial.isEmpty() && missingFromWhole.isEmpty()) return

        credentials.connection.first() ?: return
        val account = database.accounts().byUid(accountKey) ?: return

        // The shared client, whose Session is already known: a client of its
        // own asked the server for it again on every conversation opened.
        val client = clients.current() ?: return

        val accountId = AccountId(account.accountId)

        val request = RequestBuilder()
        val bodies =
            missingFromWhole
                .takeIf { it.isNotEmpty() }
                ?.let {
                    request.add(readerGet(accountId, it.map { email -> EmailId(email.emailId) }))
                }

        // The conversations not known whole are asked what they hold and their
        // members sent in the same request: one round trip rather than asking
        // and then fetching. That re-sends bodies already cached, but such a
        // conversation is nearly always one paged in from the list as its
        // newest message alone, with no bodies anyway.
        val threadGet =
            partial
                .takeIf { it.isNotEmpty() }
                ?.let { request.add(ThreadGet(accountId, ids = it.map(::ThreadId))) }
        val members = threadGet?.let {
            request.add(readerGet(accountId, ids = null, it.reference("/list/*/emailIds")))
        }

        val results = client.send(request)
        val threads = threadGet?.let { results.result(it).list }.orEmpty()
        val fetched =
            bodies?.let { results.result(it).list }.orEmpty() +
                members?.let { results.result(it).list }.orEmpty()

        // A conversation the server no longer has -- merged into another, or
        // erased -- named nothing for the reference to fetch, and the bodies
        // the device lacks are all there is to fetch.
        val found = threads.map { it.id.value }.toSet()
        val lost = missing.filter { it.threadId in partial && it.threadId !in found }
        val emails =
            if (lost.isEmpty()) fetched
            else {
                val retry = RequestBuilder()
                val get = retry.add(readerGet(accountId, lost.map { EmailId(it.emailId) }))
                fetched + client.send(retry).result(get).list
            }

        if (emails.isEmpty() && threads.isEmpty()) return

        // Through the repository, so bodies and attachments are written the same
        // way a sync writes them and the thread summary is recomputed once --
        // with the conversation as the server just described it, so its row
        // counts the messages that have only now arrived.
        mail.storeEmails(accountKey, emails, threads, fetchedAt = now)

        // A message with neither text nor html stores no body row, so without
        // this it is "missing a body" again the next time the thread is opened --
        // one `Email/get` per open, forever, for a message that has nothing to
        // fetch. See `markFetchedBodylessMessages`.
        database.markFetchedBodylessMessages(accountKey, emails, now)
    }
}

/**
 * One body download per account at a time, whoever asks.
 *
 * A conversation pressed in the list starts downloading, and the reader the release opens asks for
 * the same conversation a moment later; the rows in view and the newest mail are fetched ahead on
 * the same foreground. Waiting for the first finds its bodies cached, where running beside it would
 * download them twice. Process-wide because the askers are separate classes and the bodies one
 * table.
 */
internal object BodyDownloads {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> oneAtATime(accountKey: String, download: suspend () -> T): T =
        locks.getOrPut(accountKey) { Mutex() }.withLock { download() }
}

/** Messages as the reader draws them, bodies included. */
private fun readerGet(
    accountId: AccountId,
    ids: List<EmailId>?,
    idsReference: ResultReference? = null,
) =
    EmailGet(
        accountId = accountId,
        ids = ids,
        idsReference = idsReference,
        properties = EmailGet.READER_PROPERTIES,
        fetchTextBodyValues = true,
        // NOTE the capitalisation inside EmailGet: fetchHTMLBodyValues. The wrong
        // spelling is silently ignored and returns empty body values with nothing
        // to debug.
        fetchHtmlBodyValues = true,
    )
