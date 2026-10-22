package de.plmail.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.data.AddressKeyStatus
import de.plmail.core.data.EncryptionStatus
import de.plmail.core.data.SmimeCertificateStatus
import de.plmail.core.designsystem.PlMailTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * End-to-end encryption on this device (#9).
 *
 * Phase 1 is about one thing: getting the person's keys here. So the screen says where the device
 * stands and offers the two ways in — pairing again from the web app with its keyring open, or the
 * password (or passphrase) typed once. Setting encryption up, making keys and publishing them stay
 * in the web app, where they already are; this screen says so rather than half-copying them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncryptionScreen(onBack: () -> Unit, viewModel: EncryptionViewModel = hiltViewModel()) {
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
                title = { Text(stringResource(R.string.encryption_title)) },
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
            when (val status = state.status) {
                null -> Working()
                EncryptionStatus.Unsupported -> Explains(R.string.encryption_unsupported)
                is EncryptionStatus.NotSetUp -> Explains(R.string.encryption_not_set_up)
                is EncryptionStatus.Unreachable -> Unreachable(onRetry = viewModel::refresh)
                is EncryptionStatus.Locked ->
                    Locked(
                        status = status,
                        state = state,
                        onSecret = viewModel::secretChanged,
                        onUnlock = viewModel::unlock,
                    )
                is EncryptionStatus.Ready ->
                    Ready(
                        status,
                        state = state,
                        onProtected = viewModel::setProtected,
                        onForget = viewModel::forget,
                    )
            }
        }
    }
}

@Composable
private fun Working() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.encryption_checking),
            style = MaterialTheme.typography.bodyMedium,
            color = PlMailTheme.colors.inkMuted,
        )
    }
}

@Composable
private fun Explains(text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = PlMailTheme.colors.ink,
    )
}

@Composable
private fun Unreachable(onRetry: () -> Unit) {
    Explains(R.string.encryption_unreachable)
    TextButton(onClick = onRetry) { Text(stringResource(R.string.encryption_retry)) }
}

/**
 * The keyring exists and this device cannot open it.
 *
 * Pairing is offered first because it asks for nothing: the browser hands the keys over. The
 * password field is the fallback, and its label says which secret it wants — an account that signs
 * in through single sign-on has an encryption passphrase and no plMail password at all, and asking
 * it for "your password" would have it type the identity provider's.
 */
