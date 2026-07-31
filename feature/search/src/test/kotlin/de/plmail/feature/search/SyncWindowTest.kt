package de.plmail.feature.search

import de.plmail.jmap.search.SearchQuery
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When "no results" is the wrong thing to say.
 *
 * Mail older than what the server has synced is not searchable, so a dated query that reaches past
 * that boundary finds nothing whether or not the message exists. Saying "no messages match" there
 * is true and misleading. The judgement has to cut both ways though — blaming the sync window for a
 * search that genuinely found nothing is its own dishonesty, and the more annoying one, because it
 * sends the reader looking for a problem that is not there.
 */
class SyncWindowTest {

    /** The server holds nothing before this. */
    private val oldest = Instant.parse("2026-03-01T00:00:00Z")

    private val clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `an after bound before the oldest message reaches outside the window`() {
        val query = SearchQuery.parse("invoice after:2024-01-01", clock)

        assertTrue(query.reachesBefore(oldest))
    }

    @Test
    fun `an after bound inside the window does not`() {
        val query = SearchQuery.parse("invoice after:2026-05-01", clock)

        assertFalse(query.reachesBefore(oldest), "this search really did find nothing")
    }

    /**
     * `before:` the boundary is the starkest case: the window it names is entirely unsynced, so the
     * search could not have succeeded however much mail exists.
     */
    @Test
    fun `a before bound at or under the oldest message is entirely outside the window`() {
        assertTrue(SearchQuery.parse("invoice before:2025-01-01", clock).reachesBefore(oldest))

        // Inclusive at the boundary: the server's `before` is a strict <, so
        // `before:` the oldest message excludes that message too and leaves a
        // window with nothing in it.
        assertTrue(SearchQuery.parse("invoice before:2026-03-01", clock).reachesBefore(oldest))
    }

    @Test
    fun `a before bound later than the oldest message is inside it`() {
        assertFalse(SearchQuery.parse("invoice before:2026-06-01", clock).reachesBefore(oldest))
    }

    @Test
    fun `a query with no date bound never blames the window`() {
        val query = SearchQuery.parse("from:acme is:unread", clock)

        assertFalse(query.hasDateBound)
        assertFalse(query.reachesBefore(oldest))
    }
}
