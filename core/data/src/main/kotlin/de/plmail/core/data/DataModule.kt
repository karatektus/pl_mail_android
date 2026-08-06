package de.plmail.core.data

import android.content.Context
import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds @Singleton abstract fun transportFactory(real: OkHttpTransportFactory): TransportFactory

    @Binds @Singleton abstract fun draftSender(real: ComposeRepository): DraftSender

    /**
     * The reconcile's view of the submission methods.
     *
     * Bound rather than injected directly for the reason every seam in this file is: the reconciler
     * wants four calls, and holding the whole composer repository would put the attachment uploader
     * and the content resolver behind a background pass whose only job is to ask what the server
     * thinks is still waiting.
     */
    @Binds @Singleton abstract fun submissionDirectory(real: ComposeRepository): SubmissionDirectory

    /**
     * Declares the listener set so it can be empty.
     *
     * Without this, a graph with no `@IntoSet NewMailListener` fails to compile rather than
     * injecting nothing — which would make `:core:data` unbuildable on its own and tie syncing to a
     * module that only exists to draw things.
     */
    @Multibinds abstract fun newMailListeners(): Set<NewMailListener>

    /**
     * The queue's view of the label list.
     *
     * Bound here rather than injected directly, so [Outbox] holds one method instead of a
     * repository that reaches Room and OkHttp — see [KnownLabels].
     */
    @Binds @Singleton abstract fun knownLabels(real: LabelRepository): KnownLabels

    /**
     * The sync's view of the failure banner.
     *
     * Bound rather than injected directly for the same reason [KnownLabels] is: [DeltaSync] would
     * otherwise hold the whole of `FeedRepository` — pagers, sockets and all — to withdraw one
     * sentence from a list.
     */
    @Binds @Singleton abstract fun reachableAccounts(real: FeedRepository): ReachableAccounts

    /**
     * The event editor's view of the calendar.
     *
     * Bound rather than injected directly, for the reason the two above are: the editor needs four
     * methods, and holding the whole repository would put Room, DataStore and OkHttp behind a
     * screen whose interesting behaviour is which form is on it.
     */
    @Binds @Singleton abstract fun eventEditing(real: CalendarRepository): EventEditing

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

        /**
         * A scope that lives as long as the process.
         *
         * For work that must not be cancelled by the screen that started it — the undo-send window
         * is exactly that: the composer closes the instant Send is tapped, and a `viewModelScope`
         * would take the send with it.
         *
         * `SupervisorJob` so one failed send does not cancel the next, and `Dispatchers.Default`
         * rather than `Main` so nothing here can block a frame.
         */
        @Provides
        @Singleton
        @ApplicationScope
        fun applicationScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * What "today" is — and, since it carries a zone, *where* the device is.
         *
         * [CalendarRepository] compares the window being refreshed against today to decide whether
         * the server may have answered it from a partial index. A test pinning that with
         * `LocalDate.now()` underneath would pass when it was written and start failing on a date
         * nobody can predict, which is the shape of flake that gets a whole assertion deleted.
         *
         * The **zone** is load-bearing as well: `CalendarEvent/query` windows go on the wire in
         * UTC, so every window is converted out of this clock's zone. `systemDefaultZone` rather
         * than `systemUTC` for that reason, and a test in Europe/Berlin is a phone in
         * Europe/Berlin.
         */
        @Provides @Singleton fun clock(): Clock = Clock.systemDefaultZone()

        /** `DevicePairingController` truncates at 100; sending more would be silently cut. */
        private const val MAX_DEVICE_NAME = 100
    }
}

/** Distinguishes the device label from every other injectable `String`. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DeviceName

/** Distinguishes the process-lifetime scope from any other injectable `CoroutineScope`. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope
