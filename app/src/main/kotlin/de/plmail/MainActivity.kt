package de.plmail

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.feature.calendar.CalendarScreen
import de.plmail.feature.compose.ComposeHost
import de.plmail.feature.compose.ComposeRequest
import de.plmail.feature.compose.ComposeRequestSaver
import de.plmail.feature.compose.ScheduledSendsBar
import de.plmail.feature.compose.SendStatusHost
import de.plmail.feature.mail.MailShell
import de.plmail.feature.mail.ThreadTarget
import de.plmail.feature.onboarding.OnboardingScreen
import de.plmail.feature.search.SearchScreen
import de.plmail.feature.settings.AccountsScreen
import de.plmail.feature.settings.AppearanceScreen
import de.plmail.feature.settings.AppearanceViewModel
import de.plmail.feature.settings.DiagnosticsScreen
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
     */
    private var pendingLink by mutableStateOf<String?>(null)

    /**
     * What a tapped notification asked for, if this launch came from one.
     *
     * Held beside [pendingLink] rather than folded into it: a pairing link and a notification are
     * both "the intent that started us", and both arrive through [onNewIntent] as well, but they
     * are consumed by different screens and one of them is only meaningful once a server is
     * connected.
     */
    private var pendingNotification by mutableStateOf<NotificationRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before setContent, so the first frame is already drawn edge to edge
        // rather than being inset and then jumping.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        pendingLink = intent?.data?.toString()
        pendingNotification = intent?.notificationRequest()

        setContent {
            PlMailAppTheme {
                PlMailApp(
                    pendingLink = pendingLink,
                    onLinkHandled = { pendingLink = null },
                    notification = pendingNotification,
                    onNotificationHandled = { pendingNotification = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // setIntent as well, so anything that later reads getIntent() sees the
        // one that is actually being acted on rather than the launch intent.
        setIntent(intent)
        pendingLink = intent.data?.toString()

        // Replaced rather than merged. Tapping a second notification while the
        // first conversation is open means "show me that one instead", and a
        // queue would make the second tap open the first mail again.
        pendingNotification = intent.notificationRequest()
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
    // Reached from the push screen rather than the drawer: the log is
    // evidence about a registration, and reading it without the registration
    // above it is reading a column of timestamps.
    var isReadingPushLog by rememberSaveable { mutableStateOf(false) }
    var isAdjustingAppearance by rememberSaveable { mutableStateOf(false) }
    var isManagingAccounts by rememberSaveable { mutableStateOf(false) }
    var isCalendaring by rememberSaveable { mutableStateOf(false) }
    var composing by rememberSaveable(stateSaver = ComposeRequestSaver) { mutableStateOf(null) }

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
        }

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
                    } else if (screen == Screen.DIAGNOSTICS) {
                        // Above search in this chain rather than beside it,
                        // because a notification tap has to win over both: mail
                        // arriving is a reason to leave a screen the user opened
                        // to find out why mail was not arriving.
                        DiagnosticsScreen(onBack = { isDiagnosing = false })
                    } else if (screen == Screen.SEARCH) {
                        SearchScreen(
                            // The reader is M4's and reached from the list;
                            // opening a result closes search, so Back returns to
                            // the mail list rather than to a query the user has
                            // finished with.
                            onOpenThread = { _, _ -> isSearching = false },
                            onBack = { isSearching = false },
                        )
                    } else {
                        MailShell(
                            onSearch = { isSearching = true },
                            onPush = { isChoosingPush = true },
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
                            onThreadOpened = onNotificationHandled,
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
    PUSH_LOG,
    APPEARANCE,
    ACCOUNTS,
}
