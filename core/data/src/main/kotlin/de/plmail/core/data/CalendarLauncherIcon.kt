package de.plmail.core.data

/**
 * The calendar's own launcher icon, asked about and switched.
 *
 * An interface here for the same reason `MailDestinations` is one: the component this turns on and
 * off is declared in `:app`'s manifest, and `:app` is above every module that has a settings screen
 * to offer it from. The implementation is `AppCalendarLauncherIcon`.
 *
 * **[isEnabled] asks the system every time, and there is no flow.** The enabled state of a manifest
 * component is a fact `PackageManager` owns; a copy of it in DataStore would be a second answer
 * that can disagree with the launcher, and the way it disagrees is silent. There is also nothing to
 * observe — the system publishes no change signal for a component's enabled state — so a screen
 * that shows this re-reads it when it comes back into view rather than subscribing to it.
 *
 * Both calls are synchronous and cheap: a binder round trip to the package manager, no disk of
 * ours.
 */
interface CalendarLauncherIcon {

    /** Whether the launcher is currently showing a separate calendar icon for this install. */
    fun isEnabled(): Boolean

    /**
     * Adds or removes the icon.
     *
     * Switching it off also closes the calendar's task, so the disabled state does not leave a card
     * in Recents pointing at a component that can no longer be started.
     */
    fun setEnabled(enabled: Boolean)
}
