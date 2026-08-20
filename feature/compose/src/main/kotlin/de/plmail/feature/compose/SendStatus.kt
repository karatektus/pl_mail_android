package de.plmail.feature.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.ComposeDraft
import de.plmail.core.data.ScheduledSend
import de.plmail.core.data.SendQueue
import de.plmail.core.data.SendState
import de.plmail.core.designsystem.PaneTone
import de.plmail.core.designsystem.PlMailPane
import de.plmail.core.designsystem.PlMailTheme
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The snackbar the undo window lives in.
 *
 * Hosted over the mail list rather than inside the composer, because the composer has closed by
 * then — that is the entire point of an undo window rather than a confirmation dialog.
 *
 * The window itself is the server's hold now rather than a timer in the app, which changes one
 * thing here: undo can *fail*, and it can arrive too late. Both are shown. A snackbar that said
 * "undone" over a message already delivered would be the worst sentence this app could write.
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
    val scheduledFormat = stringResource(R.string.send_scheduled)
    val tooLate = stringResource(R.string.send_too_late)

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

            is SendState.Scheduled -> {
                // Short, and not the whole story: the bar below keeps the
                // release time reachable until it arrives, so this only has to
                // confirm what just happened.
                snackbars.showSnackbar(
                    message = scheduledFormat.format(Instant.ofEpochMilli(current.sendAt).asWhen()),
                    duration = SnackbarDuration.Short,
                )
                viewModel.acknowledge()
            }

            SendState.TooLate -> {
                snackbars.showSnackbar(tooLate, duration = SnackbarDuration.Long)
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

/**
 * Messages waiting to leave, with the time each one leaves at.
 *
 * Over the mail list rather than in a folder of its own, because there is no folder to put them in:
 * a held submission is still an ordinary draft on the server, and nothing in `Mailbox/get`
 * distinguishes it from one nobody has scheduled. This bar is the only place the release time
 * exists on this device, so it stays reachable until the mail goes rather than living in a snackbar
 * that vanishes in four seconds.
 *
 * Empty — which is nearly always — it draws nothing at all.
 */
@Composable
fun ScheduledSendsBar(
    modifier: Modifier = Modifier,
    viewModel: ScheduledSendsViewModel = hiltViewModel(),
) {
    val sends by viewModel.pending.collectAsStateWithLifecycle()

    if (sends.isEmpty()) return

    Column(
        modifier = modifier.padding(PlMailTheme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny),
    ) {
        sends.take(MAX_ROWS).forEach { send ->
            PlMailPane(tone = PaneTone.RAISED, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(PlMailTheme.spacing.small),
                    horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Schedule, contentDescription = null)

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text =
                                send.subject.ifBlank {
                                    stringResource(R.string.send_scheduled_no_subject)
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        Text(
                            text =
                                stringResource(
                                    R.string.send_scheduled_leaves,
                                    Instant.ofEpochMilli(send.sendAt).asWhen(),
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    TextButton(onClick = { viewModel.cancel(send) }) {
                        Text(stringResource(R.string.send_scheduled_cancel))
                    }
                }
            }
        }

        if (sends.size > MAX_ROWS) {
            val overflow = sends.size - MAX_ROWS

            Text(
                text = pluralStringResource(R.plurals.send_scheduled_more, overflow, overflow),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
     * actually stored. Null back means the cancel did not take — the queue has already put a state
     * on screen saying so, and reopening a composer over it would suggest otherwise.
     */
    fun undo(onReopen: (ComposeRequest) -> Unit) {
        viewModelScope.launch { queue.undo()?.asEditRequest()?.let(onReopen) }
    }

    fun acknowledge() = queue.acknowledge()
}

@HiltViewModel
class ScheduledSendsViewModel @Inject constructor(private val queue: SendQueue) : ViewModel() {

    /**
     * What is still ahead of us.
     *
     * The clock is read per emission rather than held, so a bar composed at half past seven stops
     * offering to cancel an eight o'clock send once eight has passed. `settle` then retires the
     * record for good, once the server has been asked whether the mail actually went.
     */
    val pending: StateFlow<List<ScheduledSend>> =
        queue.schedule
            .map { sends -> sends.filter { it.isPendingAt(System.currentTimeMillis()) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            // On every launch, and cheap: nothing to do unless a release time
            // has passed while the app was closed, which is the case this
            // exists for.
            runCatching { queue.settle() }

            // Then ask the server what it is actually holding. This is what
            // makes a schedule created on a laptop appear here at all, and a
            // cancel made there take the row away -- and it is deliberately
            // *after* settle, so the cheap local pass has already retired
            // anything obviously stale before a round trip is spent.
            runCatching { queue.reconcile() }
        }
    }

    fun cancel(send: ScheduledSend) {
        viewModelScope.launch { runCatching { queue.cancelScheduled(send) } }
    }
}

private const val MAX_ROWS = 3

private fun ComposeDraft.asEditRequest(): ComposeRequest.Edit? = emailId?.let {
    ComposeRequest.Edit(accountKey, it)
}

/**
 * Keeps an open composer open across a rotation, and a share across a process death.
 *
 * Primitives only, and a discriminator rather than a class name, so a rename cannot change what a
 * saved bundle means. Compose has no `Parcelable` for a sealed interface and `Serializable` would
 * make the class's binary shape part of the saved state.
 *
 * `Any` rather than `String` as the element type, because [ComposeRequest.Share] carries lists —
 * recipients, and the staged files that are the whole reason a share survives being killed. An
 * `ArrayList<String>` is `Serializable`, which is what Compose's bundle check actually asks for, so
 * it is as saveable as the strings beside it. Restoring casts to `List<*>` and filters rather than
 * casting to `List<String>`, so nothing here needs an unchecked-cast suppression to compile.
 */
val ComposeRequestSaver: Saver<ComposeRequest?, Any> =
    listSaver<ComposeRequest?, Any>(
        save = { request ->
            when (request) {
                null -> emptyList()
                ComposeRequest.New -> listOf("new")
                is ComposeRequest.Reply ->
                    listOf("reply", request.accountKey, request.emailId, request.all.toString())
                is ComposeRequest.Forward -> listOf("forward", request.accountKey, request.emailId)
                is ComposeRequest.Edit -> listOf("edit", request.accountKey, request.emailId)
                is ComposeRequest.Share ->
                    listOf(
                        "share",
                        ArrayList(request.to),
                        ArrayList(request.cc),
                        ArrayList(request.bcc),
                        request.subject,
                        request.text,
                        ArrayList(request.attachments),
                        ArrayList(request.tooLarge),
                        ArrayList(request.unreadable),
                    )
            }
        },
        restore = { saved ->
            when (saved.getOrNull(0)) {
                "new" -> ComposeRequest.New
                "reply" ->
                    ComposeRequest.Reply(
                        accountKey = saved.text(1),
                        emailId = saved.text(2),
                        all = saved.text(3).toBoolean(),
                    )
                "forward" -> ComposeRequest.Forward(saved.text(1), saved.text(2))
                "edit" -> ComposeRequest.Edit(saved.text(1), saved.text(2))
                "share" ->
                    ComposeRequest.Share(
                        to = saved.texts(1),
                        cc = saved.texts(2),
                        bcc = saved.texts(3),
                        subject = saved.text(4),
                        text = saved.text(5),
                        attachments = saved.texts(6),
                        tooLarge = saved.texts(7),
                        unreadable = saved.texts(8),
                    )
                else -> null
            }
        },
    )

private fun List<Any>.text(index: Int): String = getOrNull(index) as? String ?: ""

private fun List<Any>.texts(index: Int): List<String> =
    (getOrNull(index) as? List<*>)?.filterIsInstance<String>().orEmpty()
