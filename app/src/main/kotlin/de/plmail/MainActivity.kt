package de.plmail

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import de.plmail.feature.onboarding.OnboardingScreen

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
        is ConnectionState.Connected ->
            PlaceholderScreen((connection as ConnectionState.Connected).username)
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

@Composable
private fun PlaceholderScreen(username: String) {
    Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.placeholder_signed_in_as, username),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.placeholder_no_mail_yet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaceholderScreenPreview() {
    PlMailTheme { PlaceholderScreen(username = "someone@example.com") }
}
