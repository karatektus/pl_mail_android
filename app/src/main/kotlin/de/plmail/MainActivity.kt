package de.plmail

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
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
            PlMailTheme {
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

    // Search lives here rather than inside the mail shell because it is its own
    // feature module: :feature:mail depending on :feature:search would make two
    // peers into a chain, and the module boundary exists to prevent exactly that.
    // :app is the one place that is allowed to know about both.
    var isSearching by rememberSaveable { mutableStateOf(false) }

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
        is ConnectionState.Connected ->
            if (isSearching) {
                SearchScreen(
                    // The reader is M4's and reached from the list; opening a
                    // result closes search, so Back returns to the mail list
                    // rather than to a query the user has finished with.
                    onOpenThread = { _, _ -> isSearching = false },
                    onBack = { isSearching = false },
                )
            } else {
                MailShell(onSearch = { isSearching = true })
            }
    }
}

/**
 * Material 3 with the platform's own colours, for now.
 *
 * plMail's real appearance model is two-axis — six Themes crossed with two Layouts, plus density
 * and per-knob overrides, all resolved through semantic tokens rather than raw palette values. That
 * arrives with its own milestone. What matters until then is that no screen hardcodes a colour, so
 * swapping the source of these values touches this function and nothing else.
 */
@Composable
private fun PlMailTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
