package de.plmail

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
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
import de.plmail.core.designsystem.PlMailDensity
import de.plmail.core.designsystem.PlMailLayout
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.designsystem.PlMailThemeChoice
import de.plmail.feature.compose.ComposeRequest
import de.plmail.feature.compose.ComposeRequestSaver
import de.plmail.feature.compose.ComposeScreen
import de.plmail.feature.compose.SendStatusHost
import de.plmail.feature.mail.MailShell
import de.plmail.feature.onboarding.OnboardingScreen
import de.plmail.feature.search.SearchScreen

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

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before setContent, so the first frame is already drawn edge to edge
        // rather than being inset and then jumping.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        pendingLink = intent?.data?.toString()

        setContent {
            PlMailAppTheme {
                PlMailApp(pendingLink = pendingLink, onLinkHandled = { pendingLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // setIntent as well, so anything that later reads getIntent() sees the
        // one that is actually being acted on rather than the launch intent.
        setIntent(intent)
        pendingLink = intent.data?.toString()
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
    viewModel: MainViewModel = hiltViewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()

    // Search and compose live here rather than inside the mail shell because
    // they are their own feature modules: :feature:mail depending on either
    // would make peers into a chain, and the module boundary exists to prevent
    // exactly that. :app is the one place allowed to know about all three.
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var composing by rememberSaveable(stateSaver = ComposeRequestSaver) { mutableStateOf(null) }

    // Over the mail list, never over the composer: the composer has closed by
    // the time the undo window is running, which is the whole point of a window
    // rather than a confirmation.
    val snackbars = remember { SnackbarHostState() }

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

                val request = composing

                when {
                    request != null ->
                        ComposeScreen(
                            request = request,
                            onClose = { composing = null },
                        )
                    isSearching ->
                        SearchScreen(
                            // The reader is M4's and reached from the list;
                            // opening a result closes search, so Back returns to
                            // the mail list rather than to a query the user has
                            // finished with.
                            onOpenThread = { _, _ -> isSearching = false },
                            onBack = { isSearching = false },
                        )
                    else ->
                        MailShell(
                            onSearch = { isSearching = true },
                            onCompose = { composing = ComposeRequest.New },
                            onReply = { accountKey, emailId, all ->
                                composing = ComposeRequest.Reply(accountKey, emailId, all)
                            },
                            onForward = { accountKey, emailId ->
                                composing = ComposeRequest.Forward(accountKey, emailId)
                            },
                        )
                }

                // Last, so it draws over the screen it belongs to, and inset
                // only against the navigation bar: it floats above content
                // rather than being laid out with it, so the gesture bar is the
                // one thing it has to clear.
                SnackbarHost(
                    hostState = snackbars,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                )
            }
    }
}

/**
 * The app's theme, from local settings for now and from the server's `Appearance` when that is
 * exposed.
 *
 * The two-axis model lives in `:core:designsystem`; this is only the place that decides which
 * theme, layout and density to hand it. When the settings screen arrives it replaces these defaults
 * and touches nothing else, which is the whole reason the resolver is a separate module.
 */
@Composable
private fun PlMailAppTheme(content: @Composable () -> Unit) {
    PlMailTheme(
        theme = PlMailThemeChoice.SYSTEM,
        layout = PlMailLayout.FLAT,
        density = PlMailDensity.COMFORTABLE,
        content = content,
    )
}
