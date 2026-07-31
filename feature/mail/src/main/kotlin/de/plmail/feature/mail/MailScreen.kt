package de.plmail.feature.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import de.plmail.core.database.ThreadEntity

/**
 * The unified inbox.
 *
 * Rows come from the feed table, so a cold launch draws whatever was last synced before any request
 * leaves the device. That is the point of the table, and it is why the empty state below is only
 * shown once loading has actually settled — flashing "nothing here" at someone whose mail is on
 * disk would be a lie about their own inbox.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailScreen(
    onThreadSelected: (ThreadEntity) -> Unit,
    viewModel: MailViewModel = hiltViewModel(),
) {
    val threads = viewModel.threads.collectAsLazyPagingItems()
    val unreachable by viewModel.unreachable.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.inbox_title)) }) },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            // Above the list, not instead of it. One unreachable account must
            // never take the other accounts' mail off the screen.
            unreachable.forEach { account ->
                Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text =
                                stringResource(
                                    R.string.account_unreachable,
                                    account.displayName,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { threads.retry() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }

            ThreadList(threads = threads, onThreadSelected = onThreadSelected)
        }
    }
}

@Composable
private fun ThreadList(
    threads: LazyPagingItems<ThreadEntity>,
    onThreadSelected: (ThreadEntity) -> Unit,
) {
    val refreshing = threads.loadState.refresh is LoadState.Loading

    if (threads.itemCount == 0) {
        // "Nothing here" and "still looking" are different answers, and showing
        // the first while the first page is in flight tells someone their inbox
        // is empty when it is not.
        Message(stringResource(if (refreshing) R.string.inbox_loading else R.string.inbox_empty))
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(
            count = threads.itemCount,
            // Keyed on the row's own identity, so an inserted message does not
            // recycle every row below it and lose their scroll position.
            key = { index -> threads.peek(index)?.uid ?: index },
        ) { index ->
            val thread = threads[index]

            if (thread != null) {
                ThreadRow(thread = thread, onClick = { onThreadSelected(thread) })
                HorizontalDivider()
            }
        }

        if (threads.loadState.append is LoadState.Loading) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
