package de.plmail.feature.compose

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.ComposeDraft
import de.plmail.core.data.SendQueue
import de.plmail.core.data.SendState
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The snackbar the undo window lives in.
 *
 * Hosted over the mail list rather than inside the composer, because the composer has closed by
 * then — that is the entire point of an undo window rather than a confirmation dialog.
 */
@Composable
fun SendStatusHost(
    snackbars: SnackbarHostState,
    onReopen: (ComposeRequest) -> Unit,
    viewModel: SendStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val pending = stringResource(R.string.send_pending)
    val undo = stringResource(R.string.send_undo)
    val sent = stringResource(R.string.send_done)
    val reopen = stringResource(R.string.send_reopen)
    val failedFormat = stringResource(R.string.send_failed)

    LaunchedEffect(state) {
        when (val current = state) {
            SendState.Idle -> Unit

            is SendState.Pending -> {
                // Indefinite, and dismissed by the queue moving on rather than
                // by a timer of its own: two clocks counting the same six
                // seconds drift, and the one that matters is the one that
                // decides whether the mail goes.
                val result =
                    snackbars.showSnackbar(
                        message = pending,
                        actionLabel = undo,
                        duration = SnackbarDuration.Indefinite,
                    )

                if (result == SnackbarResult.ActionPerformed) viewModel.undo(onReopen)
            }

            SendState.Sent -> {
                snackbars.showSnackbar(sent, duration = SnackbarDuration.Short)
                viewModel.acknowledge()
            }

            is SendState.Failed -> {
                val result =
                    snackbars.showSnackbar(
                        message = failedFormat.format(current.message),
                        actionLabel = reopen,
                        duration = SnackbarDuration.Long,
                    )

                if (result == SnackbarResult.ActionPerformed) {
                    current.draft.asEditRequest()?.let(onReopen)
                }

                viewModel.acknowledge()
            }
        }
    }
}

@HiltViewModel
class SendStatusViewModel @Inject constructor(private val queue: SendQueue) : ViewModel() {

    val state: StateFlow<SendState> = queue.state

    /**
     * Cancels the send and reopens the composer on the draft.
     *
     * By id, not by value: the draft is already in Drafts on the server at this point, so reopening
     * loads it back rather than restoring an in-memory copy that could disagree with what is
     * actually stored.
     */
    fun undo(onReopen: (ComposeRequest) -> Unit) {
        viewModelScope.launch { queue.undo()?.asEditRequest()?.let(onReopen) }
    }

    fun acknowledge() = queue.acknowledge()
}

private fun ComposeDraft.asEditRequest(): ComposeRequest.Edit? = emailId?.let {
    ComposeRequest.Edit(accountKey, it)
}

/**
 * Keeps an open composer open across a rotation.
 *
 * Primitives only, and a discriminator rather than a class name, so a rename cannot change what a
 * saved bundle means. Compose has no `Parcelable` for a sealed interface and `Serializable` would
 * make the class's binary shape part of the saved state.
 */
val ComposeRequestSaver: Saver<ComposeRequest?, Any> =
    listSaver<ComposeRequest?, String>(
        save = { request ->
            when (request) {
                null -> emptyList()
                ComposeRequest.New -> listOf("new")
                is ComposeRequest.Reply ->
                    listOf("reply", request.accountKey, request.emailId, request.all.toString())
                is ComposeRequest.Forward -> listOf("forward", request.accountKey, request.emailId)
                is ComposeRequest.Edit -> listOf("edit", request.accountKey, request.emailId)
            }
        },
        restore = { saved ->
            when (saved.getOrNull(0)) {
                "new" -> ComposeRequest.New
                "reply" ->
                    ComposeRequest.Reply(
                        accountKey = saved[1],
                        emailId = saved[2],
                        all = saved[3].toBoolean(),
                    )
                "forward" -> ComposeRequest.Forward(saved[1], saved[2])
                "edit" -> ComposeRequest.Edit(saved[1], saved[2])
                else -> null
            }
        },
    )
