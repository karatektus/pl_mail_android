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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.data.AccountHealth
import de.plmail.core.data.DiagnosticsReport
import de.plmail.core.designsystem.PaneTone
import de.plmail.core.designsystem.PlMailPane
import de.plmail.core.designsystem.PlMailTheme

/**
 * What the app knows about itself, for the person who has to fix it.
 *
 * The audience is the reason this screen exists and the reason it looks like this. plMail's users
 * run their own server, so when mail stops arriving they are simultaneously the person waiting for
 * it and the person who has to work out why — and until now the app could only tell them that
 * nothing was wrong. "The subscription was never verified", "the distributor was uninstalled", "the
 * credential expired" and "the box is off" all presented identically: a quiet inbox.
 *
 * Three rules follow, and they are why this does not read like the rest of the app.
 *
 * - **Say what happened, not how it feels.** Timestamps and the server's own error strings, not
 *   "Something went wrong". Somebody is going to paste one of these into a search box or grep a log
 *   for it, and a translated reassurance is unsearchable.
 * - **Distinguish what is known from what is believed.** Everything here is recorded state except
 *   the verification check, which costs a round trip and therefore happens when asked.
 * - **Never make requests just because it was opened.** The first thing somebody does when their
 *   server is struggling must not be to add traffic to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val report by viewModel.state.collectAsStateWithLifecycle()

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
                title = { Text(stringResource(R.string.diagnostics_title)) },
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
            Server(report)
            Accounts(report.accounts)
            Push(report, onRetry = viewModel::retryPush)
            CheckNow(report, onCheck = viewModel::checkNow)
        }
    }
}

@Composable
private fun Server(report: DiagnosticsReport) {
    Section(stringResource(R.string.diagnostics_server)) {
        // The address, verbatim. It is the first thing to check and the thing
        // most likely to be wrong after somebody moves their reverse proxy --
        // and the app has never shown it back since onboarding.
        Fact(
            label = stringResource(R.string.diagnostics_address),
            value = report.server ?: stringResource(R.string.diagnostics_not_connected),
            isMonospace = true,
        )
    }
}

@Composable
private fun Accounts(accounts: List<AccountHealth>) {
    Section(stringResource(R.string.diagnostics_accounts)) {
        if (accounts.isEmpty()) {
            Text(
                text = stringResource(R.string.diagnostics_no_accounts),
                style = MaterialTheme.typography.bodyMedium,
                color = PlMailTheme.colors.inkMuted,
            )
            return@Section
        }

        accounts.forEach { account ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = PlMailTheme.spacing.small),
                verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny),
            ) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = PlMailTheme.colors.ink,
                )

                Fact(
                    label = stringResource(R.string.diagnostics_last_sync),
                    // "Never" rather than a blank or a zero epoch. An account
                    // that has genuinely never synced is a different problem
                    // from one that synced this morning and has failed since,
                    // and the old code could not tell them apart because
                    // recording a failure also erased the timestamp.
                    value =
                        account.lastSyncedAt?.let { asAbsoluteTime(it) }
                            ?: stringResource(R.string.diagnostics_never),
                )

                if (!account.hasSyncCursor) {
                    // Deliberately not phrased as a fault. No cursor means the
                    // next refresh re-pages instead of syncing incrementally,
                    // which is the ordinary consequence of being away long
                    // enough for the server's change log to move past us.
                    Note(
                        text = stringResource(R.string.diagnostics_no_cursor),
                        tone = PaneTone.INFO,
                    )
                }

                account.lastError?.let { error ->
                    Note(
                        text = stringResource(R.string.diagnostics_last_error, error),
                        tone = PaneTone.DANGER,
                        isMonospace = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun Push(report: DiagnosticsReport, onRetry: () -> Unit) {
    val push = report.push

    Section(stringResource(R.string.diagnostics_push)) {
        Fact(
            label = stringResource(R.string.diagnostics_distributor),
            // The package name, not a friendly label. `io.heckel.ntfy` is what
            // the user will see in Android's own app settings and what they can
            // search for; a prettified name is a second vocabulary.
            value = report.distributor ?: stringResource(R.string.diagnostics_none),
            isMonospace = report.distributor != null,
        )

        if (report.installedDistributors.isEmpty()) {
            Note(
                text = stringResource(R.string.diagnostics_no_distributor),
                tone = PaneTone.WARNING,
            )
        } else if (report.installedDistributors.size > 1 && report.distributor == null) {
            // Several installed and none chosen is a real state and a silent
            // one: PushSetup deliberately refuses to pick, because registering
            // with an arbitrary distributor decides something that is the
            // user's to decide.
            Note(
                text =
                    stringResource(
                        R.string.diagnostics_several_distributors,
                        report.installedDistributors.joinToString(", "),
                    ),
                tone = PaneTone.WARNING,
            )
        }

        Fact(
            label = stringResource(R.string.diagnostics_registered),
            value =
                push.registeredAt?.let { asAbsoluteTime(it) }
                    ?: stringResource(R.string.diagnostics_not_registered),
        )

        // The most valuable line here, because it is the only one that is
        // evidence rather than belief: a push physically arrived on this
        // device. Everything above it is the app describing its own intentions.
        Fact(
            label = stringResource(R.string.diagnostics_last_push),
            value =
                push.lastMessageAt?.let { asAbsoluteTime(it) }
                    ?: stringResource(R.string.diagnostics_never),
        )

        when (report.pushVerified) {
            // Null is "not asked", which the button below fixes -- and it must
            // not be drawn as "no", because an unverified subscription and an
            // unasked question look nothing alike to whoever is debugging.
            null -> Unit
            true ->
                Note(text = stringResource(R.string.diagnostics_verified), tone = PaneTone.SUNKEN)
            false ->
                Note(
                    text = stringResource(R.string.diagnostics_unverified),
                    tone = PaneTone.DANGER,
                )
        }

        push.lastError?.let { error ->
            Note(
                text = stringResource(R.string.diagnostics_push_error, error),
                tone = PaneTone.DANGER,
                isMonospace = true,
            )
        }

        if (!push.isRegistered) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.diagnostics_enable_push)) }
        }
    }
}

@Composable
private fun CheckNow(report: DiagnosticsReport, onCheck: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
        ) {
            TextButton(onClick = onCheck, enabled = !report.isChecking) {
                Text(stringResource(R.string.diagnostics_check))
            }

            if (report.isChecking) {
                CircularProgressIndicator(
                    color = PlMailTheme.colors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Text(
            text = stringResource(R.string.diagnostics_check_explains),
            style = MaterialTheme.typography.bodySmall,
            color = PlMailTheme.colors.inkMuted,
        )

        report.checkError?.let { error ->
            Note(
                text = stringResource(R.string.diagnostics_check_failed, error),
                tone = PaneTone.DANGER,
                isMonospace = true,
            )
        }
    }
}

/** A heading and the facts under it. Whitespace separates sections, not a card around each. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = PlMailTheme.colors.inkMuted,
        )

        Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny)) { content() }
    }
}

/**
 * One labelled value.
 *
 * The label is metadata and the value is the fact, so they sit on the two ink steps that mean
 * exactly that. Monospace where the value is something that gets copied or compared character by
 * character — an address, an error, a package name — because a proportional font makes `l` and `1`
 * the same shape in the one place that matters.
 */
@Composable
private fun Fact(label: String, value: String, isMonospace: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = PlMailTheme.colors.inkFaint,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (isMonospace) FontFamily.Monospace else null,
            color = PlMailTheme.colors.ink,
        )
    }
}

/** A short block of prose that carries a tone — something is wrong, or worth knowing. */
@Composable
private fun Note(text: String, tone: PaneTone, isMonospace: Boolean = false) {
    PlMailPane(modifier = Modifier.fillMaxWidth(), tone = tone) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (isMonospace) FontFamily.Monospace else null,
            color = PlMailTheme.colors.inkSoft,
            modifier = Modifier.padding(PlMailTheme.spacing.small),
        )
    }
}
