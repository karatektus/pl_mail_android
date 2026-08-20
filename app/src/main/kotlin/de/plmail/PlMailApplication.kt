package de.plmail

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import de.plmail.core.data.AppLocaleOverride
import javax.inject.Inject

/**
 * The application object.
 *
 * Deliberately near-empty, and meant to stay that way. Everything the app needs at launch belongs
 * behind an injected dependency that can be replaced in a test; work done here runs on every cold
 * start, before anything knows whether it is needed, and cannot be swapped out.
 *
 * [HiltAndroidApp] is the one exception, and it is not really work — it is the annotation that
 * generates the component everything else is injected from.
 *
 * [ForegroundPresence] is the rule rather than a second exception: the observer is injected, so a
 * test replaces it by replacing a binding, and this class holds one line that registers it. The
 * registration itself cannot live anywhere else — `ProcessLifecycleOwner` is per process, and an
 * activity registering it would tie "the app is visible" to one screen.
 *
 * [attachBaseContext] is the same shape and the same argument: the decision it applies is somebody
 * else's, this class holds the one line that applies it, and it cannot live anywhere else because
 * the base context is created exactly once and this is the only hook in front of it.
 *
 * [icons] is the fourth, and it is a registration for the same reason [presence] is: the launcher
 * icon follows the account's logo colourway, which is a fact about the *install* rather than about
 * anything on screen, so no activity may own the subscription. It reaches the package manager only
 * when the colourway has actually changed — see [LauncherIconSync] and [AppLauncherIcon] — so what
 * runs on a cold start is one flow subscription and, almost always, nothing else at all.
 */
@HiltAndroidApp
class PlMailApplication : Application() {

    @Inject lateinit var presence: ForegroundPresence

    @Inject lateinit var icons: LauncherIconSync

    /**
     * The third exception, and the one that has to run before everything else.
     *
     * This is the *application* context's language, which is the one `:core:notifications` resolves
     * notification text and channel names from — a notification is posted from a worker or a push
     * receiver, with no activity in sight. Above API 33 the platform has already applied the
     * per-app locale to this configuration and [AppLocaleOverride.wrap] hands the context straight
     * back; on API 31 and 32 it is applied here, from disk, because there is nothing else that
     * would.
     *
     * `attachBaseContext` rather than `onCreate`, because by `onCreate` the context whose resources
     * everything will be resolved against already exists.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocaleOverride.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()

        ProcessLifecycleOwner.get().lifecycle.addObserver(presence)

        icons.start()
    }
}
