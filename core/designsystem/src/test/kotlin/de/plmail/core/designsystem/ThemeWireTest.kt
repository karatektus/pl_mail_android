package de.plmail.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one resolver every appearance value passes through.
 *
 * `paper` is here because it is a *decision* rather than a fallback, and the two are
 * indistinguishable from the outside: both leave the enum unchanged, and only one of them is right.
 * Somebody who chose a light theme on the web and got `system` had a phone that went dark at sunset
 * — which reads as a broken sync rather than as a mapping nobody made.
 */
class ThemeWireTest {

    @Test
    fun `paper resolves to Light, and an unknown theme still falls back to System`() {
        assertEquals(PlMailThemeChoice.LIGHT, PlMailThemeChoice.fromWire("paper"))

        // A theme a future server adds is one this build genuinely has no
        // opinion about, and following the phone is the only honest answer.
        assertEquals(PlMailThemeChoice.SYSTEM, PlMailThemeChoice.fromWire("midnight"))
        assertEquals(PlMailThemeChoice.SYSTEM, PlMailThemeChoice.fromWire(null))

        // No entry can spell `paper` back, which is what keeps a phone from
        // writing the web's choice away: the app only ever patches the property
        // the user just touched.
        assertEquals(emptyList(), PlMailThemeChoice.entries.filter { it.wire == "paper" })
    }

    @Test
    fun `the wire values are the server's own, all six of them`() {
        // Names and order are plMail's `App\Domain\Enum\Theme\Theme`, so a value
        // maps by name and nothing is translated. A rename here is a sync that
        // silently loses a setting.
        assertEquals(
            listOf("system", "light", "dark", "nord", "dusk", "solar"),
            PlMailThemeChoice.entries.map { it.wire },
        )
        assertEquals(listOf("flat", "boxed"), PlMailLayout.entries.map { it.wire })
        assertEquals(
            listOf("comfortable", "cosy", "compact"),
            PlMailDensity.entries.map { it.wire },
        )
    }
}
