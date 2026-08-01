package de.plmail.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import de.plmail.core.data.SkipReason
import de.plmail.core.database.StoreKey
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Search.
 *
 * The query language is Gmail's, parsed on this device and compiled to a JMAP filter — so the chips
 * are not a second way to search but a *readout* of the string, updating as it is typed. Someone
 * who learns the syntax and someone who never does are using the same feature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenThread: (accountKey: String, threadId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snippets by viewModel.snippets.collectAsStateWithLifecycle()
    val skipped by viewModel.skipped.collectAsStateWithLifecycle()
    val results = viewModel.results.collectAsLazyPagingItems()

    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // The keyboard opens with the screen: arriving at search and having to tap
    // the box is a wasted step, and this screen has exactly one purpose.
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Paging owns loading state; asking it rather than tracking a second copy is
    // what keeps the empty state from appearing for a moment mid-load.
    val isSettled = results.loadState.refresh !is LoadState.Loading

    LaunchedEffect(results.itemCount, isSettled, state.query) {
        if (isSettled && results.itemCount == 0) viewModel.onEmptyResults()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChanged,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        placeholder = { Text(stringResource(R.string.search_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions =
                            KeyboardActions(
                                onSearch = {
                                    viewModel.onSubmit()
                                    keyboard?.hide()
                                }
                            ),
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onQueryChanged("") }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.search_clear),
                                    )
                                }
                            }
                        },
                        colors =
                            TextFieldDefaults.colors(
                                // The field *is* the app bar; its own container
                                // and indicator would draw a box inside a box.
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.onLeave()
                            onBack()
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = stringResource(R.string.search_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            QueryChips(state)

            skipped.forEach { account ->
                if (account.reason == SkipReason.NoSuchMailbox) {
                    Text(
                        text =
                            stringResource(
                                R.string.search_account_lacks_mailbox,
                                account.accountKey.substringAfterLast('/'),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = PlMailTheme.colors.inkMuted,
                        modifier =
                            Modifier.padding(
                                horizontal = PlMailTheme.spacing.gutter,
                                vertical = PlMailTheme.spacing.tiny,
                            ),
                    )
                }
            }

            when {
                results.loadState.refresh is LoadState.Loading ->
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                results.itemCount == 0 ->
                    EmptyState(
                        state = state,
                        onRecentChosen = viewModel::onRecentChosen,
                        onForgetRecent = viewModel::onForgetRecent,
                    )

                else ->
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(count = results.itemCount) { index ->
                            val thread = results[index] ?: return@items

                            SearchResultRow(
                                thread = thread,
                                snippet =
                                    snippets[
                                        StoreKey.objectKey(thread.accountKey, thread.threadId)],
                                onClick = { onOpenThread(thread.accountKey, thread.threadId) },
                            )

                            // Between rows, never after the last one. A
                            // hairline with nothing under it implies another
                            // result is coming, so a search that found three
                            // things looked like one that had stopped loading.
                            // The mail list settled this already; search was
                            // written before it did.
                            if (index < results.itemCount - 1) {
                                PlMailDivider(startIndent = 72.dp)
                            }
                        }
                    }
            }
        }
    }
}

/**
 * What the typed string was understood to mean.
 *
 * Shown rather than offered: tapping one removes that operator from the query, which is the only
 * edit a chip can make unambiguously. Adding operators is what the keyboard is for, and a chip that
 * inserted `is:unread` into a half-typed `from:"a b` would have to guess where.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueryChips(state: SearchUiState) {
    val query = state.parsed
    val labels = buildList {
        query.from?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.chip_from, it)) }
        query.to?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.chip_to, it)) }
        query.subject
            ?.takeIf { it.isNotBlank() }
            ?.let { add(stringResource(R.string.chip_subject, it)) }

        if (query.hasAttachment) add(stringResource(R.string.chip_attachment))
        if (query.isUnread) add(stringResource(R.string.chip_unread))
        if (query.isRead) add(stringResource(R.string.chip_read))
        if (query.isStarred) add(stringResource(R.string.chip_starred))

        query.mailbox?.let { add(stringResource(R.string.chip_in, it.wire)) }
        query.after?.let { add(stringResource(R.string.chip_after, it.asDate())) }
        query.before?.let { add(stringResource(R.string.chip_before, it.asDate())) }
    }

    if (labels.isEmpty()) return

    FlowRow(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = PlMailTheme.spacing.gutter,
                    vertical = PlMailTheme.spacing.tiny,
                ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEach { label ->
            FilterChip(selected = true, onClick = {}, label = { Text(label) })
        }
    }

    // Both set is contradictory and matches nothing on every client, which is
    // reproduced rather than resolved -- so it has to be *said*, or the reader
    // sees an empty list and no reason for it.
    if (query.isRead && query.isUnread) {
        Text(
            text = stringResource(R.string.search_contradiction),
            style = MaterialTheme.typography.bodySmall,
            color = PlMailTheme.colors.danger,
            modifier =
                Modifier.padding(
                    horizontal = PlMailTheme.spacing.gutter,
                    vertical = PlMailTheme.spacing.tiny,
                ),
        )
    }
}

/**
 * Nothing to show, and why.
 *
 * The dated case is the one worth building: mail older than what the server synced is not
 * searchable at all, so "no results" is a true sentence that means the wrong thing. Naming the
 * oldest message the server actually holds turns a dead end into something the reader can act on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(
    state: SearchUiState,
    onRecentChosen: (String) -> Unit,
    onForgetRecent: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(PlMailTheme.spacing.xLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val reason = state.emptyReason) {
            is EmptyReason.OutsideSyncedRange -> {
                Text(
                    text = stringResource(R.string.search_empty_outside_window),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.search_oldest_held, reason.oldest.asDate()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PlMailTheme.colors.inkMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            EmptyReason.NoMatches ->
                Text(
                    text = stringResource(R.string.search_empty_no_matches),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )

            // Not asked yet, or still deciding. Either way the recents below are
            // the useful thing on screen; an apology for an unasked question is
            // not.
            EmptyReason.NotAsked,
            null -> Unit
        }

        if (state.recent.isNotEmpty()) {
            Text(
                text = stringResource(R.string.search_recent),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.recent.forEach { query ->
                    FilterChip(
                        selected = false,
                        onClick = { onRecentChosen(query) },
                        label = { Text(query) },
                        leadingIcon = {
                            Icon(Icons.Default.History, contentDescription = null)
                        },
                        trailingIcon = {
                            IconButton(onClick = { onForgetRecent(query) }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription =
                                        stringResource(R.string.search_forget, query),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** A date as the reader writes one, in their zone. */
private fun Instant.asDate(): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(this)
