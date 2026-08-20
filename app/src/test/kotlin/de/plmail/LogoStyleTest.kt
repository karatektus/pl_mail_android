package de.plmail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The wire vocabulary, which is the whole of what this app decides about a logo colourway.
 *
 * Everything else in the feature is a resource or a manifest attribute, checked elsewhere. What is
 * checked here is the one function with a policy in it: what a wire name resolves to, and — far
 * more importantly — what a name it does not recognise resolves to.
 *
 * **The unknown case is not an edge case, it is the expected case.** `logoStyle` is a property the
 * server grew after this app shipped its icons, and the server will grow colourways after this
 * build ships too: `LogoStyle` on the web is an enum somebody adds to. An app that answered null
 * for one of those would be an app one deploy away from having no launcher icon at all, which is
 * not a failure the user can undo from inside it.
 *
 * Plain JUnit 5, no Android: this is a `when` over strings and needs nothing.
 */
class LogoStyleTest {

    @Test
    fun `a wire name resolves to its colourway`() {
        assertSame(LogoStyle.BERRY, LogoStyle.fromWire("berry"))
        assertSame(LogoStyle.PRODUCT_BLUE, LogoStyle.fromWire("product-blue"))
        assertSame(LogoStyle.PETROL_COPPER, LogoStyle.fromWire("petrol-copper"))
        assertSame(LogoStyle.AURORA, LogoStyle.fromWire("aurora"))
        assertSame(LogoStyle.INK, LogoStyle.fromWire("ink"))
    }

    @Test
    fun `every colourway the server publishes can be spelled back to itself`() {
        // The round trip rather than a list of thirty-two literals: this suite
        // does not get to be the second transcription of the table, or it would
        // be the thing that goes stale when the table gains an entry.
        LogoStyle.entries.forEach { style ->
            assertSame(style, LogoStyle.fromWire(style.wire), "${style.wire} did not round-trip")
        }
    }

    @Test
    fun `a colourway this build has never heard of is the default, not a null`() {
        // A server newer than this build. The name is well-formed and entirely
        // reasonable; it simply shipped after these icons did, and there is no
        // drawable in this APK for it.
        assertSame(LogoStyle.Default, LogoStyle.fromWire("seafoam-brass"))

        // And the shapes of "wrong" that are not a future colourway at all.
        assertSame(LogoStyle.Default, LogoStyle.fromWire(""))
        assertSame(LogoStyle.Default, LogoStyle.fromWire("BERRY"))
        assertSame(LogoStyle.Default, LogoStyle.fromWire("product_blue"))
    }

    @Test
    fun `a server too old to publish the property leaves the default in place`() {
        // Null is what an older server arrives as: `logoStyle` is simply absent
        // from the Appearance object, decodes to null, and reaches here. It is
        // the same answer as an unknown name on purpose -- see fromWire -- and
        // it is the case that every install in the field is in today.
        assertSame(LogoStyle.Default, LogoStyle.fromWire(null))
    }

    @Test
    fun `the default is the colourway the manifest and the fallback drawable agree on`() {
        // Berry three times over: the server's own default, the ink in
        // ic_launcher_foreground, and the one alias the manifest enables. The
        // manifest half is asserted in LogoStyleManifestTest against the merged
        // file; this is the Kotlin half of the same claim.
        assertEquals("berry", LogoStyle.Default.wire)
    }

    @Test
    fun `the table has thirty-two distinct colourways and no two share an alias`() {
        assertEquals(32, LogoStyle.entries.size)

        // A duplicate wire name would make fromWire answer whichever came first
        // and quietly strand the other. A duplicate alias would be two
        // colourways switching one component, so wearing either would show the
        // wrong icon for one of them -- and neither is visible in a diff of a
        // generated file.
        assertEquals(32, LogoStyle.entries.map { it.wire }.toSet().size)
        assertEquals(32, LogoStyle.entries.map { it.alias }.toSet().size)
    }

    @Test
    fun `every alias is a class name under this module's namespace`() {
        // Not the applicationId, which carries a flavour suffix and a build-type
        // one: android:name in the manifest resolves against the namespace, and
        // AppLauncherIcon pairs this half with context.packageName for the
        // other. See LogoStyle's docblock for what getting that backwards costs.
        LogoStyle.entries.forEach { style ->
            assertTrue(
                style.alias.startsWith("de.plmail.LogoLauncher"),
                "${style.wire} has alias ${style.alias}",
            )
        }
    }
}
