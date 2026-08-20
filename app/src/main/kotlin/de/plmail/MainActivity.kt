package de.plmail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import de.plmail.core.data.AppLocaleOverride
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.feature.calendar.CalendarScreen
import de.plmail.feature.compose.ComposeHost
import de.plmail.feature.compose.ComposeRequest
import de.plmail.feature.compose.ComposeRequestSaver
import de.plmail.feature.compose.ScheduledSendsBar
import de.plmail.feature.compose.SendStatusHost
import de.plmail.feature.compose.ShareIntake
import de.plmail.feature.compose.SharedMessage
import de.plmail.feature.mail.MailShell
import de.plmail.feature.mail.ThreadTarget
import de.plmail.feature.onboarding.OnboardingScreen
import de.plmail.feature.search.SearchScreen
import de.plmail.feature.settings.AccountsScreen
import de.plmail.feature.settings.AppearanceScreen
import de.plmail.feature.settings.AppearanceViewModel
import de.plmail.feature.settings.DiagnosticsScreen
import de.plmail.feature.settings.NotificationsScreen
import de.plmail.feature.settings.PushLogScreen
import de.plmail.feature.settings.PushScreen
import de.plmail.notifications.NotificationRequest
import de.plmail.notifications.RequestNotificationPermission
import de.plmail.notifications.notificationRequest

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * The `plmail://` URI this activity was opened with, if any.
     *
     * Held as state rather than read from `intent` inside composition, because [onNewIntent] can
     * replace it while the activity is alive — tapping a pairing link while onboarding is already
     * open is the ordinary case, not an edge one, and reading `intent` during composition would
     * keep showing the URI the activity first launched with.
     *
     * **Filtered by scheme**, which it did not used to have to be. This activity now answers VIEW
     * for `mailto:` as well as for `plmail://`, and both arrive as `intent.data` — so handing
     * whatever turned up straight to onboarding would have it try to redeem an email address as a
     * pairing code.
     */
    private var pendingLink by mutableStateOf<String?>(null)

    /**
     * What another app shared, if this launch came from a share sheet or a `mailto:`.
     *
     * The parsing happens here and the copying does not: turning the intent into a [SharedMessage]
     * is field access, while taking a copy of the files is IO and belongs in a scope. See
     * [MainViewModel.stage].
     *
     * Not cleared by an ordinary launch. A share that arrives before the app has a server waits
     * here through the whole of onboarding and opens the moment there is an account to send it
     * from, which is the difference between "share into plMail" working on a fresh install and it
     * dropping the user's photo on the floor.
     */
    private var pendingShare by mutableStateOf<SharedMessage?>(null)

    /**
     * What a tapped notification asked for, if this launch came from one.
     *
     * Held beside [pendingLink] rather than folded into it: a pairing link and a notification are
     * both "the intent that started us", and both arrive through [onNewIntent] as well, but they
     * are consumed by different screens and one of them is only meaningful once a server is
     * connected.
     */
    private var pendingNotification by mutableStateOf<NotificationRequest?>(null)

    /**
     * The chosen language, laid over this activity's own configuration.
     *
     * An activity's configuration comes from the ActivityThread rather than from the application's,
     * so `PlMailApplication`'s wrap does not reach it and this is not a duplicate of it. On API 33
     * and up [AppLocaleOverride.wrap] hands the context back untouched — the platform has already
     * applied the per-app locale, and a second override is a second answer.
     *
     * This is also what makes the choice take effect: below API 33 the settings screen re-creates
     * the activity, and re-creation runs this again against the tag that was just stored.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleOverride.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before setContent, so the first frame is already drawn edge to edge
        // rather than being inset and then jumping.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Only on a fresh create, and this is a fix rather than tidiness.
        //
        // `getIntent()` keeps returning the intent that started the activity for
        // as long as the activity exists, and onCreate runs again on every
        // recreation — a rotation, a theme change, a process death the task
        // survived. Re-reading it there means acting on the same intent twice.
        // For the pairing link that is a code redeemed a second time; for a
        // share it is every attached file copied again, tens of megabytes at a
        // time, on a gesture as ordinary as turning the phone.
        //
        // Nothing is lost by not re-reading. What the composer is open on lives
        // in a `rememberSaveable` below and comes back on its own, which is
        // exactly what a saved request is for.
        //
        // The one cost, stated because it is real: a rotation during the second
        // or two in which a share's files are still being copied loses that
        // share, because the copy had not finished and there is no saved request
        // yet. Sharing again is the recovery, and it is a smaller window than
        // the one this closes.
        if (savedInstanceState == null) accept(intent)

        setContent {
            PlMailAppTheme {
                PlMailApp(
                    pendingLink = pendingLink,
                    onLinkHandled = { pendingLink = null },
                    notification = pendingNotification,
                    onNotificationHandled = { pendingNotification = null },
                    share = pendingShare,
                    onShareHandled = { pendingShare = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // setIntent as well, so anything that later reads getIntent() sees the
        // one that is actually being acted on rather than the launch intent.
        setIntent(intent)
        accept(intent)
    }

    /**
     * Sorts one intent into the three things it can be.
     *
     * Shared between [onCreate] and [onNewIntent] rather than written twice, and it became worth
     * sharing the moment there were three: this activity is `singleTask` and carries five intent
     * filters, so every one of these can arrive either way and a rule applied in one place only is
     * a rule that holds on a cold start and not on a warm one.
     */
    private fun accept(intent: Intent?) {
        // Only our own scheme. The mailto: filter added beside the pairing one
        // is also VIEW, so `intent.data` is no longer proof of a pairing link.
        pendingLink = intent?.data?.toString()?.takeIf { it.startsWith(PAIRING_SCHEME) }

        // Replaced rather than merged. Tapping a second notification while the
        // first conversation is open means "show me that one instead", and a
        // queue would make the second tap open the first mail again.
        pendingNotification = intent?.notificationRequest()

        // Assigned only when there is one, unlike the two above. A share that is
        // waiting for an account must survive whatever else happens to this
        // activity in the meantime, and during onboarding what happens is a
        // pairing link arriving through this very method.
        ShareIntake.read(intent)?.let { pendingShare = it }
    }

    private companion object {
        const val PAIRING_SCHEME = "plmail:"
    }
}

/**
 * Chooses between onboarding and the app.
 *
 * The decision is the presence of a stored connection, and it is deliberately read from the store
 * rather than remembered after onboarding finishes: a credential whose Keystore key did not survive
 * a restore reads as absent, and this is what turns that into "pair again" instead of a launch into
 * a mailbox the app cannot reach.
 */
@Composable
private fun PlMailApp(
    pendingLink: String?,
    onLinkHandled: () -> Unit,
    notification: NotificationRequest?,
    onNotificationHandled: () -> Unit,
    share: SharedMessage?,
    onShareHandled: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val hasCalendar by viewModel.hasCalendar.collectAsStateWithLifecycle()

    // Search and compose live here rather than inside the mail shell because
    // they are their own feature modules: :feature:mail depending on either
    // would make peers into a chain, and the module boundary exists to prevent
    // exactly that. :app is the one place allowed to know about all three.
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var isDiagnosing by rememberSaveable { mutableStateOf(false) }
    var isChoosingPush by rememberSaveable { mutableStateOf(false) }
    var isChoosingNotifications by rememberSaveable { mutableStateOf(false) }
    // Reached from the push screen rather than the drawer: the log is
    // evidence about a registration, and reading it without the registration
    // above it is reading a column of timestamps.
    var isReadingPushLog by rememberSaveable { mutableStateOf(false) }
    var isAdjustingAppearance by rememberSaveable { mutableStateOf(false) }
    var isManagingAccounts by rememberSaveable { mutableStateOf(false) }
    var isCalendaring by rememberSaveable { mutableStateOf(false) }
    var composing by rememberSaveable(stateSaver = ComposeRequestSaver) { mutableStateOf(null) }

    // A conversation picked from a search result. It goes through the same
    // openThread door as a notification tap because that is the only door:
    // MailPane owns the list/detail navigator, and closing search just puts the
    // mail *list* on screen — which is exactly what tapping a result used to
    // do, and was a bug, not a shortcut.
    var searchPick by
        rememberSaveable(stateSaver = ThreadTargetSaver) { mutableStateOf<ThreadTarget?>(null) }

    // Over the mail list, never over the composer: the composer has closed by
    // the time the undo window is running, which is the whole point of a window
    // rather than a confirmation.
    val snackbars = remember { SnackbarHostState() }

    // A notification tap, split into the two things it can mean. Reply is
    // translated into a compose request straight away because the composer is
    // already hoisted here; opening a conversation is handed down to the shell,
    // which owns the pane that shows it.
    val openThread =
        (notification as? NotificationRequest.OpenConversation)?.let {
            ThreadTarget(accountKey = it.accountKey, threadId = it.threadId)
        } ?: searchPick

    LaunchedEffect(notification) {
        val reply = notification as? NotificationRequest.Reply ?: return@LaunchedEffect

        // Search would otherwise still be on screen behind the composer after a
        // tap that arrived while the user was mid-query.
        isSearching = false
        composing = ComposeRequest.Reply(reply.accountKey, reply.emailId, all = false)
        onNotificationHandled()
    }

    when (connection) {
        ConnectionState.Unknown -> Unit // The very first frame, before the store has been read.
        ConnectionState.None ->
            OnboardingScreen(
                // Nothing to do: the store is the source of truth, so saving
                // flips `connection` and this `when` swaps the screen itself.
                onFinished = {},
                pendingLink = pendingLink,
                onLinkHandled = onLinkHandled,
            )
        // The mail pane owns its own layout and back behaviour from here on;
        // :app only decides whether there is a server to show it for.
        //
        // A plain Box and not a Scaffold, and that is the fix for a real defect
        // rather than a preference. Every screen below draws its own app bar,
        // and an app bar applies the status-bar inset itself. A Scaffold here
        // handed the same inset out a second time as content padding, and
        // applying it pushed the entire app down by an extra status bar --
        // 136px of it on the test device, because that AVD's cutout is deep --
        // which is what put roughly 80dp of dead space between the notch and
        // the "Inbox" title and lifted the bottom navigation off the gesture
        // bar by the same trick. Insets belong to whoever draws the chrome that
        // avoids them, and nothing at this level draws any.
        is ConnectionState.Connected ->
            Box(modifier = Modifier.fillMaxSize()) {
                // Here rather than at launch: a permission prompt asked before
                // there is a mailbox to notify about is a question nobody can
                // answer. See the composable's own note for why it is not asked
                // again after a refusal.
                RequestNotificationPermission()

                // Inside the connected branch, and that placement is the whole
                // handling of "shared into an app with no account". This effect
                // does not exist while onboarding is on screen, so the share
                // simply waits in MainActivity's state; the frame after pairing
                // finishes, this composes for the first time and the composer
                // opens on it. No branch, no message, no dropped photo.
                //
                // The staging inside is IO over files that may be megabytes, so
                // there is a beat between the share sheet closing and the
                // composer appearing. That beat is on purpose: it is where the
                // bytes are copied out of a grant that is about to expire, and
                // it happens with the intent's task still alive, which is the
                // only window in which it is legal.
                LaunchedEffect(share) {
                    val message = share ?: return@LaunchedEffect

                    // Search would otherwise still be behind the composer for
                    // someone who shared into plMail mid-query.
                    isSearching = false
                    composing = viewModel.stage(message)
                    onShareHandled()
                }

                // Mounted for every screen below, so an undo remains reachable
                // after the user has navigated on. Reopening replaces whatever
                // is showing, because the message being recovered is the thing
                // they just asked for.
                SendStatusHost(
                    snackbars = snackbars,
                    onReopen = { request ->
                        isSearching = false
                        composing = request
                    },
                )

                // The composer decides its own presentation from the window: a
                // screen on a phone, a dialog over the mailbox on a tablet. The
                // list below is a slot rather than a sibling because the two
                // cases disagree about whether it should exist at all -- see
                // ComposeHost.
                ComposeHost(request = composing, onClose = { composing = null }) {
                    // One value rather than a chain read twice. The system
                    // back handler below has to dismiss *whichever* screen is on
                    // top, and the flags are not exclusive -- opening appearance
                    // from a search leaves both set, and a notification arriving
                    // outranks all of them -- so deriving the answer once is
                    // what stops back closing a screen nobody can see.
                    val screen =
                        when {
                            openThread != null -> Screen.MAIL
                            isManagingAccounts -> Screen.ACCOUNTS
                            isCalendaring -> Screen.CALENDAR
                            isAdjustingAppearance -> Screen.APPEARANCE
                            isReadingPushLog -> Screen.PUSH_LOG
                            isChoosingPush -> Screen.PUSH
                            isChoosingNotifications -> Screen.NOTIFICATIONS
                            isDiagnosing -> Screen.DIAGNOSTICS
                            isSearching -> Screen.SEARCH
                            else -> Screen.MAIL
                        }

                    // Without this, back on any of these three left the *app*.
                    // They are swapped in by state rather than pushed onto a
                    // back stack, so nothing else was going to consume the
                    // gesture -- and a settings screen you can only leave by
                    // finding the arrow is a settings screen people back out of
                    // and lose the app from.
                    BackHandler(enabled = screen != Screen.MAIL) {
                        when (screen) {
                            Screen.ACCOUNTS -> isManagingAccounts = false
                            Screen.CALENDAR -> isCalendaring = false
                            Screen.APPEARANCE -> isAdjustingAppearance = false
                            // The log closes back onto the push screen it was
                            // opened from, rather than all the way to mail:
                            // somebody who just compared a log against their
                            // server is one tap from switching transport.
                            Screen.PUSH_LOG -> isReadingPushLog = false
                            Screen.PUSH -> isChoosingPush = false
                            Screen.NOTIFICATIONS -> isChoosingNotifications = false
                            Screen.DIAGNOSTICS -> isDiagnosing = false
                            Screen.SEARCH -> isSearching = false
                            Screen.MAIL -> Unit
                        }
                    }

                    if (screen == Screen.ACCOUNTS) {
                        AccountsScreen(onBack = { isManagingAccounts = false })
                    } else if (screen == Screen.CALENDAR) {
                        // The calendar owns its own detail and editor and its
                        // own back between them; what :app decides is only
                        // whether the calendar or the mail is on screen, the
                        // same way it does for every other swapped screen here.
                        CalendarScreen(onBack = { isCalendaring = false })
                    } else if (screen == Screen.APPEARANCE) {
                        AppearanceScreen(onBack = { isAdjustingAppearance = false })
                    } else if (screen == Screen.PUSH_LOG) {
                        PushLogScreen(onBack = { isReadingPushLog = false })
                    } else if (screen == Screen.PUSH) {
                        PushScreen(
                            onBack = { isChoosingPush = false },
                            onLog = { isReadingPushLog = true },
                        )
                    } else if (screen == Screen.NOTIFICATIONS) {
                        NotificationsScreen(onBack = { isChoosingNotifications = false })
                    } else if (screen == Screen.DIAGNOSTICS) {
                        // Above search in this chain rather than beside it,
                        // because a notification tap has to win over both: mail
                        // arriving is a reason to leave a screen the user opened
                        // to find out why mail was not arriving.
                        DiagnosticsScreen(onBack = { isDiagnosing = false })
                    } else if (screen == Screen.SEARCH) {
                        SearchScreen(
                            // Closing search as well as picking: Back from the
                            // opened conversation should return to the mail
                            // list, not to a query the user has finished with.
                            onOpenThread = { accountKey, threadId ->
                                isSearching = false
                                searchPick = ThreadTarget(accountKey, threadId)
                            },
                            onBack = { isSearching = false },
                        )
                    } else {
                        MailShell(
                            onSearch = { isSearching = true },
                            onPush = { isChoosingPush = true },
                            onNotifications = { isChoosingNotifications = true },
                            onDiagnostics = { isDiagnosing = true },
                            onAppearance = { isAdjustingAppearance = true },
                            onAccounts = { isManagingAccounts = true },
                            // Null hides the drawer entry outright, which is
                            // what an instance publishing no calendars
                            // capability has to look like: a product without a
                            // calendar, rather than a control that opens an
                            // empty one.
                            onCalendar = if (hasCalendar) ({ isCalendaring = true }) else null,
                            onCompose = { composing = ComposeRequest.New },
                            onReply = { accountKey, emailId, all ->
                                composing = ComposeRequest.Reply(accountKey, emailId, all)
                            },
                            onForward = { accountKey, emailId ->
                                composing = ComposeRequest.Forward(accountKey, emailId)
                            },
                            openThread = openThread,
                            // Both cleared, not just whichever produced this
                            // target: acknowledging is what stops back-then-
                            // rotate from reopening the conversation, and a
                            // stale pick would do exactly that the moment the
                            // notification ahead of it was consumed.
                            onThreadOpened = {
                                searchPick = null
                                onNotificationHandled()
                            },
                        )
                    }
                }

                // Above the snackbar and below everything else, for the same
                // reason the snackbar is here: a message the server is holding
                // has to stay reachable whichever screen the user has navigated
                // to, and it is the only place on the device its release time
                // exists. Draws nothing when nothing is scheduled, which is
                // nearly always.
                Column(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
                    ScheduledSendsBar()

                    SnackbarHost(hostState = snackbars)
                }
            }
    }
}

