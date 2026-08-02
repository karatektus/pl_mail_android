package de.plmail

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import de.plmail.core.data.LiveUpdates
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ties the live updates to the app being visible, and to nothing else.
 *
 * **Not a foreground `Service`, and that is a product decision rather than a shortcut.** An
 * EventSource connection holds a PHP worker on the server for its whole life, so a stream that
 * outlived the visible app would occupy one of a NAS's handful of workers all day, for a user who
 * is not looking. Web Push exists precisely so that background delivery costs the server nothing
 * between messages; this is the foreground half, and it has to die with the screen.
 *
 * **`ProcessLifecycleOwner` rather than `registerActivityLifecycleCallbacks`.** The activity
 * callbacks fire on every configuration change, so the callback version would tear the stream down
 * and rebuild it — reconnecting to somebody's server, and re-syncing every account — every time the
 * phone was turned sideways. `ProcessLifecycleOwner` debounces exactly that, and reports only the
 * app genuinely arriving and genuinely leaving.
 */
@Singleton
class ForegroundPresence @Inject constructor(private val live: LiveUpdates) :
    DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        live.start()
    }

    override fun onStop(owner: LifecycleOwner) {
        live.stop()
    }
}
