package de.plmail.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.data.PushChoice
import de.plmail.core.data.PushOption
import de.plmail.core.data.PushTransportState
import de.plmail.core.data.PushUnavailable
import de.plmail.core.designsystem.PaneTone
import de.plmail.core.designsystem.PlMailTheme

/**
 * How this device is told about new mail, and what it costs.
 *
 * Three arrangements rather than a switch, because they are not degrees of the same thing. Web Push
 * needs a distributor app the user installs and tells nobody but that distributor's server;
 * Firebase needs a Firebase project the *server's administrator* configured and puts Google in the
 * path; pull needs nothing, tells nobody, and trades latency for both. Someone who self-hosts their
 * mail has an opinion about that trade, and the screen's job is to make it visible rather than to
 * pick for them.
 *
 * **An option is offered only when it is real.** Not greyed out with a shrug — every unavailable
 * row says which of the several different reasons applies, because they send the reader to
 * different places: their phone, their server's admin page, or their server's version number. An
 * option that cannot be explained is a bug in this screen, which is why [PushOption.reason] is
 * non-null whenever availability is false.
 *
 * **The switch is not finished when the row is tapped.** Registering a subscription does not make
 * it deliver: the server sends a verification code to the address just registered and delivers
 * nothing until the app echoes it back, which arrives as a push. That window is drawn rather than
 * hidden, because it is also where a broken transport sits permanently, and a screen that claimed
 * success at the create would be claiming it for a state that receives no mail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushScreen(
    onBack: () -> Unit,
    onLog: () -> Unit,
    viewModel: PushViewModel = hiltViewModel(),
) {
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
                title = { Text(stringResource(R.string.push_title)) },
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
            Transports(state, onChoose = viewModel::choose)
            Status(state)
            LogEntry(onLog)
        }
    }
}

@Composable
private fun Transports(state: PushTransportState, onChoose: (PushChoice) -> Unit) {
    Section(stringResource(R.string.push_transport)) {
        Text(
            text = stringResource(R.string.push_transport_explains),
            style = MaterialTheme.typography.bodySmall,
            color = PlMailTheme.colors.inkMuted,
        )

        Column(modifier = Modifier.selectableGroup()) {
            state.options.forEach { option ->
                TransportRow(
                    option = option,
                    isSelected = option.choice == state.choice,
                    isEnabled = option.isAvailable && !state.isSwitching,
                    onChoose = { onChoose(option.choice) },
                )
            }
        }

        if (state.isSwitching) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
            ) {
                CircularProgressIndicator(
                    color = PlMailTheme.colors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.push_switching),
                    style = MaterialTheme.typography.bodySmall,
                    color = PlMailTheme.colors.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun TransportRow(
    option: PushOption,
    isSelected: Boolean,
    isEnabled: Boolean,
    onChoose: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .selectable(
                    selected = isSelected,
                    enabled = isEnabled,
                    role = Role.RadioButton,
                    onClick = onChoose,
                )
                .padding(vertical = PlMailTheme.spacing.small),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
    ) {
        // null onClick: the whole row is the target, and a button that also
        // handled the tap would announce itself twice to TalkBack.
        RadioButton(selected = isSelected, onClick = null, enabled = isEnabled)

        Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny)) {
            Text(
                text = stringResource(option.choice.label),
                style = MaterialTheme.typography.bodyLarge,
                color = if (isEnabled) PlMailTheme.colors.ink else PlMailTheme.colors.inkFaint,
            )

            Text(
                text = stringResource(option.choice.description),
                style = MaterialTheme.typography.bodySmall,
                color = PlMailTheme.colors.inkMuted,
            )

            // The whole point of the row being drawn at all when it cannot be
            // picked. Each reason sends the reader somewhere different, and
            // "unavailable" sends them nowhere.
            option.reason?.let { reason ->
                Text(
                    text = stringResource(reason.explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = PlMailTheme.colors.inkSoft,
                )
            }

            // The transport's own words, untranslated, where it had any. Google
            // distinguishes "Play services missing" from "Play services needs
            // updating" and the second is something the user can act on.
            option.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = PlMailTheme.colors.inkFaint,
                )
            }
        }
    }
}

/** Where the chosen transport has actually got to, which is not always where it was sent. */
@Composable
private fun Status(state: PushTransportState) {
    Section(stringResource(R.string.push_status)) {
        state.fellBack?.let { reason ->
            // The one failure that happens to a device doing nothing at all:
            // the server's administrator switched Firebase off while this phone
            // was registered against it. Loud, because the alternative is a
            // phone that quietly stops ringing.
            Note(
                text = stringResource(R.string.push_fell_back, stringResource(reason.explanation)),
                tone = PaneTone.DANGER,
            )
        }

        Fact(
            label = stringResource(R.string.push_status_active),
            value =
                state.active?.let { stringResource(it.label) }
                    ?: stringResource(R.string.push_status_none),
        )

        when {
            state.choice == PushChoice.PULL ->
                Note(text = stringResource(R.string.push_status_pull), tone = PaneTone.SUNKEN)

            state.isAwaitingVerification ->
                // Registered, silent, and legitimately so for a few seconds.
                // Named rather than hidden because it is also where a broken
                // transport sits forever, and the difference is only visible if
                // the screen says which state it is in.
                Note(
                    text = stringResource(R.string.push_status_awaiting),
                    tone = PaneTone.WARNING,
                )

            state.push.isLive ->
                Note(text = stringResource(R.string.push_status_live), tone = PaneTone.SUNKEN)

            else ->
                Note(
                    text = stringResource(R.string.push_status_not_registered),
                    tone = PaneTone.WARNING,
                )
        }

        state.push.lastMessageAt?.let { at ->
            Fact(
                label = stringResource(R.string.push_status_last),
                value =
                    state.push.lastMessageTransport?.let {
                        stringResource(R.string.diagnostics_last_push_via, asAbsoluteTime(at), it)
                    } ?: asAbsoluteTime(at),
            )
        }

        state.lastError?.let { error ->
            Note(
                text = stringResource(R.string.push_last_error, error),
                tone = PaneTone.DANGER,
                isMonospace = true,
            )
        }
    }
}

