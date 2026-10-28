package de.plmail.core.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.plmail.core.datastore.UpdatePrefsStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Once a day, asks GitHub whether there is a newer release, and says so once.
 *
 * Never a retry and never a failure: the check is a courtesy, and a phone that could not reach
 * GitHub today will ask again tomorrow. Asking sooner would spend the shared rate limit of whatever
 * network the phone is on for a release that will still be there.
 */
class UpdateCheckWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    /** Through an entry point rather than `@HiltWorker`, for the reason [SyncWorker] gives. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun updater(): AppUpdater

        fun prefs(): UpdatePrefsStore

        fun listeners(): Set<@JvmSuppressWildcards UpdateListener>
    }

    override suspend fun doWork(): Result {
        val dependencies =
            EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)

        val updater = dependencies.updater()
        val prefs = dependencies.prefs()

        if (!updater.isSupported || !prefs.isAutoCheckOn.first()) return Result.success()

        val update = runCatching { updater.latest() }.getOrNull() ?: return Result.success()

        if (prefs.notifiedVersionCode() == update.versionCode) return Result.success()

        dependencies.listeners().forEach { listener ->
            runCatching { listener.onUpdateAvailable(update) }
        }
        prefs.setNotified(update.versionCode)

        return Result.success()
    }

    companion object {
        private const val NAME = "plmail.update-check"

        private const val INTERVAL_HOURS = 24L

        /**
         * Schedules the daily check, keeping any existing schedule — `KEEP` for the reason
         * [SyncWorker.schedule] gives. Harmless on a build that cannot update: the run returns at
         * its first line.
         */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
