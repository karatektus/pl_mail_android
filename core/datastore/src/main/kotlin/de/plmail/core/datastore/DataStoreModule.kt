package de.plmail.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    /**
     * One DataStore instance for the whole process.
     *
     * DataStore enforces this itself — a second instance over the same file throws — so the
     * `@Singleton` is the mechanism that keeps that from happening rather than a performance
     * preference.
     */
    @Provides
    @Singleton
    fun connectionPreferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // SupervisorJob so a failed write cannot cancel the scope and take
            // every later write with it; the store outlives any one caller.
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile(CredentialStore.FILE) },
        )

    @Provides @Singleton fun secretCipher(): SecretCipher = KeystoreSecretCipher()

    /** The end-to-end keys' own Keystore key — see [KeyVault] for why it is not the password's. */
    @Provides
    @Singleton
    @KeyVaultCipher
    fun keyVaultCipher(): SecretCipher = KeystoreSecretCipher(alias = KEY_VAULT_ALIAS)

    /**
     * The second key, for a person who asked to confirm it is them before encrypted mail opens (#9,
     * phase 5). Good for five minutes after a fingerprint, a face or unlocking the phone.
     */
    @Provides
    @Singleton
    @ProtectedKeyVaultCipher
    fun protectedKeyVaultCipher(): SecretCipher =
        KeystoreSecretCipher(
            alias = PROTECTED_KEY_VAULT_ALIAS,
            authentication = UserAuthentication(seconds = PROTECTED_FOR_SECONDS),
        )

    private const val KEY_VAULT_ALIAS = "plmail.e2e.keys"
    private const val PROTECTED_KEY_VAULT_ALIAS = "plmail.e2e.keys.protected"
    private const val PROTECTED_FOR_SECONDS = 5 * 60

    /**
     * `Dispatchers.IO` because on a real device the cipher is the Android Keystore, and opening the
     * secret is two binder round trips into the TEE. Left to the collector's thread that happens on
     * `Dispatchers.Main.immediate`, in front of the first frame — see [CredentialStore.connection].
     */
    @Provides
    @Singleton
    fun credentialStore(
        preferences: DataStore<Preferences>,
        cipher: SecretCipher,
    ): CredentialStore = CredentialStore(preferences, cipher, opening = Dispatchers.IO)
}
