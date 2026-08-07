package de.plmail

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.plmail.core.data.CalendarLauncherIcon
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `CalendarLauncher` alias, switched through `PackageManager`.
 *
 * This lives in `:app` because it is the only module that can name a component in `:app`'s
 * manifest. See that manifest for why the alias exists and why the task it opens is a separate one.
 */
@Singleton
class AppCalendarLauncherIcon
@Inject
constructor(@param:ApplicationContext private val context: Context) : CalendarLauncherIcon {

    private val alias = ComponentName(context.packageName, ALIAS)

    /**
     * The system's answer, not ours.
     *
     * `DEFAULT` means nobody has overridden the manifest, and the manifest says `enabled="false"`,
     * so it folds into "off" along with an explicit `DISABLED`. Only an explicit `ENABLED` is on.
     * Reading it this way is what lets the manifest keep the default in one place rather than
     * having it restated here as a constant that could drift from it.
     */
    override fun isEnabled(): Boolean =
        context.packageManager.getComponentEnabledSetting(alias) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /**
     * Adds or removes the icon, and tidies Recents on the way out.
     *
     * `DONT_KILL_APP` because the alternative is the system stopping our process to apply a
     * component change — which, from a settings screen, is the app vanishing under the finger that
     * just flipped a switch. Nothing in this process is holding state that depends on the alias, so
     * there is nothing that has to be restarted for the change to be true.
     *
     * The task is closed **before** the component is disabled, so the removal runs while the
     * component the task is rooted at still resolves. See [closeCalendarTask].
     */
    override fun setEnabled(enabled: Boolean) {
        if (!enabled) closeCalendarTask()

        context.packageManager.setComponentEnabledSetting(
            alias,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /**
     * Finishes the calendar's task and takes its card out of Recents.
     *
     * **What Android does on its own is not enough to rely on.** Disabling a component broadcasts a
     * package change, and the activity manager does clean up activities of disabled components when
     * it sees one; what it does with a *finished* task still sitting in Recents is less certain,
     * and it is launcher and OEM specific besides. The failure mode if nothing does it is a dead
     * card: a calendar in the recents list that relaunches into a component that no longer
     * resolves, which on most builds is a flash of nothing and on some is a toast about the app not
     * being installed.
     *
     * `getAppTasks` returns only this app's own tasks and needs no permission, and
     * `finishAndRemoveTask` is the documented way to do both halves at once. Wrapped in a
     * `runCatching` because the task can go away between being listed and being finished, and a
     * settings toggle must not crash over a race with the window manager.
     *
     * The root is matched against both names on purpose. A task started through an alias records
     * the alias in its base intent, while the activity that was actually instantiated is the target
     * — so which of the two names the task depends on a detail of the platform's own bookkeeping,
     * and accepting either costs one comparison.
     */
    private fun closeCalendarTask() {
        val activities = context.getSystemService(ActivityManager::class.java) ?: return

        activities.appTasks
            .filter { task ->
                // Nullable, and it means the task went away between being listed
                // and being asked about. Nothing to close, and nothing worth
                // guessing about: a task that cannot say what it is rooted at is
                // not one to finish on suspicion.
                val info = task.taskInfo ?: return@filter false

                isCalendarTask(info.baseIntent.component) || isCalendarTask(info.baseActivity)
            }
            .forEach { task -> runCatching { task.finishAndRemoveTask() } }
    }

    internal companion object {

        /**
         * The alias, spelled out rather than derived.
         *
         * `android:name=".CalendarLauncher"` in the manifest resolves against the module's
         * **namespace**, `de.plmail`, and not against the applicationId — which carries a flavour
         * suffix (`.google`) and a build-type one (`.debug`) and is therefore the wrong thing to
         * build a class name from. The package name at runtime is right for the first half of the
         * component and wrong for the second, which is exactly the mistake this constant exists to
         * make impossible. `CalendarLauncherManifestTest` asserts this string against the merged
         * manifest, so the two cannot drift apart unnoticed.
         */
        const val ALIAS = "de.plmail.CalendarLauncher"

        /** Whether a task rooted at [root] is the calendar's, by either of its two names. */
        fun isCalendarTask(root: ComponentName?): Boolean =
            root != null &&
                (root.className == ALIAS || root.className == CalendarActivity::class.java.name)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CalendarLauncherModule {

    @Binds @Singleton abstract fun icon(real: AppCalendarLauncherIcon): CalendarLauncherIcon
}
