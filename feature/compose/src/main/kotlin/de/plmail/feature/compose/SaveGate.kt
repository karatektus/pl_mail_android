package de.plmail.feature.compose

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One draft save at a time, and a save that has started always finishes.
 *
 * **Why a save cannot simply be cancelled.** The first save of a draft *creates* it, and the id it
 * gets back is the only handle the composer will ever have on it. Cancelling the coroutine while
 * that request is in flight does not stop the server: the draft is created, and the answer naming
 * it is thrown away. Everything after that works from a draft with no id — a discard has nothing to
 * move to Trash, a close saves a second copy, a send goes out as a new message — and the one the
 * server created stays in Drafts for good, with nothing on the phone that knows it exists.
 *
 * So the composer cancels the *debounce* that would start the next save, and waits, through
 * [afterSaves], for the save already on its way to come back with its id.
 */
internal class SaveGate {

    private val lock = Mutex()

    /** Runs [block] as the only save, to completion even if the caller is cancelled meanwhile. */
    suspend fun <T> save(block: suspend () -> T): T = lock.withLock {
        withContext(NonCancellable) { block() }
    }

    /** Runs [block] once no save is in progress, so what it reads is what the last one wrote. */
    suspend fun <T> afterSaves(block: suspend () -> T): T = lock.withLock { block() }
}
