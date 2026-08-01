package de.plmail.core.data

import de.plmail.core.database.ThreadEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which of a conversation's labels reach its row.
 *
 * Every rule here removes something, and every one of them exists because the chip it removes would
 * appear on nearly every row while saying nothing. That is the failure mode worth testing: not a
 * crash, but a list where each row carries two identical grey chips and the snippet has been
 * squeezed out to make room for them.
 */
class RowLabelsTest {

    private val work = label(key = "10", name = "Work")
    private val invoices = label(key = "11", name = "Invoices", path = "Work/Invoices")
    private val holiday = label(key = "12", name = "Holiday")
    private val germany = label(key = "13", name = "Deutschland")
    private val inbox = label(key = "1", name = "Inbox", role = "inbox")
    private val archive = label(key = "6", name = "Archive", role = "archive")

    private val all = listOf(inbox, archive, work, invoices, holiday, germany)

    @Test
    fun `a conversation shows the labels it carries`() {
        val row = thread("10,12").rowLabels(all, viewing = null)

        assertEquals(listOf("Work", "Holiday"), row.names)
        assertEquals(0, row.hidden)
    }

    /**
     * The list's own label is the one chip nobody needs.
     *
     * Every conversation in the Work list is in Work; a column of "Work" chips down a filtered list
     * takes the space the other labels would have used and tells the reader what the app bar
     * already says.
     */
    @Test
    fun `the label being looked at is not chipped onto its own rows`() {
        val row = thread("10,12").rowLabels(all, viewing = work)

        assertEquals(listOf("Holiday"), row.names)
    }

    /**
     * By role, never by name.
     *
     * A label the user made and called "Archive" is theirs, and matching on the string would drop
     * it silently off every row it is on — the same failure `Labels.kt` exists to prevent one level
     * down, where matching names merges two people's mailboxes.
     */
    @Test
    fun `system roles are dropped and a user label that shares their name is not`() {
        val theirOwnArchive = label(key = "77", name = "Archive")
        val labels = all + theirOwnArchive

        val row = thread("1,6,77").rowLabels(labels, viewing = null)

        assertEquals(listOf("Archive"), row.names)
        assertEquals("77", labels.single { it.name == "Archive" && !it.isSystem }.key)
    }

    /**
     * Past the cap, the rest are counted rather than dropped.
     *
     * "+2" is a small thing to draw and the whole difference between a row that has run out of
     * space and a row that is simply wrong about what it carries.
     *
     * The count includes the label whose slot the counter took, which is the part worth pinning:
     * four labels with a cap of two draw one name and "+3", not one name and "+2". A counter that
     * excluded itself would understate every overflowing row by exactly one.
     */
    @Test
    fun `beyond the cap the remainder is counted, including the name the counter displaced`() {
        val row = thread("10,11,12,13").rowLabels(all, viewing = null, limit = 2)

        assertEquals(listOf("Work"), row.names)
        assertEquals(3, row.hidden)
    }

    /**
     * Exactly at the cap, and the boundary the counter must not appear at.
     *
     * Two labels with a cap of two are both drawn: there is nothing hidden, so a "+0" chip would be
     * a chip that says nothing while taking the space a name was using. The off-by-one here is the
     * kind that survives review — `take(limit - 1)` applied unconditionally would silently turn
     * every second label into a counter.
     */
    @Test
    fun `at the cap every label is named and nothing is counted`() {
        val row = thread("10,12").rowLabels(all, viewing = null, limit = 2)

        assertEquals(listOf("Work", "Holiday"), row.names)
        assertEquals(0, row.hidden)
    }

    /**
     * Order comes from the label list, which is the sidebar's order, not from the stored keys.
     *
     * The keys are stored sorted so an unchanged conversation is not rewritten on every sync, and
     * that sort is over ids — so trusting it would order chips by whichever mailbox binding the
     * server happened to number first, and the same two labels would appear in a different order on
     * two different rows.
     */
    @Test
    fun `chips are ordered like the sidebar rather than like the stored keys`() {
        val row = thread("13,10").rowLabels(all, viewing = null)

        assertEquals(listOf("Work", "Deutschland"), row.names)
    }

    /**
     * A key with no label is a binding this device has not synced, or one the user unsubscribed
     * from. Drawing a chip for it would name a label they cannot find anywhere else in the app.
     */
    @Test
    fun `a key the sidebar does not know is not drawn`() {
        val row = thread("10,99").rowLabels(all, viewing = null)

        assertEquals(listOf("Work"), row.names)
        assertEquals(0, row.hidden)
    }

    @Test
    fun `a conversation with no labels asks for nothing`() {
        assertTrue(thread("").rowLabels(all, viewing = null).isEmpty)
        assertTrue(thread("1").rowLabels(all, viewing = null).isEmpty)
    }

    private fun thread(labelKeys: String) =
        ThreadEntity(
            uid = "https://nas.local/1#5",
            accountKey = "https://nas.local/1",
            threadId = "5",
            labelKeys = labelKeys,
        )

    private fun label(key: String, name: String, path: String = name, role: String? = null) =
        Label(
            key = key,
            name = name,
            path = path,
            role = role,
            unreadThreads = 0,
            totalThreads = 0,
            mayRename = role == null,
            mayDelete = role == null,
            bindings = listOf(LabelBinding("https://nas.local/1", key)),
        )
}
