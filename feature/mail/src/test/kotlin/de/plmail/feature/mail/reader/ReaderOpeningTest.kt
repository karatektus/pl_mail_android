package de.plmail.feature.mail.reader

import de.plmail.core.database.EmailEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where a conversation opens: which messages start expanded, and which one it starts at (#58). */
class ReaderOpeningTest {

    private fun email(uid: String, seen: Boolean) =
        EmailEntity(uid = uid, accountKey = "a", emailId = uid, threadId = "t", isSeen = seen)

    private fun shown(vararg emails: EmailEntity) = emails.map {
        ReaderMessage(email = it, html = null, text = null, isExpanded = false)
    }

    @Test
    fun `with nothing unread only the newest is expanded`() {
        val old = email("m1", seen = true)
        val newest = email("m3", seen = true)

        assertFalse(startsExpanded(old, newestUid = "m3", previously = null))
        assertTrue(startsExpanded(newest, newestUid = "m3", previously = null))
    }

    @Test
    fun `every unread message is expanded, not only the newest`() {
        assertTrue(startsExpanded(email("m2", seen = false), newestUid = "m3", previously = null))
    }

    /** The reader is rebuilt once bodies arrive, and must not undo what the user did meanwhile. */
    @Test
    fun `what the user left a message at wins over the rule`() {
        assertFalse(startsExpanded(email("m2", seen = false), newestUid = "m3", previously = false))
        assertTrue(startsExpanded(email("m1", seen = true), newestUid = "m3", previously = true))
    }

    @Test
    fun `it starts at the first unread message`() {
        val rebuilt =
            shown(email("m1", seen = true), email("m2", seen = false), email("m3", seen = false))

        assertEquals("m2", startAt(shownBefore = emptyList(), rebuilt = rebuilt, decided = null))
    }

    @Test
    fun `with nothing unread it starts at the top`() {
        val rebuilt = shown(email("m1", seen = true), email("m2", seen = true))

        assertNull(startAt(shownBefore = emptyList(), rebuilt = rebuilt, decided = null))
    }

    /** Reading marks m2 read; the next rebuild must not move the start on to m3. */
    @Test
    fun `the start is decided once and carried`() {
        val first = shown(email("m1", seen = true), email("m2", seen = false))
        val later =
            shown(email("m1", seen = true), email("m2", seen = true), email("m3", seen = false))

        assertEquals("m2", startAt(shownBefore = first, rebuilt = later, decided = "m2"))
    }

    /** Opened from a notification with nothing cached: the first rebuild has no messages. */
    @Test
    fun `an empty first rebuild leaves the decision to the next one`() {
        assertNull(startAt(shownBefore = emptyList(), rebuilt = emptyList(), decided = null))

        val downloaded = shown(email("m1", seen = true), email("m2", seen = false))
        assertEquals(
            "m2",
            startAt(shownBefore = emptyList(), rebuilt = downloaded, decided = null),
        )
    }
}