/**
 * The app's theme, from local settings today and from the server's `Appearance` when that is
 * exposed.
 *
 * The two-axis model lives in `:core:designsystem` and the choice lives in DataStore; this is only
 * the place that joins them. When `Appearance` arrives it replaces the source of
 * [AppearanceViewModel]'s flow and touches nothing else, which is the whole reason the resolver is
 * a separate module.
 *
 * Above the whole app rather than inside a screen, which is what makes the settings screen its own
 * preview: choosing a theme re-themes the thing being chosen from, live.
 *
 * Internal rather than private so [CalendarActivity] can wear it too. The calendar app is a second
 * window onto the same install and has to be the same app to look at; a copy of these four lines
 * over there is how one of the two ends up not following a theme change.
 */
@Composable
internal fun PlMailAppTheme(
    viewModel: AppearanceViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()

    PlMailTheme(appearance = appearance, content = content)
}

/**
 * Survives process death because [MailPane] acknowledges a target it has acted on, and between the
 * pick and that acknowledgement there is a window a recreation can land in. An empty list is the
 * saved form of "no pick" — a Saver cannot hand back null and mean it.
 */
private val ThreadTargetSaver =
    listSaver<ThreadTarget?, String>(
        save = { target -> target?.let { listOf(it.accountKey, it.threadId) } ?: emptyList() },
        restore = { saved -> if (saved.size == 2) ThreadTarget(saved[0], saved[1]) else null },
    )

/**
 * Which of the state-swapped screens is on top.
 *
 * These are not a back stack — they are booleans held beside each other, and more than one can be
 * true at a time. Naming the winner once is what keeps the back gesture and the thing being drawn
 * from disagreeing, which they did: back on the appearance screen closed the app.
 */
private enum class Screen {
    MAIL,
    CALENDAR,
    SEARCH,
    DIAGNOSTICS,
    PUSH,
    NOTIFICATIONS,
    PUSH_LOG,
    APPEARANCE,
    ACCOUNTS,
}
