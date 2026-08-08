package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns "something moved on the server" into a sync of the accounts it moved in.
 *
 * One applier for two channels. A Web Push payload and an EventSource `state` event are the *same*
 * JMAP `StateChange` — a map of account id to the state tokens that changed — and the only thing
 * that differs is how it reached the device. Two copies of this decision is how the two paths come
 * to disagree about when a sync is worth making, on a product where one of them only runs while the
 * app is on screen and the other only while it is not.
 *
 * Lifted out of [PushRepository] rather than left there: the stream has no subscription id, no
 * verification handshake and no endpoint, and would have had to reach through all three to get at
 * four lines.
 */
@Singleton
class StateChangeApplier
@Inject
constructor(
    private val database: PlMailDatabase,
    private val deltaSync: DeltaSync,
    /**
     * What makes a tapped notification open a conversation rather than fetch one.
     *
     * The sync above stores list-row properties only, so without this the push path ends with the
     * message on the device and its body still on the server — and the reader the user reaches from
     * the notification shade is the one screen guaranteed to be about mail that arrived seconds ago.
     */
    private val bodies: BodyPrefetcher,
) {

    /**
     * Syncs every announced account this device has not already caught up with.
     *
     * **The comparison is the reason this class is worth having.** An announcement says where the
     * server's Email state *now* is; the account row says where this device's cursor is. Equal
     * means the sync that would be started has already been made, and `Email/changes` would answer
     * with an empty list. Without the check a chatty stream — one bulk label edit in the browser is
     * dozens of events within a few seconds — becomes one `Email/changes` per event against a
     * machine that advertises four concurrent requests and is frequently a Raspberry Pi.
     *
     * It only became safe to skip on that comparison once `AccountDao.setEmailStateIfAbsent`
     * existed. While page loads wrote the cursor unconditionally, the stored token meant "wherever
     * the last page happened to be read at", which could sit *ahead* of changes that had never been
     * fetched — so this would have answered "up to date" for an account that was missing mail and
     * skipped the one sync that could have found it. The column only became a claim about what has
     * been applied when page loads stopped stepping over it.
     *
     * Tokens other than `Email` are neither compared nor required. A `Mailbox` or `Thread` state
     * moving on its own is rare and cheap to act on, and refusing to sync for it would leave a
     * label renamed in the browser waiting for the next unrelated message; only the mail cursor can
     * prove there is nothing to fetch, so only it may skip.
     */
    suspend fun apply(changed: Map<String, Map<String, String>>) {
        if (changed.isEmpty()) return

        // Read once for the whole announcement rather than per named account. A
        // StateChange names every account behind one credential, and this runs on
        // the push path -- a query per account is a query per mailbox on every
        // notification, for a table that fits in a screenful.
        val accounts = database.accounts().all()

        changed.forEach { (accountId, states) ->
            accounts
                .filter { it.accountId == accountId }
                .forEach { account ->
                    val announced = states[EMAIL_TYPE]

                    if (announced != null && announced == account.emailState) return@forEach

                    deltaSync.sync(account.uid)

                    // After the sync, because the sync is what puts the message
                    // rows there for this to find. Swallowed: a body that could
                    // not be fetched now is fetched when the conversation is
                    // opened, which is what happened before this existed at all.
                    runCatching { bodies.prefetch(account.uid) }
                }
        }
    }

    private companion object {
        /** The JMAP type name a `StateChange` keys the mail cursor under. */
        const val EMAIL_TYPE = "Email"
    }
}
