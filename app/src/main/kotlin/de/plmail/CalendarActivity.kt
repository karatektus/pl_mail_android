package de.plmail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import de.plmail.core.data.AppLocaleOverride
import de.plmail.feature.calendar.CalendarScreen

/**
 * The calendar when it is its own app.
 *
 * Reached only through the `CalendarLauncher` alias, which the user switches on in settings and
 * which is disabled in the manifest until they do. The drawer's own Calendar entry is untouched and
 * still opens the calendar inside [MainActivity]; this is a second door, not a replacement, because
 * the toggle is off for everybody who never finds it.
 *
 * **Why a second activity at all.** The manifest carries the full argument; the short version is
 * that an `<activity-alias>` cannot declare `taskAffinity` or `launchMode` — both are outside the
 * attribute subset an alias may override, so an alias over [MainActivity] would inherit its
 * affinity and its `singleTask` and land in the mail's task. One card in Recents, no split screen.
 * The affinity has to be declared on an activity, so there has to be an activity.
 *
 * **Which is also why there is nothing here that arbitrates between two tasks.** The obvious
 * alternative was one activity entered two ways, with an extra or an action telling it to come up
 * with `isCalendaring` already set. That version has a real problem: `MainActivity` is
 * `singleTask`, so there is exactly one instance of it, and both doors would be steering the same
 * instance and the same `isCalendaring` boolean. Opening the calendar app while the mail was open
 * would either hijack the mail task's screen or need a second instance of a `singleTask` activity,
 * which is not a thing. Two activities with two affinities have no shared state to fight over, and
 * that is the whole reason for the shape.
 *
 * **Back is deliberately unhandled here.** [CalendarScreen] takes the gesture for its own detail
 * and editor pages and disables its handler on the board, exactly as it does inside [MainActivity].
 * What changes is what it falls through *to*: there, `:app` catches it and clears `isCalendaring`,
 * which puts the mail list back. Here nothing catches it, so the activity finishes and the task
 * ends — which is the requirement. Adding a handler is what would break it.
 */
@AndroidEntryPoint
class CalendarActivity : ComponentActivity() {

    /**
     * The chosen language, for the reason [MainActivity.attachBaseContext] gives.
     *
     * Repeated here rather than shared, because there is nothing to share it through: an activity's
     * base context is handed to the activity and each of these is a separate entry into the process
     * with a configuration of its own. Below API 33 this task is *not* re-created when the language
     * changes — the settings screen is in the mail task and can only re-create what it is drawn in
     * — so an open calendar app keeps the previous language until it is next started.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleOverride.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before setContent, so the first frame is drawn edge to edge rather
        // than being inset and then jumping. Same reasoning as MainActivity.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            PlMailAppTheme {
                CalendarApp(
                    // The app bar's arrow means the same thing the gesture does
                    // in a task of one screen: leave. finish() and not
                    // finishAndRemoveTask(), because a user who backs out of an
                    // app expects to find it again in Recents.
                    onLeave = ::finish,
                    onNotPaired = ::openOnboarding,
                )
            }
        }
    }

    /**
     * Hands an unpaired launch to the mail app, and takes this task away behind it.
     *
     * The icon can outlive the connection: signing out leaves it on the home screen, and it is left
     * there on purpose rather than being switched off underneath the user — an icon they added is
     * theirs, and pairing again brings the calendar back to it. What must not happen is the icon
     * opening an empty calendar, so this sends them to the one screen that can help.
     *
     * `NEW_TASK` is stated rather than relied on. [MainActivity] is `singleTask` under a different
     * affinity and the system would move it out of this task regardless; saying so means the intent
     * reads as what it is instead of depending on that.
     *
     * `finishAndRemoveTask` and not `finish` here, and this is the one place the difference
     * matters: a calendar task whose only screen bounced straight to onboarding has nothing worth
     * returning to, so leaving its card in Recents would leave a card that opens onboarding.
     */
    private fun openOnboarding() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        finishAndRemoveTask()
    }
}

/**
 * The calendar app's one screen, and the check that there is a server behind it.
 *
 * The same three-state read [MainActivity] does, and for the same reason: `Unknown` is a real state
 * and collapsing it into "not paired" would bounce every cold launch through onboarding for a
 * frame. What differs is the answer to `None` — the mail app shows onboarding itself, and this one
 * has nowhere to show it, so it hands over.
 */
@Composable
private fun CalendarApp(
    onLeave: () -> Unit,
    onNotPaired: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()

    when (connection) {
        ConnectionState.Unknown -> Unit // The first frame, before the store has been read.
        ConnectionState.None -> LaunchedEffect(Unit) { onNotPaired() }
        // The calendar owns its own detail, editor and the back between them,
        // exactly as it does in the mail app. All that changes is where its
        // outermost back goes, and that is the activity's business rather than
        // the screen's.
        is ConnectionState.Connected -> CalendarScreen(onBack = onLeave)
    }
}
