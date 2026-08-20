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
 * Keeps the launcher icon on whatever colourway the account says, for as long as this device is
 * following the account at all.
 *
 * The colourway is picked in the browser and published by the JMAP `Appearance` singleton as a
 * read-only `logoStyle`. `AppearanceRepository` reads it on foreground and on every sync — there is
 * no `Appearance/changes` and no push for this object, so those are the only two moments it can be
 * noticed — and it lands in DataStore. This is what spends it.
 *
 * **A flow rather than a call at the end of `refresh`.** The value reaches this class the same way
 * the theme reaches the app: by being on disk and observed. That means a colourway read by the
 * fifteen-minute worker is applied without the worker knowing anything about launcher icons, and it
 * means the one place that decides what the icon should be is not also a place that has to be
 * called from every path that might have changed it.
 *
 * **[AppearanceSettings.syncWithServer][de.plmail.core.data.AppearanceSettings.syncWithServer]
 * gates it, and off means "keep what you have" rather than "go back to the default".** A phone that
 * has been taken off the account's appearance keeps the icon it was wearing when the switch was
 * thrown: the alias stays enabled, nothing is written, and the home screen does not change under
 * somebody who has just said they want this device left alone. That is why the flag is paired with
 * the wire name below rather than filtered out of the flow — turning the switch back *on* has to
 * re-apply, and after `setSyncWithServer(true)` the colourway itself has very often not changed at
 * all, so a flow keyed on the wire name alone would emit nothing and the icon would stay wrong.
 *
 * Nothing here writes to the server, and there is nothing it could write to: `logoStyle` is
 * read-only. See `AppearanceRepository` for why that is the rule for every appearance value and not
 * just this one.
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
     * install rather than about anything on screen, and a colourway read by a background sync has
     * to be applied whether or not anybody is looking.
     */
    fun start() {
        if (following?.isActive == true) return

        following = scope.launch {
            appearances.settings
                .map { settings -> settings.syncWithServer to settings.logoStyle }
                // The repository's own flow is distinct on the whole record,
                // so it emits when any of eighteen appearance values moves.
                // Narrowing to the pair this class acts on is what keeps a
                // font-size change off the package manager.
                .distinctUntilChanged()
                .collect { (followsServer, wire) ->
                    if (!followsServer) return@collect

                    // Unknown and absent both land on the default here, and
                    // `wear` is a no-op when that is already what is on the
                    // home screen -- which it is on every install that has
                    // never been told otherwise. See LogoStyle.fromWire.
                    icon.wear(LogoStyle.fromWire(wire))
                }
        }
    }
}
