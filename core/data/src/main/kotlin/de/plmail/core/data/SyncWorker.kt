package de.plmail.core.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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

        fun mailActions(): MailActions

        fun appearance(): AppearanceRepository

        fun bodies(): BodyPrefetcher
    }

    override suspend fun doWork(): Result {
        val dependencies =
            EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)

        val accounts = dependencies.database().accounts().all()
        if (accounts.isEmpty()) return Result.success()

        // Before the sync, not after, and that ordering is the whole reason this
        // is here. A queued archive and a delta sync disagree about the same
        // conversation: the sync would fetch the server's copy, which still has
        // the Inbox label, and write it over the local row -- so the change the
        // user made offline would visibly *undo itself* the moment the network
        // came back, which is worse than it never having been sent.
        //
        // A failure is not reported up. The sync below is what decides whether
        // this run is worth retrying, and a queue that could not drain will
        // still be there next time by construction.
        runCatching { dependencies.mailActions().flush() }

        val outcomes = accounts.map { dependencies.deltaSync().sync(it.uid) }

        // Appearance has no push and no `/changes`, so this and the foreground
        // resume are the only two places a theme changed on another device can
        // be noticed. It swallows its own failures and never decides the run's
        // result: a sync is not worth retrying over a colour.
        dependencies.appearance().refresh()

        // Last, and never able to change the result. This is the one place with
        // the time to spend on bodies: nobody is watching a spinner, and the mail
        // it downloads is what makes the *next* tap on a conversation open
        // instantly rather than fetching. A prefetch that could not finish leaves
        // the app exactly as it was before -- reading a body on demand is still
        // the fallback -- so failing the run over it would re-ask the server for
        // changes it has already answered, to retry something optional.
        runCatching { dependencies.bodies().prefetchAll() }

        // After the prefetch rather than before it, so nothing downloaded in the
        // line above is measured against a threshold it has just moved past.
        runCatching { dependencies.bodies().prune() }

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

        /**
         * Asks for a run as soon as there is a network, for a change that could not be sent.
         *
         * WorkManager rather than a coroutine watching connectivity, and that is the whole point: a
         * queued archive has to survive the app being swiped away, which every in-process listener
         * does not. The constraint is what makes this "when the network comes back" rather than
         * "now" — the job simply waits, at no cost, for as long as the phone is in a lift.
         *
         * `KEEP`, so five changes made offline ask for one run rather than five. The work drains
         * the whole queue, so the first request already covers the rest; `REPLACE` would push the
         * run further out each time somebody swiped, which is the opposite of what they want.
         */
        fun requestFlush(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        BACKOFF_MINUTES,
                        TimeUnit.MINUTES,
                    )
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(FLUSH_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** Stops syncing, for when the last account is removed. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }

        private const val FLUSH_NAME = "plmail.flush"

        private const val INTERVAL_MINUTES = 15L

        /** Exponential from ten minutes: a server that is down is usually down for a while. */
        private const val BACKOFF_MINUTES = 10L
    }
}
