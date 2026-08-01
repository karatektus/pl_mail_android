package de.plmail.core.data

import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.PushState
import de.plmail.core.datastore.PushStateStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What the transport carrying pushes can say about itself.
 *
 * Implemented in `:app`, because that is where UnifiedPush lives and only that module knows which
 * distributors are installed. The seam runs this way round for the same reason `MailDestinations`
 * does: a build with a different push transport — or none — swaps the implementation and nothing
 * here changes.
 */
interface PushTransport {
    /** The distributor app currently carrying pushes, or null when none has been chosen. */
    fun distributor(): String?

    /** Every distributor installed. Zero and "more than one" are different problems to explain. */
    fun installed(): List<String>

    /** Tries to register again. False when there is nothing on the device that could deliver. */
    fun register(): Boolean
}

/** One account, and whether it is actually working. */
data class AccountHealth(
    val accountKey: String,
    val name: String,
    val server: String,
    /** When a sync last *succeeded*. Null means never, which is different from "not lately". */
    val lastSyncedAt: Long?,
    /** What went wrong since then, in the server's or the transport's own words. */
    val lastError: String?,
    /**
     * Whether this account has a sync cursor at all.
     *
     * Worth showing on its own, because an account with no cursor is not broken — it re-pages
     * instead of syncing incrementally — and "no cursor" is the state a client is in after
     * `cannotCalculateChanges`, which reads as a failure in the log and is not one.
     */
    val hasSyncCursor: Boolean,
)

/** Everything the diagnostics screen draws, in one value. */
data class DiagnosticsReport(
    val server: String?,
    val accounts: List<AccountHealth>,
    val push: PushState,
    /** The distributor package currently carrying pushes, read live from the transport. */
    val distributor: String?,
    val installedDistributors: List<String>,
    /**
     * Whether the server agrees the subscription is verified, once it has been asked.
     *
     * Null until somebody presses the button, and that is deliberate: answering it costs a round
     * trip, and a diagnostics screen that quietly makes requests every time it is opened is one
     * more thing hitting a server somebody is already worried about.
     */
    val pushVerified: Boolean? = null,
    /** What went wrong the last time the checks were run, if anything. */
    val checkError: String? = null,
    val isChecking: Boolean = false,
)

/**
 * The state of the app's own machinery, assembled for someone who has to fix it.
 *
 * This audience runs the server. When mail stops arriving they are simultaneously the user and the
 * administrator, and the difference between "the subscription was never verified", "the distributor
 * was uninstalled" and "the credential expired" is the difference between a two-minute fix and an
 * evening. Every one of those states used to look identical from inside the app: nothing wrong, no
 * mail.
 */
@Singleton
class Diagnostics
@Inject
constructor(
    private val accountsRepository: AccountsRepository,
    private val credentials: CredentialStore,
    private val pushState: PushStateStore,
    private val push: PushRepository,
    private val deltaSync: DeltaSync,
    private val transport: PushTransport,
) {

    /**
     * The report, live.
     *
     * Read from what the app already records rather than probed — opening this screen must not
     * itself be a load on the server, or the first thing somebody does when their box is struggling
     * is add traffic to it.
     */
    val report: Flow<DiagnosticsReport> =
        combine(
            // The user's order, so this screen lists mailboxes in the same
            // sequence as everything else. A diagnostics screen that reorders
            // the accounts it is diagnosing is one more thing to double-check
            // at the moment somebody is least able to.
            accountsRepository.ordered,
            pushState.state,
            credentials.connection.map { it?.address?.origin },
        ) { accounts, push, server ->
            DiagnosticsReport(
                server = server,
                accounts =
                    accounts.map { account ->
                        AccountHealth(
                            accountKey = account.uid,
                            name = account.name,
                            server = account.serverId,
                            lastSyncedAt = account.lastSyncedAt,
                            lastError = account.lastSyncError,
                            hasSyncCursor = account.emailState != null,
                        )
                    },
                push = push,
                distributor = transport.distributor(),
                installedDistributors = transport.installed(),
            )
        }

    /**
     * Syncs every account now and asks the server whether the push subscription is verified.
     *
     * The one place in the app that makes requests because somebody asked it to rather than because
     * mail needed fetching. Both halves matter: the sync proves the credential still works and
     * writes its own outcome into the account rows, and the verification check answers the question
     * that no amount of local state can — an unverified subscription is registered, looks correct
     * from every angle here, and receives nothing for ever.
     */
    suspend fun check(): CheckOutcome {
        val results = accountsRepository.all().map { deltaSync.sync(it.uid) }

        val verified =
            pushState.state.first().subscriptionId?.let { id -> runCatching { push.isLive(id) } }

        return CheckOutcome(
            // A sync that says "re-page" is not a failure, and neither is one
            // that found nothing. Only a genuine Failed is worth reporting up
            // here -- the per-account rows carry the detail either way.
            failures = results.filterIsInstance<SyncResult.Failed>().map { it.error },
            pushVerified = verified?.getOrNull(),
            pushCheckError = verified?.exceptionOrNull()?.message,
        )
    }

    /**
     * Asks the transport to register again, for a device that has just had a distributor installed.
     */
    fun retryPush(): Boolean = transport.register()

    data class CheckOutcome(
        val failures: List<Throwable>,
        val pushVerified: Boolean?,
        val pushCheckError: String?,
    )
}
