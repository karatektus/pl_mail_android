package de.plmail.core.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Says that an account can no longer be described incrementally, so its lists have to be paged
 * again.
 *
 * The in-process half of `SyncResult.NeedsRepage`. The durable half is the account's own null
 * `emailState`, which [FeedMediator] reads when a list is created — but a list that is already on
 * screen was created before the sync happened and would go on drawing the rows it had until
 * something told it. This is that something.
 *
 * A [SharedFlow] with no replay: a re-page is an instruction to whatever is watching *now*, and a
 * subscriber arriving afterwards learns the same fact from the cursor, which is the authority. The
 * buffer is there only so that emitting never suspends a sync — a signal nobody is listening for is
 * dropped, which is correct, because there is no list to re-page.
 */
@Singleton
class RepageSignal @Inject constructor() {

    private val _accounts = MutableSharedFlow<String>(extraBufferCapacity = BUFFER)

    /** The accounts asked to re-page, by key. */
    val accounts: SharedFlow<String> = _accounts.asSharedFlow()

    fun repage(accountKey: String) {
        _accounts.tryEmit(accountKey)
    }

    private companion object {
        /** Room for a sync of every account a session realistically holds, without suspending. */
        const val BUFFER = 8
    }
}
