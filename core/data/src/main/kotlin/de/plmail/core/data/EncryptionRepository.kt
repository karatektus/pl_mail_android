package de.plmail.core.data

import de.plmail.core.datastore.DeviceLockRequired
import de.plmail.core.datastore.KeyVault
import de.plmail.crypto.AddressKeyException
import de.plmail.crypto.AddressKeys
import de.plmail.crypto.KeyDerivation
import de.plmail.crypto.SmimeKeyException
import de.plmail.crypto.SmimeKeys
import de.plmail.crypto.UnlockedAddressKey
import de.plmail.crypto.UnlockedSmimeKey
import de.plmail.crypto.UserKey
import de.plmail.crypto.UserKeys
import de.plmail.crypto.WrappedUserKey
import de.plmail.crypto.WrappingException
import de.plmail.jmap.methods.AddressKeyEntry
import de.plmail.jmap.methods.ContactKeyGet
import de.plmail.jmap.methods.ContactKeyGetResult
import de.plmail.jmap.methods.ContactKeyLookup
import de.plmail.jmap.methods.Keyring
import de.plmail.jmap.methods.KeyringGet
import de.plmail.jmap.methods.RecipientCertificate
import de.plmail.jmap.methods.SmimeCertificateEntry
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.JmapError
import de.plmail.jmap.protocol.KeysCapability
import de.plmail.jmap.protocol.RequestBuilder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * End-to-end encryption on this device: which keys it holds, and getting them here (#9).
 *
 * The keys reach a device two ways. Pairing with the web app's QR code while its keyring is open
 * hands them over with the app password (`ServerConnector.pair`, stored on confirm). Failing that,
 * the person types the secret the keyring hangs off — their plMail password, or the encryption
 * passphrase of a single-sign-on account — once, and the app derives the wrap key exactly as the
 * browser does and opens the user keys with it. Everything after that hangs off the user keys: an
 * address key added, renewed or rewrapped in the web app is opened through its token without asking
 * anybody anything.
 *
 * The server never sees a key in the clear and never sees the secret: [unlock] downloads wrapped
 * keys and does all of its work on the phone.
 */
@Singleton
class EncryptionRepository
@Inject
constructor(
    private val clients: AccountClients,
    private val vault: KeyVault,
    /** How the person is asked to confirm it is them, for protected keys (phase 5). */
    private val prompt: UnlockPrompt = UnlockPrompt.NEVER,
) {

    /**
     * The address keys last unlocked, and when.
     *
     * Reading a conversation of encrypted messages would otherwise fetch the keyring and unlock
     * every address key once per message — a request and an S2K derivation each. Kept briefly and
     * only in memory; dropped whenever the keys on the device change.
     */
    private var unlocked: Pair<Long, OpenedKeys>? = null
    private val unlocking = Mutex()

    /**
     * Where this device stands, read fresh from the server and the vault.
     *
     * Opens every address key it can as part of answering, because "keys on the device" is only
     * worth saying if they open what they are for: a user key retired by a reset in the web app is
     * still stored here, opens nothing, and has to read as [EncryptionStatus.Locked].
     */
    suspend fun status(): EncryptionStatus = guarded { client ->
        val session = client.session()
        val capability = session.keys ?: return@guarded EncryptionStatus.Unsupported
        val keyring = keyring(client) ?: return@guarded EncryptionStatus.Unsupported

        when {
            !keyring.enabled -> EncryptionStatus.Unsupported
            !keyring.isSetUp -> EncryptionStatus.NotSetUp(keyring.isPassphrase)
            else ->
                try {
                    describe(keyring, capability, vault.userKeys())
                } catch (_: DeviceLockRequired) {
                    // Protected and not confirmed lately. The settings screen
                    // is not a reason to ask: it says the keys are here, and
                    // that it cannot see further without the person.
                    EncryptionStatus.Ready(
                        addresses = keyring.addressKeys.map { it.status(opens = null) },
                        isProtected = true,
                        certificates = keyring.smimeCertificates.map { it.status(opens = null) },
                    )
                }
        }
    }

    /**
     * Protects the keys on this device behind the person's fingerprint, face or screen lock — or
     * stops. Asks them to confirm it is them first, both ways.
     */
    suspend fun protect(on: Boolean): ProtectResult {
        if (!prompt.confirm()) return ProtectResult.NotConfirmed

        return try {
            vault.setProtected(on)
            ProtectResult.Done
        } catch (_: DeviceLockRequired) {
            ProtectResult.NotConfirmed
        }
    }

    /**
     * Opens the keyring with the person's password or passphrase and keeps the user keys.
     *
     * Slow on purpose — PBKDF2 at the server's iteration count, several hundred thousand rounds —
     * and run on [Dispatchers.Default] so it never lands on the thread drawing the screen.
     */
    suspend fun unlock(secret: String): UnlockResult =
        try {
            val client = clients.current() ?: return UnlockResult.Failed(null)
            val capability = client.session().keys ?: return UnlockResult.NotSetUp
            val keyring = keyring(client) ?: return UnlockResult.NotSetUp
            val kdf = keyring.rootKdf
            val salt = kdf?.salt

            if (!keyring.isSetUp || salt == null) return UnlockResult.NotSetUp

            val opened =
                withContext(Dispatchers.Default) {
                    val wrapKey =
                        KeyDerivation.wrapKey(
                            secret,
                            salt,
                            kdf.iterations,
                            capability.wrapKeyInfo,
                        )
                    try {
                        UserKeys.open(
                            wrapKey,
                            keyring.wrappedUserKeys(),
                            capability.purposes.userKey,
                        )
                    } finally {
                        wrapKey.fill(0)
                    }
                }

            // The whole of "wrong password": nothing opened. The server keeps
            // nothing to compare a passphrase with, so there is no other check.
            if (opened.isEmpty()) return UnlockResult.WrongSecret

            vault.save(opened)
            unlocked = null
            UnlockResult.Unlocked
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (refused: JmapError.NotAuthenticated) {
            forget()
            UnlockResult.Failed(refused)
        } catch (failed: Exception) {
            UnlockResult.Failed(failed)
        }

    /**
     * The address keys this device can use now, unlocked.
     *
     * For the phases that decrypt and sign; phase 1 only uses it through [status]. Empty when the
     * device holds no keys or the server is out of reach.
     *
     * When the keys are protected and the person has not confirmed it is them lately, an
     * [interactive] caller — somebody just opened an encrypted message — has them asked; anybody
     * else, and an [interactive] caller whose person declined, gets [KeysLocked].
     */
    suspend fun unlockedAddressKeys(interactive: Boolean = false): List<UnlockedAddressKey> =
        unlockedKeys(interactive).addressKeys

    /**
     * Everything this device can open now: the OpenPGP address keys and the S/MIME certificates
     * (#47), on the same terms as [unlockedAddressKeys].
     */
    suspend fun unlockedKeys(interactive: Boolean = false): OpenedKeys = unlocking.withLock {
        unlocked
            ?.takeIf { (at, _) -> System.currentTimeMillis() - at < UNLOCKED_FOR_MILLIS }
            ?.let {
                return@withLock it.second
            }

        val keys =
            try {
                freshlyUnlocked()
            } catch (_: DeviceLockRequired) {
                if (!interactive || !prompt.confirm()) throw KeysLocked()
                try {
                    freshlyUnlocked()
                } catch (_: DeviceLockRequired) {
                    throw KeysLocked()
                }
            }

        keys.also { unlocked = if (it.isEmpty) null else System.currentTimeMillis() to it }
    }

    /**
     * The fingerprints of the address keys this device signs with, without opening anything when
     * the keys are protected and locked: then the keyring's active keys, which are the ones the
     * keys here open. For the composer, which wants to know before anybody is asked.
     */
    suspend fun signingFingerprints(): List<String> =
        try {
            unlockedAddressKeys().map { it.fingerprint }
        } catch (_: KeysLocked) {
            runCatching {
                val client = clients.current() ?: return emptyList()
                keyring(client)?.addressKeys?.filter { it.isActive }?.map { it.fingerprint }
            }
                .getOrNull()
                .orEmpty()
        }

    /**
     * The fingerprints of the S/MIME certificates this device signs with (#47), on the same terms
     * as [signingFingerprints]: without opening anything while the keys are locked.
     */
    suspend fun signingCertificates(): List<String> =
        try {
            unlockedKeys().smimeKeys.filter { it.isActive }.map { it.fingerprint }
        } catch (_: KeysLocked) {
            runCatching {
                val client = clients.current() ?: return emptyList()
                keyring(client)?.smimeCertificates?.filter { it.isActive }?.map { it.fingerprint }
            }
                .getOrNull()
                .orEmpty()
        }

    /** Whether the keys here are protected, for the composer and the settings screen. */
    val isProtected: Flow<Boolean>
        get() = vault.isProtected

    /**
     * The keys plMail holds for other people, from `ContactKey/get`; empty when there are none or
     * the server cannot be asked. For checking the signature inside an encrypted message.
     */
    suspend fun contactKeys(): ContactKeyGetResult =
        try {
            val client = clients.current()
            if (client?.session()?.keys == null) {
                ContactKeyGetResult()
            } else {
                val request = RequestBuilder(using = Capability.USING_KEYS)
                val get = request.add(ContactKeyGet())
                client.send(request).result(get)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (refused: JmapError.NotAuthenticated) {
            forget()
            ContactKeyGetResult()
        } catch (_: Exception) {
            ContactKeyGetResult()
        }

    private suspend fun freshlyUnlocked(): OpenedKeys =
        try {
            val client = clients.current() ?: return OpenedKeys()
            val capability = client.session().keys ?: return OpenedKeys()
            val keyring = keyring(client) ?: return OpenedKeys()
            val userKeys = vault.userKeys()

            withContext(Dispatchers.Default) {
                OpenedKeys(
                    addressKeys = keyring.addressKeys.mapNotNull { open(it, userKeys, capability) },
                    smimeKeys =
                        keyring.smimeCertificates.mapNotNull { open(it, userKeys, capability) },
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (locked: DeviceLockRequired) {
            throw locked
        } catch (refused: JmapError.NotAuthenticated) {
            forget()
            OpenedKeys()
        } catch (_: Exception) {
            OpenedKeys()
        }

    /** Removes the keys from this device, when the person asks. The server's copies stay. */
    suspend fun forget() {
        unlocked = null
        vault.forget()
    }

    private suspend fun describe(
        keyring: Keyring,
        capability: KeysCapability,
        userKeys: List<UserKey>,
    ): EncryptionStatus {
        if (userKeys.isEmpty())
            return EncryptionStatus.Locked(keyring.isPassphrase, hadKeys = false)

        val addresses =
            withContext(Dispatchers.Default) {
                keyring.addressKeys.map { entry ->
                    entry.status(opens = open(entry, userKeys, capability) != null)
                }
            }
        val certificates =
            withContext(Dispatchers.Default) {
                keyring.smimeCertificates.map { entry ->
                    entry.status(opens = open(entry, userKeys, capability) != null)
                }
            }

        // Keys are stored but open none of the address keys: a reset in the
        // web app retired the user key this device holds. Asking again is the
        // way back, and saying "ready" here would be the first lie of many.
        val opensAnything =
            addresses.any { it.opens == true } || certificates.any { it.opens == true }
        if ((addresses.isNotEmpty() || certificates.isNotEmpty()) && !opensAnything) {
            return EncryptionStatus.Locked(keyring.isPassphrase, hadKeys = true)
        }

        return EncryptionStatus.Ready(
            addresses,
            isProtected = vault.isProtected.first(),
            certificates = certificates,
        )
    }

    private fun SmimeCertificateEntry.status(opens: Boolean?) =
        SmimeCertificateStatus(
            fingerprint = fingerprint,
            addresses = addresses,
            issuer = issuer,
            notAfter = notAfter?.toInstantOrNull(),
            isActive = isActive,
            opens = opens,
        )

    /**
     * The S/MIME certificate plMail holds for [address] (#47) — the contact's current one, or the
     * person's own — for judging a signature inside encrypted mail. Null when there is none or the
     * server cannot be asked.
     */
    suspend fun certificateFor(address: String): RecipientCertificate? =
        try {
            val client = clients.current()
            if (client?.session()?.keys == null) {
                null
            } else {
                val request = RequestBuilder(using = Capability.USING_KEYS)
                val lookup = request.add(ContactKeyLookup(emails = listOf(address)))
                client.send(request).result(lookup).forAddress(address)?.smime
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    private fun AddressKeyEntry.status(opens: Boolean?) =
        AddressKeyStatus(
            fingerprint = fingerprint,
            addresses = addresses.map { it.email },
            primaryAddress = addresses.firstOrNull { it.primary }?.email,
            isActive = isActive,
            opens = opens,
        )

    /** An S/MIME certificate's private key, with the user key it is wrapped under. */
    private fun open(
        entry: SmimeCertificateEntry,
        userKeys: List<UserKey>,
        capability: KeysCapability,
    ): UnlockedSmimeKey? {
        val userKey = userKeys.firstOrNull { it.id == entry.userKeyId } ?: return null

        return try {
            SmimeKeys.unlock(
                certificatePem = entry.certificate,
                chainPem = entry.chain,
                wrappedPrivateKey = entry.privateKey,
                userKey = userKey,
                addresses = entry.addresses,
                isActive = entry.isActive,
                notBefore = entry.notBefore?.toInstantOrNull(),
                notAfter = entry.notAfter?.toInstantOrNull(),
                purpose = capability.purposes.smimeKey,
            )
        } catch (_: SmimeKeyException) {
            null
        }
    }

    private fun open(
        entry: AddressKeyEntry,
        userKeys: List<UserKey>,
        capability: KeysCapability,
    ): UnlockedAddressKey? {
        val userKey = userKeys.firstOrNull { it.id == entry.userKeyId } ?: return null

        return try {
            AddressKeys.unlock(
                entry.privateKey,
                AddressKeys.token(userKey, entry.token, capability.purposes.addressKeyToken),
            )
        } catch (_: WrappingException) {
            null
        } catch (_: AddressKeyException) {
            null
        }
    }

    private suspend fun keyring(client: de.plmail.jmap.client.JmapClient): Keyring? {
        val request = RequestBuilder(using = Capability.USING_KEYS)
        val get = request.add(KeyringGet())

        return client.send(request).result(get).keyring
    }

    private suspend fun guarded(
        block: suspend (de.plmail.jmap.client.JmapClient) -> EncryptionStatus
    ): EncryptionStatus =
        try {
            val client = clients.current() ?: return EncryptionStatus.Unsupported
            block(client)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (refused: JmapError.NotAuthenticated) {
            // The documented rule: an app password the server refuses takes
            // the keys with it. A revoked device must not keep reading mail.
            forget()
            EncryptionStatus.Unreachable(refused)
        } catch (failed: Exception) {
            EncryptionStatus.Unreachable(failed)
        }

    private fun Keyring.wrappedUserKeys(): List<WrappedUserKey> = userKeys.map {
        WrappedUserKey(it.id, it.wrapped, it.isActive)
    }
}

/** How long unlocked address keys are kept in memory between reads. */
private const val UNLOCKED_FOR_MILLIS = 5 * 60 * 1000L

/** Where end-to-end encryption stands on this device. */
sealed interface EncryptionStatus {

    /** The server has no end-to-end encryption, or it is switched off (a demo installation). */
    data object Unsupported : EncryptionStatus

    /** The person has not set encryption up yet. That happens in the web app, not here. */
    data class NotSetUp(val isPassphrase: Boolean) : EncryptionStatus

    /**
     * There is a keyring, and this device cannot open it: it holds no keys, or only keys a reset
     * retired ([hadKeys]). The way in is the password (or passphrase), or pairing again.
     */
    data class Locked(val isPassphrase: Boolean, val hadKeys: Boolean) : EncryptionStatus

    /**
     * The device holds keys; [addresses] says which address keys they open. [isProtected] keys open
     * only after the person confirms it is them; while they have not, nothing was opened to answer
     * this, and each address key's `opens` is null.
     */
    data class Ready(
        val addresses: List<AddressKeyStatus>,
        val isProtected: Boolean = false,
        /** The person's S/MIME certificates (#47); `opens` is null as for [addresses]. */
        val certificates: List<SmimeCertificateStatus> = emptyList(),
    ) : EncryptionStatus

    /** The server could not be asked. Says nothing about the keys either way. */
    data class Unreachable(val cause: Throwable?) : EncryptionStatus
}

/** One address key as the settings screen shows it. */
data class AddressKeyStatus(
    val fingerprint: String,
    val addresses: List<String>,
    val primaryAddress: String?,
    val isActive: Boolean,
    /** Whether the keys on this device unlock it; null when they are protected and locked. */
    val opens: Boolean?,
)

/** One of the person's S/MIME certificates as the settings screen shows it (#47). */
data class SmimeCertificateStatus(
    val fingerprint: String,
    val addresses: List<String>,
    val issuer: String?,
    val notAfter: Instant?,
    /** False once a newer certificate replaced it: it then only decrypts. */
    val isActive: Boolean,
    /** Whether the keys on this device open its private key; null when they are locked. */
    val opens: Boolean?,
)

/** Everything the device has open: OpenPGP address keys and S/MIME certificates. */
data class OpenedKeys(
    val addressKeys: List<UnlockedAddressKey> = emptyList(),
    val smimeKeys: List<UnlockedSmimeKey> = emptyList(),
) {
    val isEmpty: Boolean
        get() = addressKeys.isEmpty() && smimeKeys.isEmpty()
}

/** An ATOM date-time from the server, or null when it is not one. */
internal fun String.toInstantOrNull(): Instant? = runCatching {
    java.time.OffsetDateTime.parse(this).toInstant()
}
    .getOrNull()

/** What switching the protection did. */
enum class ProtectResult {
    Done,

    /** The person did not confirm it is them, or the device has no screen lock. Nothing changed. */
    NotConfirmed,
}

/** What typing the password (or passphrase) did. */
sealed interface UnlockResult {
    data object Unlocked : UnlockResult

    /** It opened none of the keys. The only check there is — see [EncryptionRepository.unlock]. */
    data object WrongSecret : UnlockResult

    /** There is nothing to open: encryption is not set up, or the server has none. */
    data object NotSetUp : UnlockResult

    data class Failed(val cause: Throwable?) : UnlockResult
}
