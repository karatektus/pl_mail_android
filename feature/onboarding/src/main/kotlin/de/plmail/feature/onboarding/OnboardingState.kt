package de.plmail.feature.onboarding

import de.plmail.core.data.VerifiedServer
import de.plmail.jmap.client.KeyFingerprint
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.client.ServerAddress
import de.plmail.jmap.protocol.JmapError

/**
 * Where onboarding has got to.
 *
 * Modelled as steps rather than a bag of booleans because the two questions this flow asks — "is
 * this key yours?" and "is this the right server?" — are asked at different moments, and a state
 * shaped as `isLoading`/`showTrustDialog`/`isConfirming` permits combinations that mean nothing
 * (connecting *and* confirming) while making the one that matters, a trust prompt raised during
 * pairing rather than during verification, indistinguishable.
 */
sealed interface OnboardingStep {

    /** Typing an address and a password, or waiting for a scanned invitation. */
    data object Entry : OnboardingStep

    /** A request is outstanding. Nothing on screen may be edited while one is. */
    data object Connecting : OnboardingStep

    /**
     * The certificate is not trusted and nothing is pinned yet.
     *
     * The expected first contact with a self-signed NAS, not an error. [fingerprint] is rendered in
     * `openssl`-comparable pairs so it can be checked against the server itself.
     */
    data class ConfirmKey(val host: String, val fingerprint: KeyFingerprint) : OnboardingStep

    /**
     * The server answered and the credential works — but nothing is saved yet.
     *
     * This step exists because "was that `nas.local` or the other `nas.local`" is a real question
     * for this audience, and the username and account list are the only things that can answer it.
     * Saving first and showing afterwards would make the answer arrive too late to act on.
     */
    data class Confirm(val server: VerifiedServer) : OnboardingStep

    /** Saved and verified; the host may leave onboarding. */
    data object Done : OnboardingStep
}

/**
 * Everything the onboarding screen draws.
 *
 * [addressProblem] and [failure] are deliberately separate. The first is about what is in the field
 * right now and appears under it as the user types; the second is about a round trip that did not
 * work and belongs beside the button they pressed. Collapsing them would put "could not reach
 * nas.local" under a text field as though the text were wrong.
 */
data class OnboardingUiState(
    val address: String = "",
    val appPassword: String = "",
    val step: OnboardingStep = OnboardingStep.Entry,
    val addressProblem: ParsedAddress? = null,
    val failure: JmapError? = null,
    /**
     * The key accepted during this attempt, before it has been saved.
     *
     * Held here rather than written straight to the store because the user has agreed to a key, not
     * yet to the server: if verification then fails, nothing should have been persisted.
     */
    val acceptedKey: KeyFingerprint? = null,
    /**
     * Set when an invitation is driving the flow, so the UI can say pairing rather than sign-in.
     */
    val isPairing: Boolean = false,
) {
    val isBusy: Boolean
        get() = step is OnboardingStep.Connecting

    /** The parsed address, when there is one. Null while the field is empty or wrong. */
    val parsedAddress: ServerAddress?
        get() = (addressProblem as? ParsedAddress.Valid)?.address

    /**
     * Whether "connect" should do anything.
     *
     * The password is checked for shape rather than just for emptiness: `looksValid` catches the
     * paste that picked up a stray character or lost the `plmail_` prefix, and catching it here
     * costs nothing where a round trip would return an indistinguishable 401.
     */
    val canConnect: Boolean
        get() =
            !isBusy &&
                parsedAddress != null &&
                de.plmail.jmap.client.Credential.AppPassword.looksValid(appPassword)

    /** True when the address will carry the credential in clear, so the UI can say so. */
    val warnsAboutCleartext: Boolean
        get() = parsedAddress?.isCleartext == true
}
