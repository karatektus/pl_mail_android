package de.plmail.feature.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.plmail.core.data.EncryptionBlock
import de.plmail.core.data.EncryptionPlan
import de.plmail.core.designsystem.PlMailTheme

/**
 * The lock in the app bar (#9): closed while the message will be encrypted, open while it will not.
 * A toggle, announced as one, the web app's `encryptButton` with its `aria-pressed`.
 */
@Composable
internal fun EncryptButton(isEncrypting: Boolean, onClick: () -> Unit) {
    val state =
        stringResource(
            if (isEncrypting) R.string.compose_crypto_on else R.string.compose_crypto_off
        )

    IconButton(
        onClick = onClick,
        modifier =
            Modifier.semantics {
                role = Role.Switch
                stateDescription = state
            },
    ) {
        Icon(
            imageVector = if (isEncrypting) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = stringResource(R.string.compose_encrypt),
            tint = if (isEncrypting) PlMailTheme.colors.accent else PlMailTheme.colors.inkMuted,
        )
    }
}

/**
 * One line under the recipients saying what will happen to the message, in the web app's words
 * (`compose-crypto-hint`): that it will be encrypted, or for whom it cannot be, or what stops it.
 * Nothing while encryption does not apply, nor for plain mail nobody could have encrypted.
 */
@Composable
internal fun EncryptionHint(plan: EncryptionPlan) {
    if (!plan.available) return

    val blocked = plan.blocked
    val (text, isWarning) =
        when {
            blocked != null -> blocked.text() to true
            plan.encrypt && !plan.pending ->
                stringResource(R.string.compose_crypto_encrypted_hint) to false
            plan.unencryptedFor.isNotEmpty() ->
                stringResource(R.string.compose_crypto_partial, plan.unencryptedFor.joined()) to
                    true
            else -> return
        }

    val color = if (isWarning) MaterialTheme.colorScheme.error else PlMailTheme.colors.inkSoft

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = PlMailTheme.spacing.gutter,
                    vertical = PlMailTheme.spacing.small,
                ),
        horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isWarning) Icons.Filled.Warning else Icons.Filled.Lock,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
internal fun EncryptionBlock.text(): String =
    when (this) {
        is EncryptionBlock.Missing ->
            stringResource(R.string.compose_crypto_missing, addresses.joined())
        is EncryptionBlock.Partial ->
            stringResource(R.string.compose_crypto_partial_blocked, addresses.joined())
        EncryptionBlock.NoOwnKey -> stringResource(R.string.compose_crypto_no_own_key)
        EncryptionBlock.NoKeysHere -> stringResource(R.string.compose_crypto_no_keys_here)
        EncryptionBlock.KeysLocked -> stringResource(R.string.compose_crypto_keys_locked)
    }

private fun List<String>.joined(): String = joinToString(", ")

/** The lock in front of a recipient's name. */
@Composable
internal fun RecipientLockIcon(lock: RecipientLock) {
    when (lock) {
        RecipientLock.HAS_KEY ->
            Icon(
                Icons.Filled.Lock,
                contentDescription = stringResource(R.string.compose_crypto_has_key),
                tint = PlMailTheme.colors.accent,
                modifier = Modifier.size(16.dp),
            )
        RecipientLock.KEY_CHANGED ->
            Icon(
                Icons.Filled.Warning,
                contentDescription = stringResource(R.string.compose_crypto_key_changed),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
    }
}
