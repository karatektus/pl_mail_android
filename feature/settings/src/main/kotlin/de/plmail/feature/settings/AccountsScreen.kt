package de.plmail.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.data.AccountSummary
import de.plmail.core.designsystem.PaneTone
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailPane
import de.plmail.core.designsystem.PlMailTheme

/**
 * The mailboxes behind one credential: what order they are in, how much of each is here, and which
 * of them may interrupt.
 *
 * Three things live together on this screen because they are three answers to the same question —
 * "what is this account to me" — and splitting them across three screens would mean navigating
 * between them to make one decision.
 *
 * **The order is the app's, not the server's.** It decides which mailbox the composer opens on and
 * which one a new label is created in, so it is a real setting rather than a cosmetic one. It is
 * stored in DataStore rather than on the account row, because the account row is a cache that gets
 * dropped and rebuilt on any schema change and an ordering nobody can reconstruct would go with it.
 *
 * **The window is about this device.** The app pages backwards as the user scrolls, so what is
 * searchable is what has been paged — which is why search's empty state has to talk about a sync
 * window at all, and why the number was previously visible nowhere. The server's own boundary needs
 * a request, so it sits behind a button that says it makes one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(onBack: () -> Unit, viewModel: AccountsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PlMailTheme.colors.surface,
        topBar = {
            TopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = PlMailTheme.colors.surface,
                        scrolledContainerColor = PlMailTheme.colors.surface,
                        titleContentColor = PlMailTheme.colors.ink,
                        navigationIconContentColor = PlMailTheme.colors.inkSoft,
                        actionIconContentColor = PlMailTheme.colors.inkSoft,
                    ),
                title = { Text(stringResource(R.string.accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = PlMailTheme.spacing.gutter,
                        vertical = PlMailTheme.spacing.medium,
                    ),
            verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.large),
        ) {
            if (state.accounts.isEmpty()) {
                Text(
                    text = stringResource(R.string.accounts_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PlMailTheme.colors.inkMuted,
                )
                return@Column
            }

            Section(stringResource(R.string.accounts_order)) {
                // Said once, above the arrows, rather than repeated per row.
                // "Move up" on its own does not explain that the top account is
                // where a new message and a new label go, which is the only
                // reason the order is worth arranging.
                Text(
                    text = stringResource(R.string.accounts_order_explains),
                    style = MaterialTheme.typography.bodySmall,
                    color = PlMailTheme.colors.inkMuted,
                )
            }

            state.accounts.forEachIndexed { index, account ->
                AccountRow(
                    account = account,
                    isFirst = index == 0,
                    isLast = index == state.accounts.lastIndex,
                    onMove = { by -> viewModel.move(account.accountKey, by) },
                    onNotifying = { viewModel.setNotifying(account.accountKey, it) },
                    hasAsked = state.hasAsked,
                )

                if (index != state.accounts.lastIndex) PlMailDivider()
            }

            AskServer(state, onAsk = viewModel::askServer)
        }
    }
}

@Composable
private fun AccountRow(
    account: AccountSummary,
    isFirst: Boolean,
    isLast: Boolean,
    onMove: (Int) -> Unit,
    onNotifying: (Boolean) -> Unit,
    hasAsked: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = PlMailTheme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = PlMailTheme.colors.ink,
                )
                Text(
                    text = account.server,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = PlMailTheme.colors.inkFaint,
                )
            }

            // Arrows rather than drag-and-drop, and that is an accessibility
            // decision before it is a simplicity one. A long-press drag has no
            // TalkBack equivalent unless a custom action is written for it, and
            // the custom action is *these two buttons*. Building the accessible
            // version as the only version means there is one code path and it
            // is the one that gets tested.
            IconButton(onClick = { onMove(-1) }, enabled = !isFirst) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.accounts_move_up, account.name),
                    tint = if (isFirst) PlMailTheme.colors.inkFaint else PlMailTheme.colors.inkSoft,
                )
            }

            IconButton(onClick = { onMove(1) }, enabled = !isLast) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.accounts_move_down, account.name),
                    tint = if (isLast) PlMailTheme.colors.inkFaint else PlMailTheme.colors.inkSoft,
                )
            }
        }

        // The count and the boundary in one sentence rather than two labelled
        // facts: "412 messages, back to 3 March" is the shape of the answer
        // somebody wants when they cannot find an old mail, and two separate
        // rows make them do the joining.
        Text(
            text =
                if (account.cachedMessages == 0) {
                    stringResource(R.string.accounts_window_empty)
                } else {
                    val messages =
                        pluralStringResource(
                            R.plurals.accounts_window_messages,
                            account.cachedMessages,
                            account.cachedMessages,
                        )

                    account.oldestCachedAt?.let {
                        stringResource(R.string.accounts_window, messages, asAbsoluteDate(it))
                    } ?: messages
                },
            style = MaterialTheme.typography.bodyMedium,
            color = PlMailTheme.colors.inkSoft,
        )

        if (hasAsked) {
            Text(
                text =
                    account.oldestOnServer?.let {
                        stringResource(R.string.accounts_server_window, asAbsoluteDate(it))
                    } ?: stringResource(R.string.accounts_server_window_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = PlMailTheme.colors.inkMuted,
            )
        }

        // Read out of composition, because a semantics block is not a composable
        // scope and cannot reach a string resource from inside itself.
        val notifyLabel = stringResource(R.string.accounts_notify_a11y, account.name)

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = PlMailTheme.spacing.tiny),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.accounts_notify),
                style = MaterialTheme.typography.bodyMedium,
                color = PlMailTheme.colors.ink,
                // The switch carries the whole control's semantics; leaving the
                // label focusable too makes TalkBack read the same setting twice
                // and offers a swipe stop that does nothing. The switch's own
                // description names the account, which the visible label cannot
                // — every row says "Notify me about new mail", so without it a
                // screen reader hears the same sentence once per mailbox with
                // nothing to tell them apart.
                modifier = Modifier.weight(1f).clearAndSetSemantics {},
            )

            Switch(
                checked = account.isNotifying,
                onCheckedChange = onNotifying,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = PlMailTheme.colors.onAccent,
                        checkedTrackColor = PlMailTheme.colors.accent,
                        uncheckedTrackColor = PlMailTheme.colors.sunken,
                        uncheckedBorderColor = PlMailTheme.colors.line,
                    ),
                modifier = Modifier.semantics { contentDescription = notifyLabel },
            )
        }

        if (!account.isNotifying) {
            // Said explicitly, because a muted account still syncs and still
            // shows a badge on the list — and "notifications off" is otherwise
            // indistinguishable from push being broken, which is the failure
            // this product can least afford to make ambiguous.
            Note(
                text = stringResource(R.string.accounts_muted_explains),
                tone = PaneTone.INFO,
            )
        }
    }
}

@Composable
private fun AskServer(state: AccountsState, onAsk: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
        ) {
            TextButton(onClick = onAsk, enabled = !state.isAsking) {
                Text(stringResource(R.string.accounts_ask_server))
            }

            if (state.isAsking) {
                CircularProgressIndicator(
                    color = PlMailTheme.colors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Text(
            text = stringResource(R.string.accounts_ask_server_explains),
            style = MaterialTheme.typography.bodySmall,
            color = PlMailTheme.colors.inkMuted,
        )

        state.askError?.let {
            Note(text = stringResource(R.string.accounts_ask_failed, it), tone = PaneTone.DANGER)
        }
    }
}

/** A short block of prose carrying a tone. Local twin of the diagnostics screen's, same shape. */
@Composable
private fun Note(text: String, tone: PaneTone) {
    PlMailPane(modifier = Modifier.fillMaxWidth(), tone = tone) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = PlMailTheme.colors.inkSoft,
            modifier = Modifier.padding(PlMailTheme.spacing.small),
        )
    }
}
