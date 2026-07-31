package de.plmail.core.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The undo-send window, which exists only here.
 *
 * plMail's web composer has a grace period; `EmailSubmission/set` deliberately does **not** apply
 * it to JMAP clients, on the grounds that a client calling it asked to send now. `maxDelayedSend`
 * is 0, so there is no scheduled send to lean on either. That leaves the delay as the client's,
 * which is the right place for it anyway — undo has to be reachable from the device that pressed
 * send.
 *
 * The ordering is the part worth being careful about. The draft is written to the server **first**,
 * then the window runs, then the submission goes out. Sending straight from memory after a delay
 * would mean a process death inside those seconds loses the message with no trace of it anywhere;
 * this way the worst case is a draft in Drafts, which is exactly what the user would expect to
 * find.
 *
 * It follows that undo has nothing to undo on the server: the mail was never submitted. The
 * composer reopens on the saved draft.
 */
@Singleton
class SendQueue
@Inject
constructor(
    private val compose: DraftSender,
    /**
     * A scope that deliberately outlives the composer.
     *
     * The screen closes the instant Send is tapped — that is the whole point — so a
     * `viewModelScope` would cancel the send along with it. Injected rather than constructed here
     * so the window can be tested against a virtual clock instead of six real seconds.
     */
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val mutex = Mutex()
    private var job: Job? = null

    private val _state = MutableStateFlow<SendState>(SendState.Idle)

    /** What the snackbar over the mail list should be saying. */
    val state: StateFlow<SendState> = _state.asStateFlow()

    /**
     * Saves [draft], waits out the window, then submits.
     *
     * One at a time: a second send while one is pending waits for the first rather than racing it,
     * because both would otherwise write to the same account's Email state and one would be
     * rejected with `stateMismatch` for reasons the user could not connect to anything they did.
     */
    fun enqueue(draft: ComposeDraft) {
        scope.launch {
            mutex.withLock {
                job?.join()

                job = scope.launch {
                    // Tracked separately so a failure reports the draft as
                    // the *server* last knew it. Reporting the one handed in
                    // would drop the id a successful save had just assigned,
                    // and reopening the composer on that would create a
                    // second draft beside the first.
                    var current = draft

                    try {
                        current = compose.save(draft)

                        _state.value =
                            SendState.Pending(
                                draft = current,
                                endsAt = System.currentTimeMillis() + UNDO_WINDOW_MS,
                            )

                        delay(UNDO_WINDOW_MS)
                        compose.submit(current)

                        _state.value = SendState.Sent
                    } catch (cancelled: CancellationException) {
                        // Undo. `runCatching` here would swallow this and
                        // report "StandaloneCoroutine was cancelled" as a
                        // send failure -- a red snackbar for the user's own
                        // deliberate action.
                        throw cancelled
                    } catch (failure: Exception) {
                        // Never swallowed. The composer has already closed,
                        // so a send that fails silently is a message the
                        // user believes they have sent.
                        _state.value =
                            SendState.Failed(
                                draft = current,
                                message = failure.message ?: "The message could not be sent.",
                            )
                    }
                }
            }
        }
    }

    /**
     * Cancels a send still inside its window and hands the draft back.
     *
     * Returns null when there was nothing to cancel — the window had already elapsed, or the
     * snackbar was tapped twice. Reporting that as an error would be worse than doing nothing: by
     * then the mail really has been sent, and there is no unsending it.
     */
    suspend fun undo(): ComposeDraft? {
        val pending = _state.value as? SendState.Pending ?: return null

        mutex.withLock {
            job?.cancel()
            job = null
        }

        _state.value = SendState.Idle

        return pending.draft
    }

    /** Clears a terminal state once it has been shown. */
    fun acknowledge() {
        if (_state.value !is SendState.Pending) _state.value = SendState.Idle
    }

    companion object {
        /**
         * Six seconds, matching the undo window the rest of the app uses for archive and trash.
         *
         * Long enough to read a snackbar and react, short enough that nobody wonders whether the
         * mail went. Gmail offers 5–30s as a setting; that belongs with the rest of the settings in
         * M10 rather than as a constant nobody can reach.
         */
        const val UNDO_WINDOW_MS = 6_000L
    }
}

/**
 * The half of [ComposeRepository] the queue needs.
 *
 * An interface for one reason: [SendQueue]'s ordering — save first, wait, then submit — is the rule
 * that decides whether a process death inside the undo window loses someone's mail, and it is worth
 * a test that runs on the JVM in milliseconds rather than one that needs a database, a content
 * resolver and a server.
 */
interface DraftSender {

    suspend fun save(draft: ComposeDraft): ComposeDraft

    suspend fun submit(draft: ComposeDraft)
}

/** Where a send has got to. */
sealed interface SendState {

    data object Idle : SendState

    /**
     * Saved to Drafts, waiting out the undo window.
     *
     * [endsAt] rather than a remaining duration, so a snackbar recomposing at 60fps does not need
     * the queue to tick, and a screen rotation does not restart the countdown.
     */
    data class Pending(val draft: ComposeDraft, val endsAt: Long) : SendState

    data object Sent : SendState

    /** [draft] so the composer can be reopened on exactly what failed to go. */
    data class Failed(val draft: ComposeDraft, val message: String) : SendState
}
