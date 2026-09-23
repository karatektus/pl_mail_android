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
 * `DEFAULT` means whatever the manifest said, and that switching from one icon to another leaves
 * exactly one launcher entry behind. It resolves the launcher query `AppLauncherIcon` asks against
 * that same table — manifest `enabled`, then any override — which is what the platform does.
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

    private val ocean = icon("pl", "ocean")
    private val ink = icon("pl", "ink")
    private val ember = icon("pl", "ember")
    private val horn = icon("blue-horn", "original")

    private fun icon(motif: String, paint: String) =
        LauncherIcon.all.single { it.motif == motif && it.paint == paint }

    private fun setting(icon: LauncherIcon) =
        context.packageManager.getComponentEnabledSetting(
            ComponentName(context.packageName, icon.alias)
        )

    /** The icons the system has been told something explicit about. */
    private fun overridden() =
        LauncherIcon.all.filter { setting(it) != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT }

    @Test
    fun `a fresh install wears the default without anything having been written`() {
        // Every alias is DEFAULT, meaning nobody has overridden the manifest --
        // and the manifest enables exactly one of them. That is what gives a
        // never-launched install a launcher icon, and it is why DEFAULT cannot
        // be read as a flat "off" the way it is for the calendar alias, whose
        // manifest state is false.
        assertTrue(overridden().isEmpty())
        assertEquals(LauncherIcon.Default, icon.worn())
    }

    @Test
    fun `wearing the icon already worn writes nothing at all`() {
        icon.wear(LauncherIcon.Default)

        // Still untouched, which is the strongest form this assertion can take:
        // a disable-and-re-enable would have left the default at ENABLED and the
        // rest at DISABLED, all of them indistinguishable from here by their
        // effect and every one of them a package-change broadcast.
        assertTrue("expected no component writes, got ${overridden()}", overridden().isEmpty())
        assertEquals(LauncherIcon.Default, icon.worn())
    }

    @Test
    fun `switching enables the new alias and disables the old one`() {
        icon.wear(ocean)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, setting(ocean))

        // DISABLED and not DEFAULT, for the reason the calendar toggle gives:
        // DEFAULT for the default icon means enabled, so returning it there
        // would leave two launcher entries showing.
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, setting(LauncherIcon.Default))

        assertEquals(ocean, icon.worn())
    }

    @Test
    fun `switching leaves every untouched icon untouched`() {
        icon.wear(ocean)

        // Only what had to change did. The other three hundred-odd aliases were
        // already off by the manifest, and writing DISABLED over them would be a
        // binder call and a broadcast each to say nothing -- on a switch that
        // happens while somebody is looking at their home screen.
        assertEquals(setOf(LauncherIcon.Default, ocean), overridden().toSet())
    }

    @Test
    fun `re-applying after a switch is still a no-op`() {
        icon.wear(ocean)
        val before = LauncherIcon.all.associateWith(::setting)

        icon.wear(ocean)

        // Nothing moved, and nothing was added: the second call did not walk the
        // other aliases writing DISABLED over a state they were already in,
        // which is what `overridden` would have grown to show.
        assertEquals(before, LauncherIcon.all.associateWith(::setting))
        assertEquals(2, overridden().size)
    }

    @Test
    fun `it can be switched again, to another motif, and back to the default`() {
        icon.wear(ocean)
        icon.wear(horn)

        assertEquals(horn, icon.worn())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, setting(ocean))

        icon.wear(ink)
        icon.wear(LauncherIcon.Default)

        // The way home. An explicit ENABLED rather than a return to DEFAULT --
        // the two mean the same thing for this one alias today and would stop
        // meaning it the moment the manifest's default moved.
        assertEquals(LauncherIcon.Default, icon.worn())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, setting(LauncherIcon.Default))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, setting(ink))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, setting(horn))
    }

    @Test
    fun `there is never an instant with no launcher entry`() {
        // The order is the assertion, and it cannot be observed from outside a
        // single call -- so what is asserted is the invariant either side of
        // every transition this app can make: one entry, always. A `wear` that
        // disabled first would still pass every other test in this file.
        LauncherIcon.all.fold(LauncherIcon.Default) { previous, next ->
            icon.wear(next)

            assertEquals("nothing on the home screen after $previous -> $next", next, icon.worn())
            next
        }
    }

    @Test
    fun `a state no switch could have produced is repaired rather than trusted`() {
        // Two aliases enabled at once: what an install is left in if the process
        // dies between the enable and the disable. `worn` answers null rather
        // than picking one, so the next read re-applies instead of returning
        // early on a home screen showing plMail twice.
        icon.wear(ocean)
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, ember.alias),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )

        assertNull(icon.worn())

        icon.wear(ocean)

        assertEquals(ocean, icon.worn())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, setting(ember))
    }

    @Test
    fun `every icon has the drawable its alias promises`() {
        // The aliases are generated and so are the drawables, from one table --
        // but they are generated into different files, and an alias naming a
        // mipmap that is not there is a manifest that merges, an APK that
        // builds, and a launcher with nothing to draw. Resolved by name against
        // the *compiled* resources, which is the only place the two meet.
        LauncherIcon.all.forEach { icon ->
            val name =
                if (icon == LauncherIcon.Default) "ic_launcher"
                else "ic_launcher_${icon.motif}_${icon.paint}".replace('-', '_')

            assertNotEquals(
                "no @mipmap/$name for ${icon.motif}/${icon.paint}",
                0,
                context.resources.getIdentifier(name, "mipmap", context.packageName),
            )
        }
    }
}
