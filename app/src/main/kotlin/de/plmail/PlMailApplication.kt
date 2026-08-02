package de.plmail

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
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
 */
@HiltAndroidApp
class PlMailApplication : Application() {

    @Inject lateinit var presence: ForegroundPresence

    override fun onCreate() {
        super.onCreate()

        ProcessLifecycleOwner.get().lifecycle.addObserver(presence)
    }
}
