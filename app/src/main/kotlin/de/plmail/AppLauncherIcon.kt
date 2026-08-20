package de.plmail

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which logo colourway the launcher icon is drawn in, asked about and switched.
 *
 * **An installed app cannot change its own icon.** `android:icon` is a resource reference the
 * *launcher* resolves out of our manifest, in another process, long before any of our code runs,
 * and there is no API that hands it a bitmap instead. What can be changed is which component is
 * offering a launcher entry — so there are thirty-two `<activity-alias>` declarations in front of
 * `MainActivity`, one per colourway, and this class turns exactly one of them on. See the manifest
 * for what an alias can and cannot override; `AppCalendarLauncherIcon` is the same mechanism used
 * for a different purpose and its docblock is the longer treatment.
 *
 * In `:app` because that is the only module that can name a component in `:app`'s manifest. Unlike
 * [CalendarLauncherIcon][de.plmail.core.data.CalendarLauncherIcon] there is no interface over it:
 * that one exists because a settings screen in a lower module has to offer the toggle, and nothing
 * outside this module ever asks about the colourway. The user picks it in the browser.
 *
 * **`PackageManager` holds the answer and nothing here caches it.** A copy in DataStore would be a
 * second answer that can disagree with the launcher, silently — and the state survives reboots and
 * app upgrades where a preferences file the user cleared does not.
 */
@Singleton
class AppLauncherIcon @Inject constructor(@param:ApplicationContext private val context: Context) {

    /**
     * The colourway currently on the home screen, or null when the aliases do not agree on one.
     *
     * Null is not "none" — it is "not exactly one", which covers a half-applied switch that was
     * interrupted (two enabled) as well as the state that must never happen (none enabled). Both
     * answers mean the same thing to [wear]: whatever is out there is not what was asked for, so
     * apply it properly. Reporting them as a single nullable rather than as a count is what keeps
     * the early return in [wear] honest — it fires only when there is one alias enabled and it is
     * the right one.
     */
    fun worn(): LogoStyle? = LogoStyle.entries.singleOrNull(::isEnabled)

    /**
     * Puts [style]'s icon on the home screen, and takes every other one off.
     *
     * **Idempotent, and that is the load-bearing property rather than a nicety.** This is called
     * from every appearance read — on foreground, and on every fifteen-minute sync — so the
     * overwhelmingly common case is that the icon is already right. A `setComponentEnabledSetting`
     * pair issued anyway would broadcast a package change each time, and a launcher answers that by
     * dropping the entry and re-adding it: the app's icon blinking off somebody's home screen every
     * quarter of an hour, and on some launchers landing back at the end of the drawer rather than
     * where it was put. So the first thing this does is ask whether there is anything to do, and
     * almost always there is not.
     *
     * **The new alias is enabled before the old one is disabled, and the order is not negotiable.**
     * Between the two calls there are briefly two launcher entries, which is a cosmetic flicker.
     * Reversed, there would be an instant with none at all — and an app that disappears from the
     * home screen, however briefly, is an app some launchers do not put back without a reboot.
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
     * one Recents entry, it happens only at the moment somebody changes their colourway in a
     * browser while the phone is also open, and every alternative — deferring the disable until the
     * task is gone, trampolining the launch through a second activity to keep aliases off the task
     * root — trades it for a worse failure: two icons on the home screen for as long as the card
     * lives, or a launch hop on every cold start for a switch that happens once a year.
     */
    fun wear(style: LogoStyle) {
        if (worn() == style) return

        set(style, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)

        // Only the ones actually showing, which after the line above is at most
        // one. Writing DISABLED over thirty-one aliases that are already off
        // would be thirty-one binder calls and thirty-one package-change
        // broadcasts to say nothing.
        LogoStyle.entries
            .filter { it != style && isEnabled(it) }
            .forEach { set(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED) }
    }

    /**
     * Whether the system is currently offering [style]'s launcher entry.
     *
     * `DEFAULT` means nobody has overridden the manifest, so it has to be read *as* the manifest —
     * which enables the default colourway's alias and disables the other thirty-one. That mapping
     * is the reason a fresh install has an icon before this class has ever run, and reading
     * `DEFAULT` as a flat "off" would make [worn] answer null on every install nobody had touched,
     * so the first sync would rewrite thirty-two components to arrive where it already was.
     */
    private fun isEnabled(style: LogoStyle): Boolean =
        when (context.packageManager.getComponentEnabledSetting(component(style))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> style == LogoStyle.Default
            else -> false
        }

    private fun set(style: LogoStyle, state: Int) {
        context.packageManager.setComponentEnabledSetting(
            component(style),
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
     * See [LogoStyle.alias].
     */
    private fun component(style: LogoStyle) = ComponentName(context.packageName, style.alias)
}
