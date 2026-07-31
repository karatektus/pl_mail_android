package de.plmail.jmap.search

import de.plmail.jmap.mail.MailboxRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchQueryTest {

    /** Berlin so the local-midnight anchoring is visible as a real offset. */
    private val clock =
        Clock.fixed(Instant.parse("2026-07-31T09:00:00Z"), ZoneId.of("Europe/Berlin"))

    @Test
    fun `an empty query asks for nothing`() {
        assertTrue(SearchQuery.parse("").isEmpty)
        assertTrue(SearchQuery.parse("    ").isEmpty)
        assertFalse(SearchQuery.parse("invoice").isEmpty)
    }

    @Test
    fun `an unknown operator is searched for rather than rejected`() {
        // Colon and all: the user gets a search, not an error. A search box
        // that refuses input is worse than one that searches for it.
        assertEquals(SearchQuery(freeText = "weird:thing"), SearchQuery.parse("weird:thing"))
    }

    @Test
    fun `a token that is only a colon is free text`() {
        assertEquals(SearchQuery(freeText = ":inbox"), SearchQuery.parse(":inbox"))
    }

    @Test
    fun `an operator with no value is recognised with an empty value`() {
        // Matches the server's DTO, which stores "". The compiler is what
        // declines to turn it into a filter matching everything.
        assertEquals(SearchQuery(from = ""), SearchQuery.parse("from:"))
    }

    @Test
    fun `free text fragments combine into one query`() {
        assertEquals("quarterly report draft", SearchQuery.parse("quarterly report draft").freeText)
    }

    @Test
    fun `quotes survive into the free text so a phrase stays a phrase`() {
        // websearch_to_tsquery reads a quoted run as a phrase; stripping the
        // quotes here would silently turn it into a bag of words.
        assertEquals(
            "\"quarterly report\" invoice",
            SearchQuery.parse("\"quarterly report\" invoice").freeText,
        )
    }

    @Test
    fun `a quoted operator value is kept together`() {
        assertEquals(
            SearchQuery(subject = "quarterly report"),
            SearchQuery.parse("subject:\"quarterly report\""),
        )
    }

    @Test
    fun `an unterminated quote swallows the rest of the query`() {
        // The forgiving reading of a half-typed query, and what the server does.
        assertEquals(
            SearchQuery(subject = "quarterly report"),
            SearchQuery.parse("subject:\"quarterly report"),
        )
    }

    @Test
    fun `a single-quoted value is unwrapped too`() {
        assertEquals(SearchQuery(from = "alice"), SearchQuery.parse("from:'alice'"))
    }

    @Test
    fun `operator names are case insensitive but values are not`() {
        assertEquals(SearchQuery(from = "Alice"), SearchQuery.parse("FROM:Alice"))
    }

    @Test
    fun `a value keeps every colon after the first`() {
        assertEquals(SearchQuery(subject = "re:re:hello"), SearchQuery.parse("subject:re:re:hello"))
    }

    @Test
    fun `from to and subject are taken verbatim`() {
        val query = SearchQuery.parse("from:alice@example.com to:bob subject:Invoice")

        assertEquals("alice@example.com", query.from)
        assertEquals("bob", query.to)
        assertEquals("Invoice", query.subject)
        assertEquals("", query.freeText)
    }

    @Test
    fun `has attachment accepts the plural`() {
        assertTrue(SearchQuery.parse("has:attachment").hasAttachment)
        assertTrue(SearchQuery.parse("has:Attachments").hasAttachment)
    }

    @Test
    fun `an unknown has value narrows nothing and searches for nothing`() {
        // Dropped, not turned into free text — the token is consumed either way.
        assertTrue(SearchQuery.parse("has:banana").isEmpty)
    }

    @Test
    fun `is sets read state and starred independently`() {
        assertTrue(SearchQuery.parse("is:unread").isUnread)
        assertTrue(SearchQuery.parse("IS:Read").isRead)
        assertTrue(SearchQuery.parse("is:starred").isStarred)
    }

    @Test
    fun `is unread and is read together are kept even though nothing matches both`() {
        val query = SearchQuery.parse("is:unread is:read")

        // Reproduced rather than resolved: guessing an intent here would make
        // the phone disagree with the web UI about the same string.
        assertTrue(query.isUnread)
        assertTrue(query.isRead)
    }

    @Test
    fun `an unknown is value narrows nothing and searches for nothing`() {
        assertTrue(SearchQuery.parse("is:bogus").isEmpty)
    }

    @Test
    fun `in names each mailbox role the syntax offers`() {
        assertEquals(MailboxRole.INBOX, SearchQuery.parse("in:inbox").mailbox)
        assertEquals(MailboxRole.SENT, SearchQuery.parse("in:sent").mailbox)
        assertEquals(MailboxRole.DRAFTS, SearchQuery.parse("in:drafts").mailbox)
        assertEquals(MailboxRole.TRASH, SearchQuery.parse("in:trash").mailbox)
        assertEquals(MailboxRole.ARCHIVE, SearchQuery.parse("in:Archive").mailbox)
        assertEquals(MailboxRole.JUNK, SearchQuery.parse("in:junk").mailbox)
    }

    @Test
    fun `in accepts the draft and spam synonyms the server takes`() {
        assertEquals(MailboxRole.DRAFTS, SearchQuery.parse("in:draft").mailbox)
        assertEquals(MailboxRole.JUNK, SearchQuery.parse("in:spam").mailbox)
    }

    @Test
    fun `in ignores roles the search syntax does not offer`() {
        // `flagged` is a real MailboxRole, but no client's search syntax
        // exposes it; honouring it here would answer a query the web UI drops.
        assertTrue(SearchQuery.parse("in:flagged").isEmpty)
        assertTrue(SearchQuery.parse("in:nonsense").isEmpty)
    }

    @Test
    fun `a bare date is anchored at local midnight`() {
        // 2024-01-01 in Berlin is 2023-12-31T23:00Z — the day the user meant,
        // expressed in the zone the server compares against.
        assertEquals(
            Instant.parse("2023-12-31T23:00:00Z"),
            SearchQuery.parse("after:2024-01-01", clock).after,
        )
        assertEquals(
            Instant.parse("2024-12-30T23:00:00Z"),
            SearchQuery.parse("before:2024-12-31", clock).before,
        )
    }

    @Test
    fun `a date with an explicit offset is taken as written`() {
        assertEquals(
            Instant.parse("2024-01-01T08:30:00Z"),
            SearchQuery.parse("after:2024-01-01T09:30:00+01:00", clock).after,
        )
        assertEquals(
            Instant.parse("2024-01-01T09:30:00Z"),
            SearchQuery.parse("after:2024-01-01T09:30:00Z", clock).after,
        )
    }

    @Test
    fun `the orderings people actually type are accepted`() {
        val expected = Instant.parse("2024-01-04T23:00:00Z") // 2024-01-05, Berlin.

        assertEquals(expected, SearchQuery.parse("after:2024-1-5", clock).after)
        assertEquals(expected, SearchQuery.parse("after:2024/01/05", clock).after)
        assertEquals(expected, SearchQuery.parse("after:05.01.2024", clock).after)
        assertEquals(
            Instant.parse("2024-01-05T07:30:00Z"),
            SearchQuery.parse("after:\"2024-01-05 08:30\"", clock).after,
        )
    }

    @Test
    fun `today and yesterday are resolved against the clock`() {
        assertEquals(
            Instant.parse("2026-07-30T22:00:00Z"),
            SearchQuery.parse("after:today", clock).after,
        )
        assertEquals(
            Instant.parse("2026-07-29T22:00:00Z"),
            SearchQuery.parse("after:Yesterday", clock).after,
        )
    }

    @Test
    fun `an unparseable date is dropped rather than searched for`() {
        val query = SearchQuery.parse("before:whenever", clock)

        assertNull(query.before)
        assertTrue(query.isEmpty, "the token is consumed, not handed to free text")
        assertFalse(SearchQuery.parse("after:2024-13-45", clock).hasDateBound)
    }

    @Test
    fun `the last occurrence of an operator wins`() {
        assertEquals("bob", SearchQuery.parse("from:alice from:bob").from)

        // And an unusable value clears a good one, because the server assigns
        // unconditionally rather than only on success.
        assertNull(SearchQuery.parse("after:2024-01-01 after:whenever", clock).after)
        assertNull(SearchQuery.parse("in:inbox in:nonsense").mailbox)
    }

    @Test
    fun `operators and free text mix in any order`() {
        val query = SearchQuery.parse("  urgent from:alice   invoice is:unread in:inbox ", clock)

        assertEquals(
            SearchQuery(
                from = "alice",
                isUnread = true,
                mailbox = MailboxRole.INBOX,
                freeText = "urgent invoice",
            ),
            query,
        )
        assertFalse(query.hasDateBound)
    }
}
