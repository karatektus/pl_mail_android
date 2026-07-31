package de.plmail.core.data

import android.content.Context
import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds @Singleton abstract fun transportFactory(real: OkHttpTransportFactory): TransportFactory

    companion object {

        /**
         * What this device will be called in the user's app-password list.
         *
         * `Build.MODEL` rather than something invented, because this is the label someone reads
         * when deciding which credential to revoke, and "Android device" four times over answers
         * nothing. Trimmed to what the server accepts.
         */
        @Provides
        @DeviceName
        fun deviceName(@ApplicationContext context: Context): String {
            val model = Build.MODEL?.trim().orEmpty()

            return model.take(MAX_DEVICE_NAME).ifBlank {
                context.getString(R.string.default_device_name)
            }
        }

        @Provides
        @Singleton
        fun serverConnector(
            transports: TransportFactory,
            @DeviceName deviceName: String,
        ): ServerConnector = ServerConnector(transports, deviceName)

        /** `DevicePairingController` truncates at 100; sending more would be silently cut. */
        private const val MAX_DEVICE_NAME = 100
    }
}

/** Distinguishes the device label from every other injectable `String`. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DeviceName
