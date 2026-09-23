package de.plmail

import de.plmail.core.data.AppearanceRepository
import de.plmail.core.data.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the launcher icon on whatever the account says, for as long as this device is following the
 * account at all.
 *
 * The icon is picked in the browser, as a motif and a paint, and published by the JMAP `Appearance`
 * singleton as the read-only `logoMotif` and `logoPaint` — beside `logoStyle`, the pl mark's
 * colourway, which is all a server older than the motifs sends. `AppearanceRepository` reads them
 * on foreground and on every sync — there is no `Appearance/changes` and no push for this object,
 * so those are the only two moments a change can be noticed — and they land in DataStore. This is
 * what spends them; [LauncherIcon.resolve] decides what the three mean together.
 *
 * **A flow rather than a call at the end of `refresh`.** The values reach this class the same way
 * the theme reaches the app: by being on disk and observed. That means an icon read by the
 * fifteen-minute worker is applied without the worker knowing anything about launcher icons, and it
 * means the one place that decides what the icon should be is not also a place that has to be
 * called from every path that might have changed it.
 *
 * **[AppearanceSettings.syncWithServer][de.plmail.core.data.AppearanceSettings.syncWithServer]
 * gates it, and off means "keep what you have" rather than "go back to the default".** A phone that
 * has been taken off the account's appearance keeps the icon it was wearing when the switch was
 * thrown: the alias stays enabled, nothing is written, and the home screen does not change under
 * somebody who has just said they want this device left alone. That is why the flag is part of the
 * key below rather than filtered out of the flow — turning the switch back *on* has to re-apply,
 * and after `setSyncWithServer(true)` the icon itself has very often not changed at all, so a flow
 * keyed on the three wire values alone would emit nothing and the icon would stay wrong.
 *
 * Nothing here writes to the server, and there is nothing it could write to: all three values are
 * read-only. See `AppearanceRepository` for why that is the rule for every appearance value and not
 * just these.
 */
@Singleton
class LauncherIconSync
@Inject
constructor(
    private val icon: AppLauncherIcon,
    private val appearances: AppearanceRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private var following: Job? = null

    /**
     * Starts following. Called once, from the application object.
     *
     * Guarded rather than cancel-and-restart, for the reason `LiveUpdates.start` is: a second
     * caller must be free, and re-subscribing would re-apply an icon that is already right.
     *
     * There is no `stop`. This outlives every activity on purpose — the icon is a fact about the
     * install rather than about anything on screen, and an icon read by a background sync has to be
     * applied whether or not anybody is looking.
     */
    fun start() {
        if (following?.isActive == true) return

        following = scope.launch {
            appearances.settings
                .map { settings ->
                    Following(
                        settings.syncWithServer,
                        settings.logoMotif,
                        settings.logoPaint,
                        settings.logoStyle,
                    )
                }
                // The repository's own flow is distinct on the whole record,
                // so it emits when any of twenty appearance values moves.
                // Narrowing to the four this class acts on is what keeps a
                // font-size change off the package manager.
                .distinctUntilChanged()
                .collect { state ->
                    if (!state.server) return@collect

                    // Absent and unknown both land on a real icon here -- the
                    // default, on every install that has never been told
                    // otherwise -- and `wear` is a no-op when that is already
                    // what is on the home screen. See LauncherIcon.resolve.
                    icon.wear(LauncherIcon.resolve(state.motif, state.paint, state.style))
                }
        }
    }
}

/** What [LauncherIconSync] reacts to: the switch, and the three wire values it resolves. */
private data class Following(
    val server: Boolean,
    val motif: String?,
    val paint: String?,
    val style: String?,
)
