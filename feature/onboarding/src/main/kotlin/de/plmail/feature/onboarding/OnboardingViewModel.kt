package de.plmail.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.ConnectionAttempt
import de.plmail.core.data.ServerConnector
import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.ServerConnection
import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.PairingInvitation
import de.plmail.jmap.client.PairingUri
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.client.ParsedInvitation
import de.plmail.jmap.client.ServerAddress
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Onboarding, as a state machine over one connector and one store.
 *
 * The ordering rule the whole class exists to enforce: **nothing is written until a session has
 * come back**. Every path — pasted password, tapped deep link, key accepted on the second attempt —
 * ends at the same [ConnectionAttempt.Connected], and only [confirm] persists. A flow that saved
 * the address as soon as it was typed, or the credential as soon as it was pasted, would leave the
 * app launching into a mailbox it cannot reach, with no screen able to explain why.
 */
@HiltViewModel
class OnboardingViewModel
@Inject
constructor(
    private val connector: ServerConnector,
    private val credentials: CredentialStore,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /** A pairing code, once one has been scanned or tapped. Never surfaced in [state]. */
    private var invitation: PairingInvitation? = null

    fun addressChanged(text: String) {
        _state.update {
            it.copy(
                address = text,
                // Cleared rather than recomputed: the previous failure was
                // about the previous address, and leaving it under a field the
                // user is actively fixing reads as though the new text is
                // already wrong too.
                addressProblem = ServerAddress.parse(text),
                failure = null,
            )
        }
    }

    fun appPasswordChanged(text: String) {
        _state.update { it.copy(appPassword = text.trim(), failure = null) }
    }

    /**
     * Takes a `plmail://pair?…` URI, from the camera or from a tapped link.
     *
     * Silently ignores anything that is not one. The deep link is the app's entry point for the
     * scheme, so it will be handed URIs meant for other parts of the app as those arrive, and the
     * scanner sees every barcode in frame — neither is a failure to report.
     */
    fun invitationReceived(uri: String) {
        when (val parsed = PairingUri.parse(uri)) {
            is ParsedInvitation.Valid -> startPairing(parsed.invitation)
            is ParsedInvitation.Incomplete,
            ParsedInvitation.NotAPairingUri -> Unit
        }
    }

    private fun startPairing(scanned: PairingInvitation) {
        invitation = scanned
        _state.update {
            it.copy(
                address = scanned.address.display,
                addressProblem = ParsedAddress.Valid(scanned.address),
                isPairing = true,
                failure = null,
            )
        }

        connect()
    }

    /**
     * Attempts the connection the current state describes.
     *
     * One entry point for both routes so that the trust prompt, the retry after accepting a key and
     * the failure handling exist once. The alternative — a `pair()` and a `signIn()` each with
     * their own copy — is how one of them ends up missing the trust case.
     */
    fun connect() {
        val current = _state.value
        val pending = invitation

        if (current.isBusy) return
        if (pending == null && !current.canConnect) return

        _state.update { it.copy(step = OnboardingStep.Connecting, failure = null) }

        viewModelScope.launch {
            val outcome =
                if (pending != null) {
                    connector.pair(pending, current.acceptedKey)
                } else {
                    connector.verify(
                        address = requireNotNull(current.parsedAddress),
                        credential = Credential.AppPassword(current.appPassword),
                        pinned = current.acceptedKey,
                    )
                }

            _state.update { it.reduce(outcome) }
        }
    }

    /**
     * Accepts the presented key and tries again.
     *
     * The fingerprint is kept in memory rather than saved here. The user has agreed to a key, not
     * yet to the server, and if the retry fails nothing should have been written — otherwise a typo
     * in an address would leave a pin behind for a host they never connected to.
     */
    fun acceptKey() {
        val step = _state.value.step
        if (step !is OnboardingStep.ConfirmKey) return

        _state.update { it.copy(acceptedKey = step.fingerprint, step = OnboardingStep.Entry) }
        connect()
    }

    /** Declines the key and returns to the form, leaving nothing pinned. */
    fun rejectKey() {
        _state.update { it.copy(step = OnboardingStep.Entry, acceptedKey = null) }
    }

    /**
     * Saves the connection the user has just been shown.
     *
     * The only method that writes anything, and it can only be reached from
     * [OnboardingStep.Confirm] — which is only reachable from a session that came back.
     */
    fun confirm() {
        val step = _state.value.step
        if (step !is OnboardingStep.Confirm) return

        viewModelScope.launch {
            credentials.save(
                ServerConnection(
                    address = step.server.address,
                    credential = step.server.credential,
                    pinnedKey = step.server.pinnedKey,
                    username = step.server.username,
                )
            )

            _state.update { it.copy(step = OnboardingStep.Done) }
        }
    }

    /** Backs out of the confirmation without saving, e.g. it was the wrong one of two boxes. */
    fun cancelConfirmation() {
        _state.update { it.copy(step = OnboardingStep.Entry) }
    }

    private fun OnboardingUiState.reduce(outcome: ConnectionAttempt): OnboardingUiState =
        when (outcome) {
            is ConnectionAttempt.Connected ->
                copy(step = OnboardingStep.Confirm(outcome.server), failure = null)

            is ConnectionAttempt.NeedsTrust ->
                copy(step = OnboardingStep.ConfirmKey(outcome.host, outcome.fingerprint))

            is ConnectionAttempt.Refused ->
                copy(step = OnboardingStep.Entry, failure = outcome.error)
        }
}
