package de.plmail.feature.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * The lists reachable from the shell.
 *
 * Only the three plMail already models as roles. Labels are the user-facing concept and arrive with
 * M9 — until then this deliberately does not invent a sidebar that the server cannot fill, because
 * a navigation item that leads nowhere is worse than an absent one.
 */
enum class MailDestination(val label: Int, val icon: ImageVector) {
    INBOX(R.string.inbox_title, Icons.Outlined.Inbox),
    SENT(R.string.sent_title, Icons.AutoMirrored.Outlined.Send),
    DRAFTS(R.string.drafts_title, Icons.Outlined.Drafts),
}

/**
 * The app's navigation frame.
 *
 * `NavigationSuiteScaffold` picks the presentation from the window size itself — a bottom bar or
 * modal drawer on a phone, a permanent rail on a tablet — which is the whole reason for using it
 * rather than branching on a width breakpoint by hand. Getting that branch right is easy; keeping
 * it right through a foldable unfolding, a split-screen resize and a desktop window being dragged
 * narrower is not, and those are all resize events rather than new activities.
 *
 * Inside it sits [MailPane], which owns the list/detail split independently. The two adapt on
 * different axes and must not be conflated: a tablet shows the rail *and* both panes, a phone shows
 * a bottom bar and one pane at a time.
 */
@Composable
fun MailShell() {
    var destination by rememberSaveable { mutableStateOf(MailDestination.INBOX) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            MailDestination.entries.forEach { entry ->
                item(
                    selected = entry == destination,
                    onClick = { destination = entry },
                    icon = {
                        Icon(imageVector = entry.icon, contentDescription = null)
                    },
                    label = { Text(stringResource(entry.label)) },
                )
            }
        }
    ) {
        when (destination) {
            MailDestination.INBOX -> MailPane()
            // Sent and Drafts are the same list against a different mailbox
            // binding, which needs the per-role filter M9 introduces. Named
            // here rather than hidden so the shape of the shell is visible.
            MailDestination.SENT,
            MailDestination.DRAFTS -> ComingSoon(stringResource(destination.label))
        }
    }
}

@Composable
private fun ComingSoon(name: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.coming_soon, name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
