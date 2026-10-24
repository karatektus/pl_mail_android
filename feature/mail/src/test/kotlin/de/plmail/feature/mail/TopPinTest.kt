package de.plmail.feature.mail

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the inbox goes back to its top after rows arrived above the first one (#60). */
class TopPinTest {

    @Test
    fun `rows arriving above a list at its top put it back there`() {
        val pin = TopPin()

        pin.moved(index = 0, offset = 0, isScrolling = false)

        // The keyed list kept its old first row, now third.
        assertTrue(pin.moved(index = 2, offset = 0, isScrolling = false))
    }

    @Test
    fun `a list the user scrolled down keeps its place when rows arrive`() {
        val pin = TopPin()

        pin.moved(index = 0, offset = 0, isScrolling = false)
        pin.moved(index = 3, offset = 40, isScrolling = true)
        pin.moved(index = 5, offset = 10, isScrolling = false)

        assertFalse(pin.moved(index = 7, offset = 10, isScrolling = false))
    }

    @Test
    fun `scrolling is never undone`() {
        val pin = TopPin()

        pin.moved(index = 0, offset = 0, isScrolling = false)

        assertFalse(pin.moved(index = 0, offset = 120, isScrolling = true))
        assertFalse(pin.moved(index = 1, offset = 0, isScrolling = true))
    }

    /** After a rotation the list comes back where it was, not at the top. */
    @Test
    fun `a list restored part-way down is left there`() {
        val pin = TopPin()

        assertFalse(pin.moved(index = 12, offset = 30, isScrolling = false))
        assertFalse(pin.moved(index = 14, offset = 30, isScrolling = false))
    }

    @Test
    fun `back at the top by scrolling, it is pinned again`() {
        val pin = TopPin()

        pin.moved(index = 4, offset = 0, isScrolling = false)
        pin.moved(index = 0, offset = 0, isScrolling = true)
        pin.moved(index = 0, offset = 0, isScrolling = false)

        assertTrue(pin.moved(index = 1, offset = 0, isScrolling = false))
    }
}
