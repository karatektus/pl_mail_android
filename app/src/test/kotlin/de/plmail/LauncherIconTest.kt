package de.plmail

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The icon switch, against the thing that actually holds the answer.
 *
 * `PackageManager` owns the enabled state of a manifest component, so a fake in front of it would
 * be a test of the fake. Robolectric keeps a real component-enabled table on top of the merged
 * manifest, which is what makes the two claims this feature rests on testable at all: that
 * `DEFAULT` means whatever the manifest said, and that switching from one colourway to another
 * leaves exactly one launcher entry behind.
 *
 * **The idempotence test is the one that matters most**, and it is worth saying why. This runs on
 * every appearance read — every foreground, every fifteen-minute sync — and on almost every one of
 * them the icon is already right. An implementation that wrote the components anyway would
 * broadcast a package change each time, and a launcher answers that by dropping the app's entry and
 * re-adding it: an icon that blinks off the home screen four times an hour and can land back at the
 * end of the drawer. Nothing about that is visible from any screen in the app, and no build would
 * ever fail over it.
 *
 * JUnit 4, because Robolectric is; the vintage engine in `app/build.gradle.kts` gets it run beside
 * this module's Jupiter suites.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 and a plain Application, both for the reasons CalendarLauncherIconTest
// spells out: compileSdk is 37 and Robolectric has no image for it, and the real
// PlMailApplication would build the whole Hilt graph -- including a Keystore
// cipher a JVM has no AndroidKeyStore for -- before the first assertion.
@Config(sdk = [36], application = Application::class)
class LauncherIconTest {

    private val context = RuntimeEnvironment.getApplication()

    private val icon = AppLauncherIcon(context)

    private fun setting(style: LogoStyle) =
        context.packageManager.getComponentEnabledSetting(
            ComponentName(context.packageName, style.alias)
        )

    /** The colourways the system has been told something explicit about. */
    private fun overridden() =
        LogoStyle.entries.filter { setting(it) != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT }

    @Test
    fun `a fresh install wears the default without anything having been written`() {
        // Every alias is DEFAULT, meaning nobody has overridden the manifest --
        // and the manifest enables exactly one of them. That is what gives a
        // never-launched install a launcher icon, and it is why DEFAULT cannot
        // be read as a flat "off" the way it is for the calendar alias, whose
        // manifest state is false.
        assertTrue(overridden().isEmpty())
        assertEquals(LogoStyle.Default, icon.worn())
    }

    @Test
    fun `wearing the colourway already worn writes nothing at all`() {
        icon.wear(LogoStyle.Default)

        // Still untouched, which is the strongest form this assertion can take:
        // a disable-and-re-enable would have left the default at ENABLED and the
        // rest at DISABLED, all of them indistinguishable from here by their
        // effect and every one of them a package-change broadcast.
        assertTrue("expected no component writes, got ${overridden()}", overridden().isEmpty())
        assertEquals(LogoStyle.Default, icon.worn())
    }

    @Test
    fun `switching enables the new alias and disables the old one`() {
        icon.wear(LogoStyle.OCEAN)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, setting(LogoStyle.OCEAN))

        // DISABLED and not DEFAULT, for the reason the calendar toggle gives:
        // DEFAULT for the default colourway means enabled, so returning it there
        // would leave two launcher entries showing.
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, setting(LogoStyle.Default))

        assertEquals(LogoStyle.OCEAN, icon.worn())
    }

    @Test
    fun `switching leaves the thirty untouched colourways untouched`() {
        icon.wear(LogoStyle.OCEAN)

        // Only what had to change did. The other thirty aliases were already off
        // by the manifest, and writing DISABLED over them would be thirty binder
        // calls and thirty broadcasts to say nothing -- on a switch that happens
        // while somebody is looking at their home screen.
        assertEquals(listOf(LogoStyle.Default, LogoStyle.OCEAN).sorted(), overridden().sorted())
    }

    @Test
    fun `re-applying after a switch is still a no-op`() {
        icon.wear(LogoStyle.OCEAN)
        val before = LogoStyle.entries.associateWith(::setting)

        icon.wear(LogoStyle.OCEAN)

        // Nothing moved, and nothing was added: the second call did not walk the
        // other thirty aliases writing DISABLED over a state they were already
        // in, which is what `overridden` would have grown to show.
        assertEquals(before, LogoStyle.entries.associateWith(::setting))
        assertEquals(2, overridden().size)
    }

    @Test
    fun `it can be switched again, and back to the default`() {
        icon.wear(LogoStyle.OCEAN)
        icon.wear(LogoStyle.INK)

        assertEquals(LogoStyle.INK, icon.worn())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, setting(LogoStyle.OCEAN))

        icon.wear(LogoStyle.Default)

        // The way home. An explicit ENABLED rather than a return to DEFAULT --
        // the two mean the same thing for this one alias today and would stop
        // meaning it the moment the manifest's default moved.
        assertEquals(LogoStyle.Default, icon.worn())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, setting(LogoStyle.Default))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, setting(LogoStyle.INK))
    }

    @Test
    fun `there is never an instant with no launcher entry`() {
        // The order is the assertion, and it cannot be observed from outside a
        // single call -- so what is asserted is the invariant either side of
        // every transition this app can make: one entry, always. A `wear` that
        // disabled first would still pass every other test in this file.
        LogoStyle.entries.fold(LogoStyle.Default) { previous, style ->
            icon.wear(style)

            assertEquals("nothing on the home screen after $previous -> $style", style, icon.worn())
            style
        }
    }

    @Test
    fun `a state no switch could have produced is repaired rather than trusted`() {
        // Two aliases enabled at once: what an install is left in if the process
        // dies between the enable and the disable. `worn` answers null rather
        // than picking one, so the next read re-applies instead of returning
        // early on a home screen showing plMail twice.
        icon.wear(LogoStyle.OCEAN)
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, LogoStyle.EMBER.alias),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )

        assertNull(icon.worn())

        icon.wear(LogoStyle.OCEAN)

        assertEquals(LogoStyle.OCEAN, icon.worn())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, setting(LogoStyle.EMBER))
    }

    @Test
    fun `every colourway has the icon its alias promises`() {
        // The aliases are generated and so are the drawables, from one table --
        // but they are generated into different files, and an alias naming a
        // mipmap that is not there is a manifest that merges, an APK that
        // builds, and a launcher with nothing to draw. Resolved by name against
        // the *compiled* resources, which is the only place the two meet.
        LogoStyle.entries.forEach { style ->
            val name =
                if (style == LogoStyle.Default) "ic_launcher"
                else "ic_launcher_${style.wire.replace('-', '_')}"

            assertNotEquals(
                "no @mipmap/$name for ${style.wire}",
                0,
                context.resources.getIdentifier(name, "mipmap", context.packageName),
            )
        }
    }
}
