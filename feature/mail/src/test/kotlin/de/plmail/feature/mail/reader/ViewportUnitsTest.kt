package de.plmail.feature.mail.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A message may not size itself by the height of the view that is sized by the message.
 *
 * The markup these cases use is the reproduction from the web's own fix: a wrapper one viewport
 * tall with a footer under it, which is what made a message grow without end. A WebView is the one
 * thing Robolectric cannot lay out, so what is pinned here is the rewrite — which declarations go,
 * and that nothing else does.
 */
class ViewportUnitsTest {

    @Test
    fun `a wrapper a viewport tall loses that height and keeps the rest of its style`() {
        val html = """<div style="color:red; min-height: 100vh; padding:4px"><p>hi</p></div>"""

        assertEquals(
            """<div style="color:red; padding:4px"><p>hi</p></div>""",
            ViewportUnits.drop(html),
        )
    }

    /** The small, large and dynamic forms are the same viewport under newer names. */
    @Test
    fun `every spelling of a viewport height is dropped`() {
        listOf("100vh", "50.5VH", "100dvh", "100svh", "100lvh", "80vb", "10vmin", "10vmax")
            .forEach { unit ->
                val fitted = ViewportUnits.drop("""<td style='height:$unit'>x</td>""")

                assertFalse(unit in fitted, "$unit survived: $fitted")
            }
    }

    @Test
    fun `a height inside calc is still a viewport height`() {
        val fitted = ViewportUnits.drop("""<div style="height:calc(100vh - 40px);margin:0">""")

        assertEquals("""<div style="margin:0">""", fitted)
    }

    /** The width is the pane's and does not follow the content, so `vw` is no part of the loop. */
    @Test
    fun `a viewport width is left alone`() {
        val html = """<div style="width:100vw;max-width:50vw">x</div>"""

        assertEquals(html, ViewportUnits.drop(html))
    }

    @Test
    fun `a sender's stylesheet loses the declaration and keeps the rule`() {
        val html =
            "<style>.hero { min-height: 100vh; color: #111; } p { margin: 0 }</style><p>x</p>"
        val fitted = ViewportUnits.drop(html)

        assertFalse("100vh" in fitted)
        assertTrue(".hero {" in fitted && "color: #111;" in fitted && "p { margin: 0 }" in fitted)
    }

    /** A paragraph that says "100vh" is text, and a message about CSS must still be readable. */
    @Test
    fun `text that mentions a viewport unit is not markup`() {
        val html =
            """<p>Set min-height: 100vh; on the wrapper.</p><div style="height:100vh">x</div>"""

        assertEquals(
            """<p>Set min-height: 100vh; on the wrapper.</p><div style="">x</div>""",
            ViewportUnits.drop(html),
        )
    }

    @Test
    fun `a message with no viewport units comes back untouched`() {
        val html = """<table style="width:600px"><tr><td style="height:40px">x</td></tr></table>"""

        assertEquals(html, ViewportUnits.drop(html))
    }

    /** The document the reader draws is built from the fitted body. */
    @Test
    fun `the wrapped document carries no viewport height from the sender`() {
        val document =
            MessageDocument.wrap(
                """<div style="min-height:100vh"><p>hi</p></div><p>footer</p>""",
                MessageRenderStyle.ORIGINAL,
                MessagePalette(
                    paper = "#101112",
                    ink = "#131415",
                    inkMuted = "#161718",
                    link = "#191A1B",
                    line = "#1C1D1E",
                    isDark = false,
                ),
                RemoteImages.ALLOWED,
            )

        assertFalse("100vh" in document.substringAfter("<body>"))
    }
}
