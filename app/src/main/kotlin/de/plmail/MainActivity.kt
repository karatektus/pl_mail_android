package de.plmail

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Before setContent, so the first frame is already drawn edge to edge
        // rather than being inset and then jumping.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent { PlMailTheme { PlaceholderScreen() } }
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
private fun PlaceholderScreen() {
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
                text = stringResource(R.string.placeholder_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaceholderScreenPreview() {
    PlMailTheme { PlaceholderScreen() }
}
