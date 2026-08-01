package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.methods.EmailChanges
import de.plmail.jmap.methods.EmailGet
import de.plmail.jmap.methods.ThreadGet
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.JmapError
import de.plmail.jmap.protocol.RequestBuilder
import de.plmail.jmap.protocol.StateToken
import javax.inject.Inject
import javax.inject.Singleton

/** What one account's sync did, so the diagnostics screen has something true to show. */
sealed interface SyncResult {
    data class Updated(val fetched: Int, val destroyed: Int) : SyncResult

    /** Nothing had changed. The common case, and it must cost one request. */
    data object UpToDate : SyncResult

    /**
     * The server can no longer answer from the stored cursor, so the list has to be re-paged.
     *
     * Not a failure: `cannotCalculateChanges` is what a server says when the change log no longer
     * reaches back far enough, which is the ordinary consequence of an app not being opened for a
     * while.
     */
    data object NeedsRepage : SyncResult

    data class Failed(val error: Throwable) : SyncResult
}

/**
 * Brings one account up to date from its stored state token.
 *
 * `Email/changes` tells us *which* messages moved, never what they now contain, so every sync is a
 * changes loop followed by hydration. Both halves are deliberately bounded:
 *
 * - **256 changes per call**, the server's own maximum, looping while `hasMoreChanges`.
 * - **100 ids per `Email/get`**, well under the permitted 500. The server hydrates full Doctrine
 *   entities to answer, and this is frequently a Raspberry Pi — asking for 500 at once is how a
 *   sync becomes the reason someone's server is unresponsive.
 * - **A loop ceiling.** Past it, catching up change-by-change is slower than throwing the cursor
 *   away and re-paging, which is what a client returning after a long absence should do anyway.
 *
 * A first sync is *not* this. `Email/changes` from state `"0"` reports nothing about existing mail
 * — it only keeps an already-populated client current — so an account with no cursor is sent
 * straight to re-paging rather than being told, wrongly, that it is up to date.
 */
@Singleton
class DeltaSync
@Inject
constructor(
    private val database: PlMailDatabase,
    private val clients: AccountClients,
    private val mail: MailRepository,
) {

    suspend fun sync(accountKey: String): SyncResult {
        val account = database.accounts().byUid(accountKey) ?: return SyncResult.UpToDate
        val client = clients.forAccount(accountKey) ?: return SyncResult.UpToDate

        // No cursor means this client has never paged the account. Asking
        // Email/changes from "0" would answer "nothing changed", which is true
        // and useless: it reports changes since the beginning of time, not the
        // mail that already exists.
        val since = account.emailState ?: return SyncResult.NeedsRepage

        return try {
            val outcome = run(client, AccountId(account.accountId), accountKey, StateToken(since))

            mail.recordSync(accountKey, at = System.currentTimeMillis(), error = null)
            outcome
        } catch (resync: JmapError) {
            if (resync.requiresResync) {
                // The one condition that justifies discarding a cursor.
                database.accounts().setEmailState(accountKey, null)
                SyncResult.NeedsRepage
            } else {
                mail.recordSync(accountKey, at = null, error = resync.message)
                SyncResult.Failed(resync)
            }
        } catch (failed: Exception) {
            mail.recordSync(accountKey, at = null, error = failed.message)
            SyncResult.Failed(failed)
        }
    }

    private suspend fun run(
        client: JmapClient,
        accountId: AccountId,
        accountKey: String,
        since: StateToken,
    ): SyncResult {
        var cursor = since
        var fetched = 0
        var destroyed = 0
        var rounds = 0

        while (true) {
            val request = RequestBuilder()
            val changes = request.add(EmailChanges(accountId, cursor))
            val result = client.send(request).result(changes)

            if (result.destroyed.isNotEmpty()) {
                database
                    .emails()
                    .delete(result.destroyed.map { StoreKey.objectKey(accountKey, it.value) })

                destroyed += result.destroyed.size
            }

            fetched += hydrate(client, accountId, accountKey, result.changed)

            cursor = StateToken(result.newState)

            // Written every round rather than at the end: a sync interrupted
            // by the process dying should resume from where it got to, not
            // replay every change it had already applied.
            database.accounts().setEmailState(accountKey, cursor.value)

            if (!result.hasMoreChanges) break

            if (++rounds >= MAX_ROUNDS) {
                // Further than this and a full re-page is cheaper than
                // continuing to walk the log.
                database.accounts().setEmailState(accountKey, null)
                return SyncResult.NeedsRepage
            }
        }

        return if (fetched == 0 && destroyed == 0) SyncResult.UpToDate
        else SyncResult.Updated(fetched, destroyed)
    }

    /**
     * Re-fetches changed messages in chunks.
     *
     * List-row properties only. A change notification says a message moved, not that anyone is
     * about to read it, and pulling bodies here would turn a background sync into a multi-megabyte
     * download for mail nobody has opened.
     */
    private suspend fun hydrate(
        client: JmapClient,
        accountId: AccountId,
        accountKey: String,
        ids: List<EmailId>,
    ): Int {
        var count = 0

        ids.chunked(HYDRATION_CHUNK).forEach { chunk ->
            val request = RequestBuilder()
            val get = request.add(EmailGet(accountId, ids = chunk))

            // In the same request, for the same reason the pager does it: snooze
            // lives on the conversation, so a sync that only re-fetched messages
            // would rebuild the row without it. That matters more here than
            // anywhere — this is the path a *server-side* change arrives on, so
            // it is where mail snoozed from the web, or woken by the server's own
            // scheduled job, becomes true on the device.
            val threads =
                request.add(ThreadGet.forEmailsOf(accountId, get.reference("/list/*/threadId")))

            val results = client.send(request)
            val emails = results.result(get).list

            mail.storeEmails(
                accountKey,
                emails,
                results.result(threads).list,
                fetchedAt = System.currentTimeMillis(),
            )
            count += emails.size
        }

        return count
    }

    private companion object {
        /**
         * 100, not the permitted 500.
         *
         * The server builds full entities to answer a get, and the audience runs it on hardware
         * where the difference between 100 and 500 is the difference between a sync and an outage.
         */
        const val HYDRATION_CHUNK = 100

        /**
         * Rounds of 256 changes before giving up and re-paging.
         *
         * Twenty is roughly five thousand changes — past that the client has been away long enough
         * that the pages it would rebuild are the ones the user is going to look at anyway.
         */
        const val MAX_ROUNDS = 20
    }
}
