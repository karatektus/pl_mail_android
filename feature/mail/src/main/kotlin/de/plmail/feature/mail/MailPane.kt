package de.plmail.feature.mail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The list and the reader, side by side where there is room.
 *
 * `NavigableListDetailPaneScaffold` rather than a hand-rolled `if (isTablet)`: it owns the back
 * behaviour as well as the layout, and back is the part that is easy to get wrong. On a phone,
 * opening a conversation is a navigation step and back must return to the list; on a tablet both
 * panes are visible and back must leave the screen instead. A layout-only solution gets the columns
 * right and traps the user in the reader.
 *
 * The selected thread is a [rememberSaveable] uid rather than the row object, so a rotation or a
 * process death restores the selection without holding an entity across it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MailPane() {
    val navigator = rememberListDetailPaneScaffoldNavigator<Nothing>()
    val scope = rememberCoroutineScope()
    var selectedUid by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSubject by rememberSaveable { mutableStateOf<String?>(null) }

    // Only when the detail pane is the one being shown. On a tablet both panes
    // are visible and there is nothing to go back *from*, so intercepting here
    // would swallow the gesture that should leave the screen.
    BackHandler(enabled = navigator.canNavigateBack()) {
        scope.launch { navigator.navigateBack() }
    }

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                MailScreen(
                    onThreadSelected = { thread ->
                        selectedUid = thread.uid
                        selectedSubject = thread.subject
                        scope.launch {
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
                        }
                    }
                )
            }
        },
        detailPane = {
            AnimatedPane { ReaderPlaceholder(subject = selectedSubject, uid = selectedUid) }
        },
    )
}

/**
 * Where the reader goes.
 *
 * M4's job. Showing the selected conversation's subject rather than a blank pane so the selection
 * is visibly working — a pane that never changes looks like a broken list.
 */
@Composable
private fun ReaderPlaceholder(subject: String?, uid: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (uid == null) {
            Text(
                text = stringResource(R.string.reader_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.no_subject),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.reader_coming),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
