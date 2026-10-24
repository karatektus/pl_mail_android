package de.plmail.feature.mail.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.plmail.core.data.DecryptionProblem
import de.plmail.core.data.SignatureState
import de.plmail.core.data.SignatureVerdict
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.feature.mail.R

/**
 * What the reader knows about a message's end-to-end encryption and signature (#9).
 *
 * The web app's banner (`templates/mail/_crypto_banner.html.twig`), line for line, in its wording:
 * a lock for an encrypted message, a check for a signature that holds, a warning for one that does
 * not or whose key changed, and the signature mark for the states in between. Nothing at all for
 * plain mail — a banner on every message would teach people to stop reading it.
 */
@Composable
internal fun CryptoBanner(message: ReaderMessage, onRetry: () -> Unit) {
    val email = message.email
    val lines = mutableListOf<@Composable () -> Unit>()

    when (val encryption = message.encryption) {
        MessageEncryption.Decrypting -> lines += {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Line(
                        text =
                            stringResource(
                                if (email.isEncrypted) R.string.crypto_decrypting
                                else R.string.crypto_signed_pending
                            ),
                        tone = Tone.QUIET,
                    )
                }
            }
        is MessageEncryption.Opened ->
            if (email.isEncrypted) {
                lines += {
                    Line(
                        Icons.Filled.Lock,
                        stringResource(
                            if (email.cryptoType == SMIME_ENCRYPTED) R.string.crypto_encrypted_smime
                            else R.string.crypto_encrypted
                        ),
                        Tone.SECURE,
                    )
                }
                encryption.content.signature?.let { verdict -> lines += { Inner(verdict) } }
            } else {
                // S/MIME signed opaquely (#47): opened here to be read, and
                // its signature judged here too.
                val verdict = encryption.content.signature
                lines += {
                    Signed(
                        verdict?.state ?: SignatureState.fromWire(email.signatureState),
                        verdict?.signer ?: email.signer,
                    )
                }
            }
        is MessageEncryption.Failed -> lines += {
                Column {
                    Line(Icons.Filled.Lock, problemText(encryption.problem), Tone.WARNING)
                    if (
                        encryption.problem != DecryptionProblem.NOT_FOR_THESE_KEYS &&
                            encryption.problem != DecryptionProblem.UNSUPPORTED_CIPHER
                    ) {
                        TextButton(onClick = onRetry) {
                            Text(
                                stringResource(
                                    // Trying again is asking again, for keys
                                    // that are only locked.
                                    if (encryption.problem == DecryptionProblem.LOCKED)
                                        R.string.crypto_unlock
                                    else R.string.crypto_retry
                                )
                            )
                        }
                    }
                }
            }
        null -> {
            if (email.isEncrypted) {
                // Decrypted on arrival by a device of the person's, and kept
                // readable on the server at their request: still end-to-end
                // encrypted in transit, and worth saying where it is stored.
                lines += {
                    Line(
                        Icons.Filled.Lock,
                        stringResource(R.string.crypto_encrypted_kept),
                        Tone.SECURE,
                    )
                }
            }

            if (email.cryptoType in SIGNED_TYPES) {
                lines += { Signed(SignatureState.fromWire(email.signatureState), email.signer) }
            }
        }
    }

    if (lines.isEmpty()) return

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = PlMailTheme.spacing.medium)
                .padding(bottom = PlMailTheme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny),
    ) {
        lines.forEach { it() }
    }
}

/** The signature inside the encryption, judged on the device. */
@Composable
private fun Inner(verdict: SignatureVerdict) {
    val signer = verdict.signer?.let(::grouped).orEmpty()
    val text =
        when (verdict.state) {
            SignatureState.VALID -> stringResource(R.string.crypto_inner_valid)
            SignatureState.UNTRUSTED -> stringResource(R.string.crypto_inner_untrusted, signer)
            SignatureState.UNKNOWN_KEY -> stringResource(R.string.crypto_inner_unknown_key, signer)
            SignatureState.KEY_CHANGED -> stringResource(R.string.crypto_inner_key_changed, signer)
            SignatureState.INVALID -> stringResource(R.string.crypto_inner_invalid)
            SignatureState.UNCHECKED -> stringResource(R.string.crypto_inner_unchecked)
        }

    Line(iconFor(verdict.state), text, toneFor(verdict.state))
}

/** A signed-only message, as the server checked it. Null is a check that has not run yet. */
@Composable
private fun Signed(state: SignatureState?, signer: String?) {
    val text =
        when (state) {
            SignatureState.VALID -> stringResource(R.string.crypto_signed_valid)
            SignatureState.UNTRUSTED -> stringResource(R.string.crypto_signed_untrusted)
            SignatureState.INVALID -> stringResource(R.string.crypto_signed_invalid)
            SignatureState.UNKNOWN_KEY -> stringResource(R.string.crypto_signed_unknown_key)
            SignatureState.KEY_CHANGED -> stringResource(R.string.crypto_signed_key_changed)
            SignatureState.UNCHECKED,
            null -> stringResource(R.string.crypto_signed_pending)
        }

    Line(
        if (state == null) Icons.Filled.Draw else iconFor(state),
        text,
        if (state == null) Tone.QUIET else toneFor(state),
    )

    if (signer != null && state != null) {
        Line(text = stringResource(R.string.crypto_signer, grouped(signer)), tone = Tone.QUIET)
    }
}

@Composable
private fun problemText(problem: DecryptionProblem): String =
    stringResource(
        when (problem) {
            DecryptionProblem.NO_KEYS_HERE -> R.string.crypto_no_keys
            DecryptionProblem.LOCKED -> R.string.crypto_locked
            DecryptionProblem.NO_CERTIFICATE -> R.string.crypto_smime_unreadable
            DecryptionProblem.UNSUPPORTED_CIPHER -> R.string.crypto_smime_unsupported
            DecryptionProblem.NOT_FOR_THESE_KEYS -> R.string.crypto_not_for_these_keys
            DecryptionProblem.NO_SOURCE -> R.string.crypto_no_source
            DecryptionProblem.UNREACHABLE -> R.string.crypto_unreachable
            DecryptionProblem.FAILED -> R.string.crypto_failed
        }
    )

private enum class Tone {
    SECURE,
    WARNING,
    QUIET,
}

@Composable
private fun Line(icon: ImageVector? = null, text: String, tone: Tone) {
    val color: Color =
        when (tone) {
            Tone.SECURE -> PlMailTheme.colors.inkSoft
            Tone.WARNING -> MaterialTheme.colorScheme.error
            Tone.QUIET -> PlMailTheme.colors.inkMuted
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        }
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

private fun iconFor(state: SignatureState): ImageVector =
    when {
        state == SignatureState.VALID -> Icons.Filled.CheckCircle
        state.isWarning -> Icons.Filled.Warning
        else -> Icons.Filled.Draw
    }

private fun toneFor(state: SignatureState): Tone =
    when {
        state.isWarning -> Tone.WARNING
        state == SignatureState.VALID -> Tone.SECURE
        else -> Tone.QUIET
    }

/** A fingerprint or key id as the web app prints it: upper case, in groups of four. */
private fun grouped(hex: String): String =
    if (hex.contains('@')) hex else hex.uppercase().chunked(4).joinToString(" ")

private val SIGNED_TYPES = setOf("pgp_signed", "pgp_inline_signed", "smime_signed")

private const val SMIME_ENCRYPTED = "smime_encrypted"
