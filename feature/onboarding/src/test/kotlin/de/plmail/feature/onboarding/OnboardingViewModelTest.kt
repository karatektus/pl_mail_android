package de.plmail.feature.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import de.plmail.core.data.ServerConnector
import de.plmail.core.data.TransportFactory
import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.SealedSecret
import de.plmail.core.datastore.SecretCipher
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.client.JmapTransport
import de.plmail.jmap.client.KeyFingerprint
import de.plmail.jmap.client.ServerAddress
import de.plmail.jmap.client.StreamingTransport
import de.plmail.jmap.protocol.JmapError
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Onboarding's ordering rule, which is the thing worth testing here.
 *
 * **Nothing is written until a session has come back.** A flow that saved the address as it was
 * typed, or the credential as it was pasted, would leave the app launching into a mailbox it cannot
 * reach with no screen able to say why — and would leave a pin behind for a host the user mistyped
 * and never connected to. Most of what follows is a variation on "did anything reach the store
 * early".
 *
 * The connector is real and only the transport is scripted, so these exercise the same trust
 * handling the connector's own tests do rather than a stub that could agree with a broken
 * ViewModel.
 */
// setMain, resetMain and advanceUntilIdle are all still marked experimental, and warnings are
// errors here. Opting in once at the class is the alternative to eighteen call-site annotations
// that say nothing.
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val secret = "plmail_" + "5e".repeat(32)
    private val fingerprint = requireNotNull(KeyFingerprint.parse("ab".repeat(32)))

    private val sessionJson =
        """
        {
          "capabilities": {"urn:ietf:params:jmap:core": {}},
          "accounts": {"13": {"name": "someone@example.com"}},
          "username": "someone@example.com",
          "apiUrl": "https://nas.local/jmap/api",
          "downloadUrl": "https://nas.local/jmap/download",
          "uploadUrl": "https://nas.local/jmap/upload"
        }
        """

    private lateinit var store: CredentialStore

    @BeforeTest
    fun useTestDispatcher() {
        // viewModelScope is hard-wired to Dispatchers.Main, so it has to be
        // replaced rather than injected; that is the whole reason this needs
        // setMain at all.
        Dispatchers.setMain(dispatcher)
        store = CredentialStore(FakeDataStore(), ReversingCipher())
    }

    @AfterTest fun restoreDispatcher() = Dispatchers.resetMain()

    @Test
    fun `a verified server is shown for confirmation before anything is stored`() = runTest {
        val viewModel = viewModel { answering(sessionJson) }

        viewModel.addressChanged("nas.local")
        viewModel.appPasswordChanged(secret)
        viewModel.connect()
        advanceUntilIdle()

        val step = viewModel.state.value.step
        assertIs<OnboardingStep.Confirm>(step)
        assertEquals("someone@example.com", step.server.username)

        // The point of the step: still nothing saved.
        assertNull(store.connection.first())
    }

    @Test
    fun `confirming is what writes the connection`() = runTest {
        val viewModel = viewModel { answering(sessionJson) }

        viewModel.addressChanged("nas.local")
        viewModel.appPasswordChanged(secret)
        viewModel.connect()
        advanceUntilIdle()
        viewModel.confirm()
        advanceUntilIdle()

        val saved = requireNotNull(store.connection.first())
        assertEquals("https://nas.local", saved.address.display)
        assertEquals(secret, saved.credential.secret)
        assertEquals("someone@example.com", saved.username)
        assertIs<OnboardingStep.Done>(viewModel.state.value.step)
    }

    @Test
    fun `backing out of the confirmation saves nothing`() = runTest {
        val viewModel = viewModel { answering(sessionJson) }

        viewModel.addressChanged("nas.local")
        viewModel.appPasswordChanged(secret)
        viewModel.connect()
        advanceUntilIdle()
        viewModel.cancelConfirmation()
        advanceUntilIdle()

        assertNull(store.connection.first())
        assertIs<OnboardingStep.Entry>(viewModel.state.value.step)
    }

    @Test
    fun `an untrusted certificate becomes a prompt carrying the fingerprint`() = runTest {
        val viewModel = viewModel { refusingTrust() }

        viewModel.addressChanged("nas.local")
        viewModel.appPasswordChanged(secret)
        viewModel.connect()
        advanceUntilIdle()

        val step = viewModel.state.value.step
        assertIs<OnboardingStep.ConfirmKey>(step)
        assertEquals(fingerprint, step.fingerprint)
        assertEquals("nas.local", step.host)
        assertNull(store.connection.first())
    }

    @Test
    fun `accepting a key retries with it and only then can be saved`() = runTest {
        // Scripted on the pin, exactly as the real trust manager behaves: the
        // first attempt has nothing pinned and is refused, the retry carries
        // the accepted key and succeeds.
        val viewModel = viewModel { pinned ->
            if (pinned == null) refusingTrust() else answering(sessionJson)
        }

        viewModel.addressChanged("nas.local")
        viewModel.appPasswordChanged(secret)
        viewModel.connect()
        advanceUntilIdle()
        viewModel.acceptKey()
        advanceUntilIdle()

        assertIs<OnboardingStep.Confirm>(viewModel.state.value.step)
        assertNull(store.connection.first())

        viewModel.confirm()
        advanceUntilIdle()

        assertEquals(fingerprint, store.connection.first()?.pinnedKey)
    }

    /**
     * A rejected key must leave nothing behind.
     *
     * Otherwise a typo in an address pins a stranger's certificate, and the next attempt at the
     * *correct* host inherits it.
     */
    @Test
    fun `rejecting a key pins nothing`() = runTest {
        val viewModel = viewModel { refusingTrust() }

        viewModel.addressChanged("nas.local")
        viewModel.appPasswordChanged(secret)
        viewModel.connect()
        advanceUntilIdle()
        viewModel.rejectKey()
        advanceUntilIdle()

        assertNull(viewModel.state.value.acceptedKey)
        assertNull(store.connection.first())
        assertIs<OnboardingStep.Entry>(viewModel.state.value.step)
    }

    @Test
    fun `a revoked credential returns to the form with the reason and saves nothing`() = runTest {
        val viewModel = viewModel {
            JmapTransport {
                HttpResponse(
                    401,
                    mapOf("Content-Type" to "application/problem+json"),
                    """{"status":401,"detail":"Unknown app password."}""".encodeToByteArray(),
                )
            }
        }

        viewModel.addressChanged("nas.local")
        viewModel.appPasswordChanged(secret)
        viewModel.connect()
        advanceUntilIdle()

        assertIs<OnboardingStep.Entry>(viewModel.state.value.step)
        assertIs<JmapError.NotAuthenticated>(viewModel.state.value.failure)
        assertNull(store.connection.first())
    }

    @Test
    fun `a tapped pairing link fills the address and connects on its own`() = runTest {
        val viewModel = viewModel {
            JmapTransport { request ->
                val body =
                    if (request.url.endsWith("/device/pair")) {
                        """{"secret":"$secret","username":"someone@example.com"}"""
                    } else {
                        sessionJson
                    }

                HttpResponse(200, emptyMap(), body.encodeToByteArray())
            }
        }

        viewModel.invitationReceived("plmail://pair?host=https%3A%2F%2Fnas.local&code=abc")
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isPairing)
        assertEquals("https://nas.local", viewModel.state.value.address)
        assertIs<OnboardingStep.Confirm>(viewModel.state.value.step)
        assertNull(store.connection.first())
    }

    @Test
    fun `a uri that is not an invitation is ignored rather than shown as an error`() = runTest {
        val viewModel = viewModel { answering(sessionJson) }

        viewModel.invitationReceived("https://example.com")
        viewModel.invitationReceived("plmail://open?thread=4")
        advanceUntilIdle()

        assertIs<OnboardingStep.Entry>(viewModel.state.value.step)
        assertNull(viewModel.state.value.failure)
        assertFalse(viewModel.state.value.isPairing)
    }

    @Test
    fun `connect does nothing without a usable address and password`() = runTest {
        var calls = 0
        val viewModel = viewModel {
            calls++
            answering(sessionJson)
        }

        viewModel.connect()
        advanceUntilIdle()

        viewModel.addressChanged("nas.local")
        viewModel.connect()
        advanceUntilIdle()

        // Looks like a password but is not one: caught before a round trip that
        // would come back as an indistinguishable 401.
        viewModel.appPasswordChanged("not-an-app-password")
        viewModel.connect()
        advanceUntilIdle()

        assertEquals(0, calls, "connect ran without a usable address and password")
    }

    @Test
    fun `a cleartext address is flagged so the screen can warn`() = runTest {
        val viewModel = viewModel { answering(sessionJson) }

        viewModel.addressChanged("http://10.0.2.2:8002")
        assertTrue(viewModel.state.value.warnsAboutCleartext)

        viewModel.addressChanged("https://nas.local")
        assertFalse(viewModel.state.value.warnsAboutCleartext)
    }

    private fun answering(body: String): JmapTransport = JmapTransport {
        HttpResponse(200, mapOf("Content-Type" to "application/json"), body.encodeToByteArray())
    }

    private fun refusingTrust(): JmapTransport = JmapTransport {
        throw JmapError.UntrustedCertificate("nas.local", fingerprint.hex)
    }

    private fun viewModel(transport: (KeyFingerprint?) -> JmapTransport): OnboardingViewModel =
        OnboardingViewModel(
            connector = ServerConnector(factory(transport), deviceName = "Pixel"),
            credentials = store,
        )

    /**
     * A factory over one transport, chosen by the pin.
     *
     * Spelled out rather than passed as a lambda because [TransportFactory] grew a second method
     * and stopped being a `fun interface`. That method throws here rather than obliging: onboarding
     * opens no event stream, and a fake that answered would let this keep passing while the
     * connector did something no server on this path is ever asked to do.
     */
    private fun factory(transport: (KeyFingerprint?) -> JmapTransport): TransportFactory =
        object : TransportFactory {
            override fun create(address: ServerAddress, pinned: KeyFingerprint?): JmapTransport =
                transport(pinned)

            override fun createStreaming(
                address: ServerAddress,
                pinned: KeyFingerprint?,
            ): StreamingTransport = error("Onboarding opens no event stream.")
        }
}

/** Reversible, and marked, so a store that forgot to seal is caught rather than passing quietly. */
private class ReversingCipher : SecretCipher {
    override fun seal(plaintext: String) = SealedSecret(PREFIX + plaintext.reversed())

    override fun open(sealed: SealedSecret): String? =
        if (sealed.encoded.startsWith(PREFIX)) {
            sealed.encoded.removePrefix(PREFIX).reversed()
        } else {
            null
        }

    private companion object {
        const val PREFIX = "sealed:"
    }
}

private class FakeDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences>
        get() = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
        val updated = transform(state.value)
        state.update { updated }

        return updated
    }
}