@Composable
private fun Locked(
    status: EncryptionStatus.Locked,
    state: EncryptionUiState,
    onSecret: (String) -> Unit,
    onUnlock: () -> Unit,
) {
    Section(stringResource(R.string.encryption_locked_heading)) {
        Explains(
            if (status.hadKeys) R.string.encryption_locked_stale else R.string.encryption_locked
        )
    }

    Section(stringResource(R.string.encryption_pair_heading)) {
        Explains(R.string.encryption_pair_explains)
    }

    Section(
        stringResource(
            if (status.isPassphrase) R.string.encryption_unlock_passphrase_heading
            else R.string.encryption_unlock_password_heading
        )
    ) {
        Explains(
            if (status.isPassphrase) R.string.encryption_unlock_passphrase_explains
            else R.string.encryption_unlock_password_explains
        )

        OutlinedTextField(
            value = state.secret,
            onValueChange = onSecret,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    stringResource(
                        if (status.isPassphrase) R.string.encryption_passphrase
                        else R.string.encryption_password
                    )
                )
            },
            singleLine = true,
            enabled = !state.isWorking,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onUnlock() }),
            isError = state.problem != null,
            supportingText =
                state.problem?.let { problem ->
                    {
                        Text(
                            stringResource(
                                when (problem) {
                                    UnlockProblem.WrongSecret ->
                                        if (status.isPassphrase)
                                            R.string.encryption_wrong_passphrase
                                        else R.string.encryption_wrong_password
                                    UnlockProblem.NotSetUp -> R.string.encryption_not_set_up
                                    UnlockProblem.Unreachable -> R.string.encryption_unreachable
                                }
                            )
                        )
                    }
                },
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
        ) {
            Button(onClick = onUnlock, enabled = state.secret.isNotEmpty() && !state.isWorking) {
                Text(stringResource(R.string.encryption_unlock))
            }

            // Several hundred thousand PBKDF2 rounds: long enough to look stuck.
            if (state.isWorking) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun Ready(
    status: EncryptionStatus.Ready,
    state: EncryptionUiState,
    onProtected: (Boolean) -> Unit,
    onForget: () -> Unit,
) {
    val isWorking = state.isWorking
    var isConfirmingForget by remember { mutableStateOf(false) }

    Section(stringResource(R.string.encryption_ready_heading)) {
        Explains(R.string.encryption_ready)
    }

    Protection(
        isProtected = status.isProtected,
        canProtect = state.canProtect,
        isWorking = isWorking,
        wasRefused = state.protectRefused,
        onProtected = onProtected,
    )

    if (status.addresses.isNotEmpty()) {
        Section(stringResource(R.string.encryption_keys_heading)) {
            status.addresses.forEach { AddressKeyRow(it) }
        }
    }

    // S/MIME (#47): what the person imported in the web app, which is also
    // the only place to import, export or remove one.
    Section(stringResource(R.string.encryption_certificates_heading)) {
        if (status.certificates.isEmpty()) {
            Explains(R.string.encryption_certificates_empty)
        } else {
            status.certificates.forEach { CertificateRow(it) }
        }
    }

    TextButton(onClick = { isConfirmingForget = true }, enabled = !isWorking) {
        Text(stringResource(R.string.encryption_forget))
    }

    if (isConfirmingForget) {
        AlertDialog(
            onDismissRequest = { isConfirmingForget = false },
            title = { Text(stringResource(R.string.encryption_forget_title)) },
            text = { Text(stringResource(R.string.encryption_forget_explains)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        isConfirmingForget = false
                        onForget()
                    }
                ) {
                    Text(stringResource(R.string.encryption_forget_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { isConfirmingForget = false }) {
                    Text(stringResource(R.string.encryption_forget_cancel))
                }
            },
        )
    }
}

/**
 * Asking for the person before encrypted mail opens (#9, phase 5). Off unless they turn it on;
 * offered only on a device with a screen lock, because that is what the key is gated on.
 */
@Composable
private fun Protection(
    isProtected: Boolean,
    canProtect: Boolean,
    isWorking: Boolean,
    wasRefused: Boolean,
    onProtected: (Boolean) -> Unit,
) {
    val title = stringResource(R.string.encryption_protect)

    Section(stringResource(R.string.encryption_protect_heading)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).clearAndSetSemantics {}) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = PlMailTheme.colors.ink,
                )
                Text(
                    text =
                        stringResource(
                            if (canProtect || isProtected) R.string.encryption_protect_explains
                            else R.string.encryption_protect_unavailable
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = PlMailTheme.colors.inkMuted,
                )
            }

            Switch(
                checked = isProtected,
                onCheckedChange = onProtected,
                // Turning it off stays possible without a screen lock: the key
                // that needs one is gone with it, and the keys must not be
                // stranded behind it.
                enabled = !isWorking && (canProtect || isProtected),
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = PlMailTheme.colors.onAccent,
                        checkedTrackColor = PlMailTheme.colors.accent,
                        uncheckedTrackColor = PlMailTheme.colors.sunken,
                        uncheckedBorderColor = PlMailTheme.colors.line,
                    ),
                modifier = Modifier.semantics { contentDescription = title },
            )
        }

        if (wasRefused) {
            Text(
                text = stringResource(R.string.encryption_protect_refused),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CertificateRow(certificate: SmimeCertificateStatus) {
    val expired = certificate.notAfter?.isBefore(Instant.now()) == true

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = PlMailTheme.spacing.tiny)) {
        Text(
            text = certificate.addresses.joinToString(", "),
            style = MaterialTheme.typography.bodyLarge,
            color = PlMailTheme.colors.ink,
        )

        val details =
            listOfNotNull(
                certificate.issuer?.let {
                    stringResource(R.string.encryption_certificate_issuer, it)
                },
                certificate.notAfter?.let {
                    stringResource(
                        R.string.encryption_certificate_until,
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                            .format(it.atZone(ZoneId.systemDefault())),
                    )
                },
            )
        if (details.isNotEmpty()) {
            Text(
                text = details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = PlMailTheme.colors.inkMuted,
            )
        }

        Text(
            text =
                stringResource(
                    when {
                        certificate.opens == null -> R.string.encryption_key_locked
                        certificate.opens == false -> R.string.encryption_key_does_not_open
                        expired -> R.string.encryption_certificate_expired
                        !certificate.isActive -> R.string.encryption_certificate_retired
                        else -> R.string.encryption_key_unlocked
                    }
                ),
            style = MaterialTheme.typography.labelMedium,
            color =
                if (certificate.opens != false) PlMailTheme.colors.inkSoft
                else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun AddressKeyRow(key: AddressKeyStatus) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = PlMailTheme.spacing.tiny)) {
        Text(
            text = key.primaryAddress ?: key.addresses.firstOrNull().orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            color = PlMailTheme.colors.ink,
        )

        val others = key.addresses.filter { it != key.primaryAddress }
        if (others.isNotEmpty()) {
            Text(
                text = others.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = PlMailTheme.colors.inkMuted,
            )
        }

        // Grouped in fours, two lines of five, as GnuPG prints a fingerprint, so
        // it can be compared against the web app's character by character
        // without a lone last group wrapping onto a line of its own.
        Text(
            text =
                key.fingerprint.uppercase().chunked(4).chunked(5).joinToString("\n") {
                    it.joinToString(" ")
                },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = PlMailTheme.colors.inkMuted,
        )

        Text(
            text =
                stringResource(
                    when {
                        key.opens == null -> R.string.encryption_key_locked
                        key.opens == false -> R.string.encryption_key_does_not_open
                        !key.isActive -> R.string.encryption_key_retired
                        else -> R.string.encryption_key_unlocked
                    }
                ),
            style = MaterialTheme.typography.labelMedium,
            color =
                if (key.opens != false) PlMailTheme.colors.inkSoft
                else MaterialTheme.colorScheme.error,
        )
    }
}
