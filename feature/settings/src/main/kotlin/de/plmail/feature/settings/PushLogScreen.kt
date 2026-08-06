package de.plmail.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.data.PushChoice
import de.plmail.core.data.PushDelivery
import de.plmail.core.data.ReceivedPush
import de.plmail.core.designsystem.PaneTone
import de.plmail.core.designsystem.PlMailTheme

/**
 * Every push this device has actually received, newest first.
 *
 * **This is the receiving half of a pair.** The server keeps its own record of what it dispatched;
 * this keeps what landed. Neither is interesting alone — a server log full of sends proves nothing
 * about a phone, and a quiet phone proves nothing about a server — and together they turn "push
 * does not work" into a line somebody can point at. So the columns are chosen to be *comparable*
 * with that log: when, by what route, of what type, and for which account.
 *
 * **Which route is the column that earns the screen.** A user watching mail arrive instantly while
 * the app is open, and never while it is closed, is looking at a stream doing the work of a
 * subscription that was never verified — and every other view in the app draws those two
 * identically. The badge is what separates them.
 *
 * **No mail content, and not merely because JMAP does not push any.** What arrives is a map of
 * account id to the object types whose state token moved, so that is what is drawn. A subject line
 * here would be a copy of somebody's mail sitting in a diagnostics list for the next two hundred
 * messages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushLogScreen(onBack: () -> Unit, viewModel: PushLogViewModel = hiltViewModel()) {
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
                title = { Text(stringResource(R.string.push_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::clear) {
                        Text(stringResource(R.string.push_log_clear))
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding =
                androidx.compose.foundation.layout.PaddingValues(
                    horizontal = PlMailTheme.spacing.gutter,
                    vertical = PlMailTheme.spacing.medium,
                ),
            verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
        ) {
            item { Subscription(state) }

            if (state.entries.isEmpty()) {
                item {
                    // Not phrased as an error. An empty log on a freshly
                    // switched transport is the ordinary first state, and it is
                    // also exactly what somebody sees a second after clearing
                    // it on purpose.
                    Note(text = stringResource(R.string.push_log_empty), tone = PaneTone.INFO)
                }
            }

            items(items = state.entries, key = { "${it.at}-${it.transport}-${it.type}" }) { entry ->
                Entry(entry)
            }
        }
    }
}

/**
 * The registration these entries are evidence about.
 *
 * At the top rather than on the previous screen, because the log is read *against* it: two hundred
 * lines all saying `stream` mean one thing beside a verified FCM subscription and something else
 * beside one that has been awaiting verification since Tuesday.
 */
@Composable
private fun Subscription(state: PushLogState) {
    val transports = state.transports

    Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny)) {
        Fact(
            label = stringResource(R.string.push_status_active),
            value =
                transports.active?.let { stringResource(it.label) }
                    ?: stringResource(R.string.push_status_none),
        )

        val verifiedAt = transports.push.verifiedAt

        Fact(
            label = stringResource(R.string.push_log_verified),
            value =
                when {
                    transports.choice == PushChoice.PULL ->
                        stringResource(R.string.push_log_not_applicable)
                    verifiedAt != null -> asAbsoluteTime(verifiedAt)
                    transports.push.isRegistered ->
                        stringResource(R.string.push_status_awaiting_short)
                    else -> stringResource(R.string.diagnostics_not_registered)
                },
        )

        Fact(
            label = stringResource(R.string.push_status_last),
            value =
                transports.push.lastMessageAt?.let { asAbsoluteTime(it) }
                    ?: stringResource(R.string.diagnostics_never),
        )
    }
}

@Composable
private fun Entry(entry: ReceivedPush) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = PlMailTheme.spacing.tiny),
        horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
        verticalAlignment = Alignment.Top,
    ) {
        TransportBadge(entry.delivery, entry.transport)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.hair),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
            ) {
                Text(
                    text = entry.type,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = PlMailTheme.colors.ink,
                    modifier = Modifier.weight(1f),
                )

                // Absolute, not "3 minutes ago". This list is read beside a
                // server log printing timestamps, and a relative one cannot be
                // lined up against anything.
                Text(
                    text = asAbsoluteTime(entry.at),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = PlMailTheme.colors.inkFaint,
                )
            }

            if (entry.changed.isEmpty()) {
                // A verification carries no `changed` map at all, which is
                // correct rather than missing -- so nothing is drawn rather
                // than an empty list that reads as a delivery that changed
                // nothing.
            } else {
                entry.changed.toSortedMap().forEach { (accountId, types) ->
                    Text(
                        text =
                            stringResource(
                                R.string.push_log_changed,
                                accountId,
                                types.joinToString(", "),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = PlMailTheme.colors.inkMuted,
                    )
                }
            }

            entry.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = PlMailTheme.colors.inkFaint,
                )
            }
        }
    }
}

/**
 * Which way in, as a colour and a word.
 *
 * A word as well as a colour, and the word is the wire name rather than a friendly one: it is the
 * same string the server's own log prints, so the two can be matched without a translation table in
 * between. Colour alone would also fail anyone who cannot distinguish them, on the one screen whose
 * entire purpose is telling four things apart.
 */
@Composable
private fun TransportBadge(delivery: PushDelivery?, wire: String) {
    val colours = PlMailTheme.colors

    val tint: Color =
        when (delivery) {
            PushDelivery.FCM -> colours.info
            PushDelivery.UNIFIEDPUSH,
            PushDelivery.WEBPUSH -> colours.accent
            // Deliberately the quiet one. A stream delivery is the app doing
            // the work itself, which is worth seeing and is not the thing the
            // user configured push for.
            PushDelivery.STREAM -> colours.inkMuted
            null -> colours.inkFaint
        }

    Text(
        text = wire,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = tint,
        modifier =
            Modifier.background(colours.sunken, RoundedCornerShape(BADGE_RADIUS))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private val BADGE_RADIUS = 4.dp
