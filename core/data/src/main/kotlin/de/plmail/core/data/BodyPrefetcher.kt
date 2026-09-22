package de.plmail.core.data

import de.plmail.core.database.EmailBodyEntity
import de.plmail.core.database.EmailEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.jmap.mail.Email
import de.plmail.jmap.methods.EmailGet
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.RequestBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads the bodies of recently arrived mail before anybody asks for them.
 *
 * Every sync stores list-row properties only — deliberately, because a page of fifty bodies is
 * twenty megabytes to draw a list nobody has scrolled. The cost of that is paid one conversation at
 * a time: the *first* open of every thread waits on the network, on the one screen where the mail
 * is a second old and the user has just tapped a notification about it.
 *
 * So the bodies come down afterwards, on the background paths only. Nothing here ever runs in front
 * of something the user is waiting for: it is on the periodic worker and on the push handler, and
 * explicitly not on the foreground `syncAll()` that pull-to-refresh spins for.
 *
 * Bounded on both sides. [BUDGET] messages per account per run, so an account that has just been
 * paged back to 2019 does not turn one sync into an overnight download, and [prune] gives the space
 * back for anything nobody has read in [RETENTION_DAYS] days.
 */
@Singleton
class BodyPrefetcher
@Inject
constructor(
    private val database: PlMailDatabase,
    private val clients: AccountClients,
    private val mail: MailRepository,
) {

    /**
     * Every account, in turn, each account's failure its own.
     *
     * `runCatching` per account rather than around the loop: the accounts behind one credential can
     * fail independently — one is a server that is down, one is a mailbox the credential lost
     * access to — and the first of them must not decide that the other three go without bodies.
     */
    suspend fun prefetchAll() {
        database.accounts().all().forEach { runCatching { prefetch(it.uid) } }
    }

    /** The newest [BUDGET] messages of one account that have no body on the device. */
    suspend fun prefetch(accountKey: String) {
        val account = database.accounts().byUid(accountKey) ?: return
        val missing = database.emails().withoutBodies(accountKey, BUDGET)
        if (missing.isEmpty()) return

        val client = clients.forAccount(accountKey) ?: return
        val accountId = AccountId(account.accountId)

        missing.chunked(CHUNK).forEach { chunk ->
            val request = RequestBuilder()
            val get =
                request.add(
                    EmailGet(
                        accountId = accountId,
                        ids = chunk.map { EmailId(it.emailId) },
                        properties = EmailGet.READER_PROPERTIES,
                        fetchTextBodyValues = true,
                        // NOTE the capitalisation inside EmailGet:
                        // fetchHTMLBodyValues. The wrong spelling is silently
                        // ignored and returns empty body values with nothing to
                        // debug.
                        fetchHtmlBodyValues = true,
                    )
                )

            val emails = client.send(request).result(get).list
            val now = System.currentTimeMillis()

            // Through the repository, so bodies and attachments are written the
            // same way a sync writes them and the thread summary is recomputed
            // once.
            mail.storeEmails(accountKey, emails, fetchedAt = now)
            database.markFetchedBodylessMessages(accountKey, emails, now)
        }
    }

    /**
     * Drops bodies nobody has read in a long time.
     *
     * The message rows stay: what is evicted is re-fetchable on demand and is the part that is
     * large. Run after [prefetchAll] rather than before, so a body downloaded this minute is never
     * measured against a threshold it was not yet on the right side of.
     */
    suspend fun prune() {
        database.emails().evictBodiesOlderThan(System.currentTimeMillis() - RETENTION_MILLIS)
    }

    private companion object {
        /**
         * Messages per account per run.
         *
         * Fifty is about two screens of list. Past that the prefetch stops being "the mail you are
         * about to read" and becomes a mirror of the server, which is neither what the user asked
         * for nor what a phone on a metered connection can afford — and the worker comes back every
         * fifteen minutes, so a genuine backlog drains anyway.
         */
        const val BUDGET = 50

        /**
         * Twenty per `Email/get`, well under the hundred a delta sync uses.
         *
         * The difference is bodies. A hundred rows is a hundred small entities; a hundred *bodies*
         * is a response the server has to hold in memory to build, on hardware that is frequently a
         * Raspberry Pi.
         */
        const val CHUNK = 20

        const val RETENTION_DAYS = 60L
        const val RETENTION_MILLIS = RETENTION_DAYS * 24 * 60 * 60 * 1000
    }
}

/**
 * Records that these messages were fetched and genuinely have no body.
 *
 * `toBodyEntity` returns null when a message has neither text nor html, so that a body that was
 * never fetched stays distinguishable from an empty one — which is right, and leaves the genuinely
 * empty message with no row at all. Every query for "what is missing a body" then answers with it,
 * forever: it would be refetched on every prefetch run, occupying budget that belongs to mail that
 * actually has something to download, and refetched on every open of the conversation it is in.
 *
 * An empty string rather than null in `textBody`, because null is what an unfetched body looks like
 * everywhere else and the whole point of the row is to be distinguishable from one.
 *
 * Written only where no row exists, so this can never overwrite a body [MailRepository] has just
 * stored — including on the ordinary path, where nearly every fetched message has one.
 *
 * **Never for a draft.** The marker is a claim that this message has nothing to download, and the
 * one kind of message that can acquire a body later is the one somebody is still writing. An
 * autosave routinely beats the first keystroke, so the empty draft behind every reply sent from the
 * phone was being recorded as permanently bodyless — and it kept that record after it was sent,
 * because sending does not change the message id. See `MailRepository.staleBodies`, which clears up
 * after the markers this used to leave.
 */
internal suspend fun PlMailDatabase.markFetchedBodylessMessages(
    accountKey: String,
    fetched: List<Email>,
    at: Long,
) {
    fetched
        .filterNot { it.isDraft }
        .forEach { email ->
            val uid = StoreKey.objectKey(accountKey, email.id.value)

            if (emails().body(uid) == null) {
                emails().upsertBody(EmailBodyEntity(uid = uid, textBody = "", fetchedAt = at))
            }
        }
}

/**
 * Whether this cached body claims the message has nothing, while the message says otherwise.
 *
 * The marker written by [markFetchedBodylessMessages] is a statement of fact — "asked, and there is
 * no body to download" — and a `preview` is the opening of the body text: RFC 8621 derives it from
 * the body, and plMail computes it from the stored text. Both cannot be true, so a row where they
 * disagree is one the cache got wrong, which is exactly what an empty draft left behind before that
 * function learned to skip them.
 *
 * The same rule in SQL is `EmailDao.bodylessMarkersAmong`, which `MailRepository.storeEmails` reads
 * over a whole page; this is the per-row form the reader uses. A refetch either fills the row in —
 * which is the end of it — or writes the marker back, and costs one `Email/get` per open of the one
 * conversation until it does.
 */
internal fun EmailBodyEntity.contradicts(email: EmailEntity): Boolean =
    htmlBody == null && textBody.isNullOrEmpty() && email.preview.isNotBlank()
