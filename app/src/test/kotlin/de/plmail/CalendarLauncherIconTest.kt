package de.plmail

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The switch, against the thing that actually holds the answer.
 *
 * `PackageManager` owning this state is the whole design, so a test with a fake in front of it
 * would be a test of the fake. Robolectric's package manager keeps a real component-enabled table
 * on top of the merged manifest, which makes the one statement worth pinning testable: **`DEFAULT`
 * means the manifest's `enabled="false"`**, so an install nobody has touched reads as off. That
 * mapping is the reason there is no stored copy of the flag anywhere in the app, and it is one line
 * of `when` away from a settings screen that shows a switch on for an icon nobody has.
 *
 * JUnit 4, because Robolectric is. The vintage engine in `app/build.gradle.kts` is what gets it run
 * beside this module's Jupiter suites.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 explicitly, matching the app's targetSdk and the screenshot suites:
// compileSdk is 37 and Robolectric has no Android to emulate for it.
//
// A plain Application, and that part is load-bearing rather than tidiness.
// Robolectric otherwise instantiates the real PlMailApplication, which is
// @HiltAndroidApp, which builds the whole singleton graph before the first
// assertion -- and the first thing in that graph is KeystoreSecretCipher asking
// for AndroidKeyStore, which a JVM does not have. The class under test needs a
// Context and nothing else, so it gets one.
@Config(sdk = [36], application = Application::class)
class CalendarLauncherIconTest {

    private val context = RuntimeEnvironment.getApplication()

    private val icon = AppCalendarLauncherIcon(context)

    private val alias
        get() = ComponentName(context.packageName, AppCalendarLauncherIcon.ALIAS)

    private val setting
        get() = context.packageManager.getComponentEnabledSetting(alias)

    @Test
    fun `a fresh install has no calendar icon`() {
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, setting)

        // DEFAULT and off are the same answer here, and this is the assertion
        // that says so. Reading DEFAULT as "on" is the obvious mistake, because
        // for a component with an intent filter and no android:enabled it would
        // be right.
        assertFalse(icon.isEnabled())
    }

    @Test
    fun `switching it on enables the alias for the system`() {
        icon.setEnabled(true)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, setting)
        assertTrue(icon.isEnabled())
    }

    @Test
    fun `switching it off disables it explicitly rather than returning it to default`() {
        icon.setEnabled(true)
        icon.setEnabled(false)

        // DISABLED and not DEFAULT. They look alike from isEnabled's side, and
        // they are not: DEFAULT would be the right answer only for as long as
        // the manifest keeps enabled="false", so writing it would make an
        // unrelated manifest edit switch icons on for people who had turned
        // them off.
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, setting)
        assertFalse(icon.isEnabled())
    }

    @Test
    fun `it can be switched twice`() {
        icon.setEnabled(true)
        icon.setEnabled(false)
        icon.setEnabled(true)

        assertTrue(icon.isEnabled())
    }

    @Test
    fun `the alias is named under this build's application id`() {
        // The class half of the component comes from the namespace and the
        // package half from the applicationId, and they are different strings in
        // three of the four variants. Getting this the obvious way round --
        // ComponentName(context, "...CalendarLauncher") -- yields a component
        // the system has never heard of, and setComponentEnabledSetting on one
        // of those throws.
        assertEquals(context.packageName, alias.packageName)
        assertEquals("de.plmail.CalendarLauncher", alias.className)
    }

    @Test
    fun `a calendar task is recognised by either of the two names it can carry`() {
        val pkg = context.packageName

        // The base intent of a task started through an alias records the alias;
        // the activity that was instantiated is the target. Which of the two a
        // task reports is the platform's bookkeeping rather than ours, so the
        // Recents cleanup accepts both.
        assertTrue(
            AppCalendarLauncherIcon.isCalendarTask(
                ComponentName(pkg, AppCalendarLauncherIcon.ALIAS)
            )
        )
        assertTrue(
            AppCalendarLauncherIcon.isCalendarTask(
                ComponentName(context, CalendarActivity::class.java)
            )
        )

        // And the mail's task is not the calendar's. Without this the toggle
        // would close the mailbox on its way to removing an icon.
        assertFalse(
            AppCalendarLauncherIcon.isCalendarTask(ComponentName(context, MainActivity::class.java))
        )
        assertFalse(AppCalendarLauncherIcon.isCalendarTask(null))
    }
}
