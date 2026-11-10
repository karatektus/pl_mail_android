package de.plmail.feature.mail.reader

import androidx.compose.ui.unit.Constraints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A very long message must be cut, not crash the reader.
 *
 * The reader used to give the WebView its full document height with `Modifier.height`. A plain-text
 * mail a few thousand lines long is taller than [Constraints] can hold at a phone's width, and
 * opening it threw inside layout.
 */
class FittedHeightTest {

    private val phone = Constraints.fixedWidth(PHONE_WIDTH)

    /** The crash itself, so this test fails if Compose ever lifts the limit and the cut can go. */
    @Test
    fun `a fixed height past the limit cannot be represented at a phone's width`() {
        assertFailsWith<IllegalArgumentException> { Constraints.fixed(PHONE_WIDTH, LONG_MAIL) }
    }

    @Test
    fun `an ordinary message keeps its height`() {
        val fitted = phone.withHeight(5_000)

        assertEquals(5_000, fitted.minHeight)
        assertEquals(5_000, fitted.maxHeight)
        assertEquals(PHONE_WIDTH, fitted.maxWidth)
    }

    @Test
    fun `a message past the limit is cut to it, keeping the width`() {
        val fitted = phone.withHeight(LONG_MAIL)

        assertEquals(PHONE_WIDTH, fitted.minWidth)
        assertEquals(PHONE_WIDTH, fitted.maxWidth)
        assertEquals(fitted.minHeight, fitted.maxHeight)
        assertTrue(fitted.maxHeight in 100_000 until LONG_MAIL, "cut to ${fitted.maxHeight}")
    }

    private companion object {
        const val PHONE_WIDTH = 1080

        /** About 6,000 lines of plain text at 50px a line. */
        const val LONG_MAIL = 300_000
    }
}
