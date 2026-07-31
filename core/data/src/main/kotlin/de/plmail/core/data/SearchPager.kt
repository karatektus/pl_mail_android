package de.plmail.core.data

import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.mail.Comparator
import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.EmailFilter
import de.plmail.jmap.methods.EmailGet
import de.plmail.jmap.methods.EmailQuery
import de.plmail.jmap.methods.SearchSnippet
import de.plmail.jmap.methods.SearchSnippetGet
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.RequestBuilder
import java.time.Instant

/**
 * One account's search results, fetched a page at a time.
 *
 * [AccountPager] with a third method in the same request: the query, the get that consumes its ids,
 * and the snippets for those same ids. Not a subclass — the shapes differ in what they report and
 * what they record, and the interesting behaviour is the third call, which would be a flag on an
 * already dense class.
 *
 * The snippets go out **back-referenced against the same query**, which is the point of sending
 * them together. Asked separately they could be answered after new mail arrived and describe a
 * different page, and the reader would see a highlight that does not appear in the row it sits on.
 *
 * Deliberately does *not* record the Email state. A search is a filtered view of a mailbox, and its
 * pages say nothing about how far the account's delta sync has got — writing a cursor from here
 * would tell `Email/changes` to resume from a point it has never actually reached, and everything
 * between would be silently skipped.
 */
class SearchPager(
    override val accountKey: String,
    private val accountId: AccountId,
    private val client: JmapClient,
    /** The compiled search. Never null: an account that matches nothing is not paged at all. */
    private val filter: EmailFilter,
    /** Receives the page and the snippets keyed by email id. */
    private val onPage: suspend (List<Email>, Map<String, SearchSnippet>) -> Unit = { _, _ -> },
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
                    // Threads are collapsed here as in the inbox, so a search
                    // returns conversations rather than the same subject eight
                    // times. The snippet then describes the message that
                    // matched, which is often not the one the row shows.
                    collapseThreads = true,
                    limit = limit + alreadyEmitted.size,
                )
            )

        val get = request.add(EmailGet.byReference(accountId, query.reference("/ids")))

        val snippets =
            request.add(SearchSnippetGet.byReference(accountId, query.reference("/ids"), filter))

        val results = client.send(request)
        val ids = results.result(query).ids
        val messages = results.result(get).ordered(ids)

        onPage(messages, results.result(snippets).byEmail())

        val rows =
            messages
                .filter { it.id.value !in alreadyEmitted }
                .mapNotNull { email ->
                    val sortDate = email.receivedAt.toEpochMillis() ?: return@mapNotNull null

                    FeedRow(
                        accountKey = accountKey,
                        id = email.id.value,
                        threadId = email.threadId?.value ?: email.id.value,
                        sortDate = sortDate,
                    )
                }

        return FeedPage(rows = rows, isExhausted = ids.size < limit + alreadyEmitted.size)
    }

    /** As in [AccountPager]: `before` is strict at one-second granularity, so open the window. */
    private fun windowed(atOrBefore: Long?): EmailFilter {
        val cursor = atOrBefore ?: return filter

        val before = EmailFilter.Before(utc = Instant.ofEpochMilli(cursor).plusSeconds(1).utc())

        return EmailFilter.And(listOf(filter, before))
    }

    private companion object {
        fun Instant.utc(): String =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(this)
    }
}
