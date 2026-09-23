package de.plmail

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which launcher icon is on the home screen, asked about and switched.
 *
 * **An installed app cannot change its own icon.** `android:icon` is a resource reference the
 * *launcher* resolves out of our manifest, in another process, long before any of our code runs,
 * and there is no API that hands it a bitmap instead. What can be changed is which component is
 * offering a launcher entry — so there is an `<activity-alias>` in front of `MainActivity` for
 * every motif in every paint, [LauncherIcon.all], and this class turns exactly one of them on. See
 * the manifest for what an alias can and cannot override; `AppCalendarLauncherIcon` is the same
 * mechanism used for a different purpose and its docblock is the longer treatment.
 *
 * In `:app` because that is the only module that can name a component in `:app`'s manifest. Unlike
 * [CalendarLauncherIcon][de.plmail.core.data.CalendarLauncherIcon] there is no interface over it:
 * that one exists because a settings screen in a lower module has to offer the toggle, and nothing
 * outside this module ever asks about the icon. The user picks it in the browser.
 *
 * **`PackageManager` holds the answer and nothing here caches it.** A copy in DataStore would be a
 * second answer that can disagree with the launcher, silently — and the state survives reboots and
 * app upgrades where a preferences file the user cleared does not.
 */
@Singleton
class AppLauncherIcon @Inject constructor(@param:ApplicationContext private val context: Context) {

    /**
     * The icon currently on the home screen, or null when the aliases do not agree on one.
     *
     * Null is not "none" — it is "not exactly one", which covers a half-applied switch that was
     * interrupted (two enabled) as well as the state that must never happen (none enabled). Both
     * answers mean the same thing to [wear]: whatever is out there is not what was asked for, so
     * apply it properly. Reporting them as a single nullable rather than as a count is what keeps
     * the early return in [wear] honest — it fires only when there is one alias enabled and it is
     * the right one.
     */
    fun worn(): LauncherIcon? = showing().singleOrNull()

    /**
     * Puts [icon] on the home screen, and takes every other one off.
     *
     * **Idempotent, and that is the load-bearing property rather than a nicety.** This is called
     * from every appearance read — on foreground, and on every fifteen-minute sync — so the
     * overwhelmingly common case is that the icon is already right. A `setComponentEnabledSetting`
     * pair issued anyway would broadcast a package change each time, and a launcher answers that by
     * dropping the entry and re-adding it: the app's icon blinking off somebody's home screen every
     * quarter of an hour, and on some launchers landing back at the end of the drawer rather than
     * where it was put. So the first thing this does is ask what is showing, and almost always
     * there is nothing to do.
     *
     * **The new alias is enabled before the old one is disabled, and the order is not negotiable.**
     * Between the two calls there are briefly two launcher entries, which is a cosmetic flicker.
     * Reversed, there would be an instant with none at all — and an app that disappears from the
     * home screen, however briefly, is an app some launchers do not put back without a reboot.
     *
     * **Only what has to change is written.** The new icon, unless it is already showing, and then
     * each icon that is showing and should not be — which after a clean switch is one. The other
     * three hundred-odd aliases are off already, and writing DISABLED over them would be a binder
     * call and a package-change broadcast each, to say nothing.
     *
     * **`DONT_KILL_APP`**, for the reason `AppCalendarLauncherIcon` gives: the alternative is the
     * system stopping our process to apply the change, which from a running mail app is the app
     * vanishing for no reason the user can connect to anything they did.
     *
     * `MainActivity` itself is never touched. It carries no launcher filter — see the manifest —
     * precisely so that switching the icon never means disabling the component this process is
     * running in.
     *
     * The residual hazard, recorded because it is the one thing here that cannot be designed away:
     * a task launched from the home screen is rooted at the *alias* that was tapped, so disabling
     * that alias while such a task is alive leaves a Recents card pointing at a component that no
     * longer resolves, and some platform versions will finish the task instead. It costs at most
     * one Recents entry, it happens only at the moment somebody changes their icon in a browser
     * while the phone is also open, and every alternative — deferring the disable until the task is
     * gone, trampolining the launch through a second activity to keep aliases off the task root —
     * trades it for a worse failure: two icons on the home screen for as long as the card lives, or
     * a launch hop on every cold start for a switch that happens once a year.
     */
    fun wear(icon: LauncherIcon) {
        val showing = showing()
        if (showing == listOf(icon)) return

        if (icon !in showing) set(icon, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)

        showing
            .filter { it != icon }
            .forEach { set(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED) }
    }

    /**
     * The icons the system is offering a launcher entry for, found with one question.
     *
     * **The launcher's own question, asked of our package only.** A launcher decides what to draw
     * by resolving `MAIN` + `LAUNCHER`; asking the same thing, limited to this package, returns
     * exactly the aliases that are on the home screen — and nothing else, because nothing else in
     * the manifest carries that filter except the calendar's alias, which is not in [byAlias] and
     * falls out of the lookup.
     *
     * It replaces a `getComponentEnabledSetting` per alias, which was thirty-two binder calls on
     * every appearance read while there were thirty-two aliases and would be three hundred and
     * twenty-nine now: several milliseconds of IPC on every foreground to learn one fact. It also
     * reads `DEFAULT` correctly without being told how. A component nobody has overridden is in the
     * answer exactly when the manifest enables it — the default alias, on an install that has never
     * been switched — which is the mapping the per-component version had to spell out by hand, and
     * which, read as a flat "off", would have made [worn] answer null on every fresh install and
     * the first sync rewrite components to arrive where it already was. The package manager applies
     * that rule itself here, the same way it does for the launcher.
     *
     * No per-component read is needed anywhere as a fallback: a disabled alias is simply absent
     * from the answer, and an enabled one that is not an icon of ours is ignored.
     */
    private fun showing(): List<LauncherIcon> {
        val launcher =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(context.packageName)

        return entries(launcher).mapNotNull { byAlias[it.activityInfo?.name] }.distinct()
    }

    private fun entries(intent: Intent): List<ResolveInfo> {
        val packages = context.packageManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            // The int overload is the only one API 31 and 32 have, and it
            // is deprecated from 33, where the branch above takes over.
            @Suppress("DEPRECATION") packages.queryIntentActivities(intent, 0)
        }
    }

    private fun set(icon: LauncherIcon, state: Int) {
        context.packageManager.setComponentEnabledSetting(
            component(icon),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }

    /**
     * The package half from the applicationId, the class half from the namespace.
     *
     * They are different strings in three of the four variants — `de.plmail.google`, `.debug` — and
     * `ComponentName(context, "…")` would build both halves out of the applicationId, yielding a
     * component the system has never heard of. `setComponentEnabledSetting` on one of those throws.
     * See [LauncherIcon.alias].
     */
    private fun component(icon: LauncherIcon) = ComponentName(context.packageName, icon.alias)

    private companion object {
        val byAlias: Map<String, LauncherIcon> = LauncherIcon.all.associateBy { it.alias }
    }
}