@Composable
private fun LogEntry(onLog: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small)) {
        TextButton(onClick = onLog) { Text(stringResource(R.string.push_log_open)) }

        Text(
            text = stringResource(R.string.push_log_explains),
            style = MaterialTheme.typography.bodySmall,
            color = PlMailTheme.colors.inkMuted,
        )
    }
}

/** The user-facing name of one arrangement. */
internal val PushChoice.label: Int
    get() =
        when (this) {
            PushChoice.WEB_PUSH -> R.string.push_transport_webpush
            PushChoice.FCM -> R.string.push_transport_fcm
            PushChoice.PULL -> R.string.push_transport_pull
        }

/** What picking it actually means, in one sentence, including who learns what. */
private val PushChoice.description: Int
    get() =
        when (this) {
            PushChoice.WEB_PUSH -> R.string.push_transport_webpush_explains
            PushChoice.FCM -> R.string.push_transport_fcm_explains
            PushChoice.PULL -> R.string.push_transport_pull_explains
        }

/**
 * Why an option cannot be picked, phrased as the thing to do about it.
 *
 * Eight strings rather than one, because they point at four different places: this phone, this
 * build, the server's admin page, and the server's version. A single "not available" would be
 * accurate and useless.
 */
private val PushUnavailable.explanation: Int
    get() =
        when (this) {
            PushUnavailable.SERVER_TOO_OLD -> R.string.push_reason_server_too_old
            PushUnavailable.SERVER_DISABLED -> R.string.push_reason_server_disabled
            PushUnavailable.SERVER_CONFIG_INCOMPLETE -> R.string.push_reason_server_config
            PushUnavailable.NOT_IN_THIS_BUILD -> R.string.push_reason_not_in_build
            PushUnavailable.NO_PLAY_SERVICES -> R.string.push_reason_no_play_services
            PushUnavailable.INIT_FAILED -> R.string.push_reason_init_failed
            PushUnavailable.NO_VAPID -> R.string.push_reason_no_vapid
            PushUnavailable.NO_DISTRIBUTOR -> R.string.push_reason_no_distributor
        }
