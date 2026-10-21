package de.plmail.jmap.methods

import de.plmail.jmap.protocol.JmapMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `Keyring/get` — the person's keys as the server holds them: wrapped, every one.
 *
 * A JMAP singleton like `Appearance/get`: one object with id `"singleton"` and **no `accountId`**,
 * which the server refuses with `invalidArguments` because a keyring belongs to the person, not to
 * one of their accounts. Declared under `urn:plmail:params:jmap:keys`.
 *
 * Never an error for a person without keys. The object is always there, and whether there is
 * anything to open is read off it — see [Keyring.isSetUp].
 */
class KeyringGet : JmapMethod<KeyringGetResult> {

    override val name = "Keyring/get"

    override fun arguments(): JsonObject = buildJsonObject { put("ids", JsonNull) }

    override fun decode(json: Json, arguments: JsonObject): KeyringGetResult =
        json.decodeFromJsonElement(KeyringGetResult.serializer(), arguments)
}

@Serializable
data class KeyringGetResult(
    val state: String = "",
    val list: List<Keyring> = emptyList(),
    val notFound: List<String> = emptyList(),
) {
    val keyring: Keyring?
        get() = list.firstOrNull()
}

/**
 * The keyring. Only what the app reads is modelled; the rest of the object — key-server
 * publications, the Web Key Directory, S/MIME certificates — is the web app's to manage and is
 * dropped by `ignoreUnknownKeys` until a phase needs it.
 */
@Serializable
data class Keyring(
    /** False on a demo installation, where nothing is encrypted. */
    val enabled: Boolean = true,
    /**
     * What the keyring hangs off: [ROOT_PASSWORD] — the plMail password — or [ROOT_PASSPHRASE], an
     * encryption passphrase on an account that signs in only through single sign-on. It decides
     * what the app asks for, and the words it uses: a passphrase is never "your password".
     */
    val root: String = ROOT_PASSWORD,
    /**
     * The salt and iterations of [root]'s secret, or null / a null salt while there is no keyring.
     */
    val rootKdf: KdfParameters? = null,
    val userKeys: List<WrappedUserKeyEntry> = emptyList(),
    val addressKeys: List<AddressKeyEntry> = emptyList(),
) {
    /**
     * Whether there is a keyring to open.
     *
     * Two shapes mean "not yet", and neither is an error: a password account whose stored hash is
     * still of the password itself has no `rootKdf`, and a passphrase account that never chose one
     * has a `rootKdf` with a null salt. Setting up happens in the web app, not over JMAP.
     */
    val isSetUp: Boolean
        get() = enabled && rootKdf?.salt != null && userKeys.any { it.isActive }

    val isPassphrase: Boolean
        get() = root == ROOT_PASSPHRASE

    companion object {
        const val ROOT_PASSWORD = "password"
        const val ROOT_PASSPHRASE = "passphrase"
    }
}

@Serializable data class KdfParameters(val salt: String? = null, val iterations: Int = 0)

@Serializable
data class WrappedUserKeyEntry(
    val id: Long,
    val wrapped: String,
    /** `active`, or `locked` once a reset retired it. */
    val status: String = "",
) {
    val isActive: Boolean
        get() = status == "active"
}

@Serializable
data class AddressKeyEntry(
    val id: Long,
    val userKeyId: Long,
    val fingerprint: String = "",
    val publicKey: String = "",
    /** Armored and protected with the token: useless without the user key the token hangs off. */
    val privateKey: String = "",
    /** The passphrase of [privateKey], wrapped under user key [userKeyId]. */
    val token: String = "",
    /** `active`, `obsolete` or `compromised`. Only an active key signs; any of them decrypts. */
    val status: String = "",
    val addresses: List<KeyAddress> = emptyList(),
) {
    val isActive: Boolean
        get() = status == "active"
}

@Serializable data class KeyAddress(val email: String, val primary: Boolean = false)
