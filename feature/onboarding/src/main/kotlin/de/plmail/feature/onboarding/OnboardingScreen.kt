package de.plmail.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.protocol.JmapError

/**
 * The first screen anyone sees.
 *
 * Two routes in, and the QR one is the better path: 71 characters of base16 typed onto a phone
 * keyboard is the worst moment in onboarding. The paste field stays because a code on another
 * screen is not always scannable, and because it is the only route that works when the camera is
 * refused.
 *
 * @param onFinished called once a connection has been saved and verified.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    pendingLink: String? = null,
    onLinkHandled: () -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var scanning by rememberSaveable { mutableStateOf(false) }

    // Keyed on the URI so a second tapped link during the same session is acted
    // on, and consumed immediately so a rotation does not redeem the code a
    // second time -- it is single-use, and the second attempt would fail with
    // the same message as an expired one.
    LaunchedEffect(pendingLink) {
        pendingLink?.let {
            viewModel.invitationReceived(it)
            onLinkHandled()
        }
    }

    // Reported through an effect rather than from the button's onClick: the
    // save is asynchronous, so leaving from the click would navigate away
    // before the write completed and, on a slow device, before it succeeded.
    LaunchedEffect(state.step) {
        if (state.step is OnboardingStep.Done) onFinished()
    }

    if (scanning) {
        QrScannerScreen(
            onScanned = {
                // Dismissed first: the scan starts a connection, and leaving the
                // camera bound behind a dialog keeps a preview running through
                // the whole handshake.
                scanning = false
                viewModel.invitationScanned(it)
            },
            onCancel = { scanning = false },
        )

        return
    }

    OnboardingScreen(
        state = state,
        onScan = { scanning = true },
        onAddressChanged = viewModel::addressChanged,
        onAppPasswordChanged = viewModel::appPasswordChanged,
        onConnect = viewModel::connect,
        onAcceptKey = viewModel::acceptKey,
        onRejectKey = viewModel::rejectKey,
        onConfirm = viewModel::confirm,
        onCancelConfirmation = viewModel::cancelConfirmation,
    )
}

/** The stateless half, so it can be previewed and screenshot-tested without a graph. */
@Composable
internal fun OnboardingScreen(
    state: OnboardingUiState,
    onScan: () -> Unit,
    onAddressChanged: (String) -> Unit,
    onAppPasswordChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onAcceptKey: () -> Unit,
    onRejectKey: () -> Unit,
    onConfirm: () -> Unit,
    onCancelConfirmation: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.onboarding_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.address,
                onValueChange = onAddressChanged,
                enabled = !state.isBusy,
                singleLine = true,
                label = { Text(stringResource(R.string.onboarding_address_label)) },
                placeholder = { Text(stringResource(R.string.onboarding_address_hint)) },
                isError = state.addressMessage() != null,
                supportingText = state.addressMessage()?.let { { Text(it) } },
                keyboardOptions =
                    KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.warnsAboutCleartext) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.onboarding_cleartext_warning),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            OutlinedTextField(
                value = state.appPassword,
                onValueChange = onAppPasswordChanged,
                enabled = !state.isBusy,
                singleLine = true,
                label = { Text(stringResource(R.string.onboarding_password_label)) },
                placeholder = { Text(stringResource(R.string.onboarding_password_hint)) },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )

            state.failureMessage()?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // The scan is offered above the fields rather than below them,
            // because it is the route that avoids typing either of them.
            Button(onClick = onScan, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_scan))
            }

            Button(
                onClick = onConnect,
                enabled = state.canConnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_connect))
            }

            if (state.isBusy) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.onboarding_connecting),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    when (val step = state.step) {
        is OnboardingStep.ConfirmKey ->
            TrustDialog(step = step, onAccept = onAcceptKey, onReject = onRejectKey)

        is OnboardingStep.Confirm ->
            ConfirmDialog(step = step, onSave = onConfirm, onCancel = onCancelConfirmation)

        OnboardingStep.Entry,
        OnboardingStep.Connecting,
        OnboardingStep.Done -> Unit
    }
}

/**
 * The trust prompt.
 *
 * The fingerprint is shown in `openssl`-comparable pairs, in a monospace style, because the only
 * way this question can be answered honestly is by reading it against the server — and a
 * fingerprint that wraps differently in each place is one nobody compares.
 */
@Composable
private fun TrustDialog(
    step: OnboardingStep.ConfirmKey,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(R.string.onboarding_trust_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.onboarding_trust_body, step.host))
                Text(
                    text = step.fingerprint.display,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.onboarding_trust_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text(stringResource(R.string.onboarding_trust_reject))
            }
        },
    )
}

/**
 * What was reached, before anything is saved.
 *
 * The account list is the point. "Was that `nas.local` or the other `nas.local`" is a real question
 * for someone with two boxes, and the mailboxes this credential reaches are the only thing on
 * screen that answers it.
 */
@Composable
private fun ConfirmDialog(step: OnboardingStep.Confirm, onSave: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.onboarding_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        R.string.onboarding_confirm_signed_in_as,
                        step.server.username,
                    )
                )
                Text(
                    text = stringResource(R.string.onboarding_confirm_accounts),
                    style = MaterialTheme.typography.labelLarge,
                )
                step.server.accountNames.forEach { name ->
                    Text(text = name, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text(stringResource(R.string.onboarding_confirm_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.onboarding_confirm_cancel))
            }
        },
    )
}

/**
 * What to say under the address field, or null while there is nothing to say.
 *
 * Blank is deliberately silent: an empty field is not a mistake, and marking it red before anyone
 * has typed anything is how a form greets someone with an accusation.
 */
@Composable
private fun OnboardingUiState.addressMessage(): String? =
    when (val problem = addressProblem) {
        null,
        ParsedAddress.Blank,
        is ParsedAddress.Valid -> null

        is ParsedAddress.UnsupportedScheme ->
            stringResource(R.string.onboarding_address_scheme, problem.scheme)

        is ParsedAddress.Malformed -> stringResource(R.string.onboarding_address_malformed)

        ParsedAddress.CredentialsInAddress ->
            stringResource(R.string.onboarding_address_credentials)
    }

/**
 * The failure, in words someone can act on.
 *
 * Only the cases with an action get their own wording. The rest fall through to whatever the server
 * said, which at least names the problem — inventing a friendlier sentence for an error we do not
 * understand would replace information with reassurance.
 */
@Composable
private fun OnboardingUiState.failureMessage(): String? =
    when (val error = failure) {
        null -> null
        is JmapError.NotAuthenticated -> stringResource(R.string.onboarding_error_unauthenticated)
        is JmapError.Unreachable ->
            stringResource(R.string.onboarding_error_unreachable, error.host)
        is JmapError.CertificateChanged ->
            stringResource(R.string.onboarding_error_certificate_changed, error.host)
        is JmapError.MalformedResponse -> stringResource(R.string.onboarding_error_malformed)
        else -> error.message
    }
