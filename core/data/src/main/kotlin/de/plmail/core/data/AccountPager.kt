package de.plmail.core.data

import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.mail.Comparator
import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.EmailFilter
import de.plmail.jmap.methods.EmailGet
import de.plmail.jmap.methods.EmailQuery
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.RequestBuilder
import java.time.Instant

/**
 * One account's rows, fetched a page at a time.
 *
 * The query and the get that consumes it go out as **one request**, back-referenced. The server may
 * be a Raspberry Pi on a domestic uplink and the session allows four concurrent requests, so round
 * trips are the scarce resource — and the alternative, waiting for ids and asking again, doubles
 * every page's latency for no benefit.
 *
 * Rows are emitted through [onPage] before they are returned, so the cache is written in the same
 * step that produced them. A feed that returned rows the database had never seen would draw a list
 * whose contents vanish on the next cold launch.
 */
class AccountPager(
    override val accountKey: String,
    private val accountId: AccountId,
    private val client: JmapClient,
    /** The list being paged — an inbox, a label, a search. Null pages everything. */
    private val filter: EmailFilter? = null,
    private val onPage: suspend (List<Email>) -> Unit = {},
) : FeedSource {

    override suspend fun page(
        atOrBefore: Long?,
        alreadyEmitted: Set<String>,
        limit: Int,
    ): FeedPage {
        val request = RequestBuilder()

        val query =
            request.add(
                EmailQuery(
                    accountId = accountId,
                    filter = windowed(atOrBefore),
                    sort = listOf(Comparator.NEWEST_FIRST),
                    // One row per conversation: this is a thread list, and
                    // without it a chatty thread fills the whole page with
                    // itself.
                    collapseThreads = true,
                    // Over-fetch by exactly what has to be discarded. Every id
                    // already emitted at the boundary second will come back
                    // again, so asking for `limit` would return a short page
                    // and read as the end of the list.
                    limit = limit + alreadyEmitted.size,
                )
            )

        val get = request.add(EmailGet.byReference(accountId, query.reference("/ids")))

        val results = client.send(request)
        val ids = results.result(query).ids

        // ordered(), never `list`: Email/get reads rows in repository order and
        // computes notFound by difference, so a query answered newest-first
        // comes back oldest-first. A list rendered from `list` is sorted by
        // database id, which looks almost right on a small mailbox.
        val messages = results.result(get).ordered(ids)

        onPage(messages)

        val rows =
            messages
                .filter { it.id.value !in alreadyEmitted }
                .mapNotNull { email ->
                    // A message whose date will not parse cannot be placed in a
                    // date-ordered list at all. Dropping it here keeps it out
                    // of the feed while leaving it in the cache, where the
                    // reader can still open it.
                    val sortDate = email.receivedAt.toEpochMillis() ?: return@mapNotNull null

                    FeedRow(
                        accountKey = accountKey,
                        id = email.id.value,
                        threadId = email.threadId?.value ?: email.id.value,
                        sortDate = sortDate,
                    )
                }

        // Exhaustion is judged on what the *server* returned, not on what
        // survived filtering: a page made entirely of already-emitted boundary
        // rows is not the end of the mailbox.
        return FeedPage(rows = rows, isExhausted = ids.size < limit + alreadyEmitted.size)
    }

    /**
     * Narrows [filter] to messages at or before the cursor.
     *
     * `before` is a strict `<` at one-second granularity, so the window is opened to the *next*
     * second and the ids already emitted are subtracted by the caller. Asking for `before: cursor`
     * would drop every message sharing that second — which is a batch delivery, not an edge case.
     */
    private fun windowed(atOrBefore: Long?): EmailFilter? {
        val cursor = atOrBefore ?: return filter

        val before = EmailFilter.Before(utc = Instant.ofEpochMilli(cursor).plusSeconds(1).utc())

        return if (filter == null) before else EmailFilter.And(listOf(filter, before))
    }

    private companion object {
        /**
         * `2026-07-31T16:55:43Z`, which is what the server parses.
         *
         * `Instant.toString()` omits the seconds when they are zero — `2026-07-31T16:55Z` — and
         * that is not a UTCDate. It is accepted by some parsers and rejected by others, so the
         * seconds are forced rather than left to chance.
         */
        fun Instant.utc(): String =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(this)
    }
}
