package de.plmail.jmap.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Parsing `text/event-stream`.
 *
 * Line-at-a-time rather than over a whole body, because the whole point of SSE is that the body
 * does not end for 300 seconds — a parser that needs the complete response delivers five minutes of
 * "live" updates at once.
 */
class EventStreamParserTest {

    private fun feed(vararg lines: String): List<Any> {
        val parser = EventStreamParser()
        return lines.mapNotNull { parser.consume(it) }
    }

    @Test
    fun `a state event yields the changed tokens`() {
        val changes =
            feed(
                "event: state",
                """data: {"@type":"StateChange","changed":{"1":{"Email":"9","Mailbox":"3"}}}""",
                "",
            )

        assertEquals(1, changes.size)

        val change = changes.first() as de.plmail.jmap.methods.StateChange

        assertEquals(mapOf("Email" to "9", "Mailbox" to "3"), change.changed["1"])
    }

    @Test
    fun `a ping is consumed rather than emitted`() {
        // Pings exist to keep the connection alive. Emitting them would make
        // every subscriber re-sync every thirty seconds for no reason.
        assertEquals(emptyList(), feed("event: ping", "data: 2026-07-31T12:00:00Z", ""))
    }

    @Test
    fun `comments are ignored`() {
        assertEquals(emptyList(), feed(": keep-alive", ""))
    }

    @Test
    fun `an unknown event type is skipped rather than failing the stream`() {
        // A parser that throws here breaks the moment the server adds an
        // event type, which it is free to do at any time.
        assertEquals(emptyList(), feed("event: somethingNew", """data: {"whatever":1}""", ""))
    }

    @Test
    fun `malformed json does not kill the connection`() {
        assertEquals(emptyList(), feed("event: state", "data: {not json", ""))
    }

    @Test
    fun `a record is only complete at the blank line`() {
        val parser = EventStreamParser()

        assertNull(parser.consume("event: state"))
        assertNull(
            parser.consume("""data: {"@type":"StateChange","changed":{"1":{"Email":"2"}}}""")
        )

        // Nothing may be emitted until the record separator arrives, or two
        // events sharing a chunk boundary become one malformed payload.
        val emitted = parser.consume("")

        assertEquals(mapOf("Email" to "2"), emitted?.changed?.get("1"))
    }

    @Test
    fun `an event with no explicit type is treated as state`() {
        // RFC 6202 makes `event:` optional and defaults to "message"; the
        // server sends the field, but a bare data record still carries a
        // StateChange and dropping it would lose a notification.
        val changes = feed("""data: {"@type":"StateChange","changed":{"7":{"Email":"4"}}}""", "")

        assertEquals(1, changes.size)
    }
}
