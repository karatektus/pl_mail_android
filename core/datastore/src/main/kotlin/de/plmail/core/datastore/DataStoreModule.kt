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
