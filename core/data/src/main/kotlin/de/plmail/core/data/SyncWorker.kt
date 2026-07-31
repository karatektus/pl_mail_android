package de.plmail.core.data

import android.content.Context
import androidx.work.BackoffPolicy
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
import java.util.concurrent.TimeUnit

/**
 * The periodic catch-up.
 *
 * Fifteen minutes, matching the server's own schedule. Being more eager buys nothing: the mail is
 * not there yet, and every extra poll is a PHP worker occupied on hardware that frequently has one.
 * This is the floor WorkManager allows anyway.
 *
 * Push, when it arrives, does not replace this — it makes it the fallback. A device with no
 * distributor, or one where the user declined notifications, still has to get its mail.
 */
class SyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    /**
     * Dependencies through an entry point rather than `@HiltWorker`.
     *
     * The annotation needs `hilt-work`, a custom `WorkerFactory`, a `Configuration.Provider` on the
     * Application and the default initialiser disabled in the manifest — four things to get right
     * for one injected class. This needs none of them.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun deltaSync(): DeltaSync

        fun database(): de.plmail.core.database.PlMailDatabase
    }

    override suspend fun doWork(): Result {
        val dependencies =
            EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)

        val accounts = dependencies.database().accounts().all()
        if (accounts.isEmpty()) return Result.success()

        val outcomes = accounts.map { dependencies.deltaSync().sync(it.uid) }

        // Retried only when everything failed. One unreachable account among
        // several is not a reason to re-run the whole sync -- the others are
        // already up to date, and retrying would re-ask them for nothing.
        return if (outcomes.all { it is SyncResult.Failed }) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "plmail.sync"

        /**
         * Schedules the periodic sync, keeping any existing schedule.
         *
         * `KEEP`, not `UPDATE`: replacing the request on every launch resets its period, so an app
         * opened often would never actually reach the fifteen-minute mark and would sync only when
         * opened — which is the one case that needs no background work at all.
         */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            // Not UNMETERED: this is someone's own server, often
                            // on the same LAN, and a list refresh is small.
                            // Requiring wifi would leave a phone on mobile data
                            // silently stale all day.
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        BACKOFF_MINUTES,
                        TimeUnit.MINUTES,
                    )
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Stops syncing, for when the last account is removed. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }

        private const val INTERVAL_MINUTES = 15L

        /** Exponential from ten minutes: a server that is down is usually down for a while. */
        private const val BACKOFF_MINUTES = 10L
    }
}
