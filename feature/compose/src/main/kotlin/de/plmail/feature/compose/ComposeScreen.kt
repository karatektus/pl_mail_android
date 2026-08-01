package de.plmail.feature.compose

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import de.plmail.core.data.StagedAttachment
import de.plmail.core.designsystem.PaneTone
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailPane
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.jmap.mail.DraftComposer

/**
 * Writing a message.
 *
 * The screen is deliberately plain: a From row that is always visible, address lines, a subject, a
 * body, and the quoted original behind a chip. Everything expensive — uploading, saving, the undo
 * window — happens outside it, which is what lets Send close the screen instantly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    request: ComposeRequest,
    onClose: () -> Unit,
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val strings = composeStrings()
    val snackbars = remember { SnackbarHostState() }
    val body = rememberRichTextState()

    LaunchedEffect(request) { viewModel.open(request, strings) }

    // One direction only. The editor owns the text; the ViewModel is told about
    // it. Writing state back into the editor on every recomposition would move
    // the cursor to the end of the line on every keystroke, which is the classic
    // way a Compose text field becomes unusable.
    LaunchedEffect(body.annotatedString) { viewModel.setBody(body.toHtml()) }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            viewModel.attachPicked(uris)
        }

    state.error?.let { error ->
        val message = error.text()

        LaunchedEffect(error) {
            snackbars.showMessage(message)
            viewModel.dismissError()
        }
    }

    // Back saves rather than discards, matching every other mail client: the
    // alternative loses work on a gesture people make by accident.
    BackHandler { viewModel.close(onClose) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose_title)) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.close(onClose) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.compose_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = stringResource(R.string.compose_attach),
                        )
                    }

                    IconButton(onClick = { if (viewModel.send()) onClose() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.compose_send),
                        )
                    }

                    OverflowMenu(onDiscard = { viewModel.discard(onClose) })
                },
            )
        },
    ) { insets ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            return@Scaffold
        }

        val spacing = PlMailTheme.spacing

        Column(
            modifier = Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
        ) {
            FromRow(state = state, onSelected = viewModel::setIdentity)
            PlMailDivider()

            RecipientField(
                label = stringResource(R.string.compose_to),
                addresses = state.draft.to,
                onChanged = viewModel::setTo,
                suggestions = state.suggestions,
                onQueryChanged = viewModel::suggest,
            )

            if (state.isShowingCopyFields) {
                RecipientField(
                    label = stringResource(R.string.compose_cc),
                    addresses = state.draft.cc,
                    onChanged = viewModel::setCc,
                    suggestions = state.suggestions,
                    onQueryChanged = viewModel::suggest,
                )

                RecipientField(
                    label = stringResource(R.string.compose_bcc),
                    addresses = state.draft.bcc,
                    onChanged = viewModel::setBcc,
                    suggestions = state.suggestions,
                    onQueryChanged = viewModel::suggest,
                )
            } else {
                TextButton(
                    onClick = viewModel::showCopyFields,
                    modifier = Modifier.padding(horizontal = spacing.medium),
                ) {
                    Text(stringResource(R.string.compose_show_copy_fields))
                }
            }

            PlMailDivider()

            TextField(
                value = state.draft.subject,
                onValueChange = viewModel::setSubject,
                placeholder = { Text(stringResource(R.string.compose_subject)) },
                singleLine = true,
                colors = flatFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            PlMailDivider()

            if (state.draft.attachments.isNotEmpty()) {
                Attachments(
                    attachments = state.draft.attachments,
                    onRemove = viewModel::detach,
                )
            }

            Formatting(body)

            RichTextEditor(
                state = body,
                placeholder = { Text(stringResource(R.string.compose_body_hint)) },
                // Its own defaults draw the Material filled-field container --
                // a grey block behind the message, which turns "write" into
                // "fill in". The editor is the page here, so it takes the page.
                colors =
                    RichTextEditorDefaults.richTextEditorColors(
                        containerColor = PlMailTheme.colors.surface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = PlMailTheme.colors.accent,
                        placeholderColor = PlMailTheme.colors.fieldPlaceholder,
                        textColor = PlMailTheme.colors.ink,
                    ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
            )

            if (state.quotedHtml.isNotBlank()) {
                Quote(
                    isExpanded = state.isQuoteExpanded,
                    html = state.quotedHtml,
                    onToggle = viewModel::toggleQuote,
                    onRemove = viewModel::removeQuote,
                )
            }

            SaveState(isSaved = state.isSaved, hasId = state.draft.emailId != null)
        }
    }
}

/**
 * Who the message comes from.
 *
 * Always visible, never hidden behind a menu — plMail accounts can hold several sendable addresses
 * and one credential reaches several accounts, so "which of me is this from" is a real question at
 * the moment of writing rather than a setting.
 *
 * Note what selecting a non-primary alias does *not* yet do: the server ignores both the draft's
 * `from` and the submission's `identityId` and sends as the account's own address. Choosing a
 * different **account** works; choosing a different alias within one does not. The ask is queued in
 * `docs/SERVER_REQUESTS.md`, and until it lands the picker shows one entry per account so it cannot
 * promise something the send will not honour.
 */
@Composable
private fun FromRow(state: ComposeUiState, onSelected: (de.plmail.core.data.SendIdentity) -> Unit) {
    var isOpen by remember { mutableStateOf(false) }
    val choices = state.identities.distinctBy { it.accountKey }

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(enabled = choices.size > 1) { isOpen = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.compose_from),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = state.identity?.label ?: stringResource(R.string.compose_from_unknown),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (choices.size > 1) {
            Icon(
                imageVector = Icons.Filled.MoreHoriz,
                contentDescription = stringResource(R.string.compose_choose_sender),
            )
        }

        DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }) {
            choices.forEach { identity ->
                DropdownMenuItem(
                    text = { Text(identity.label) },
                    onClick = {
                        onSelected(identity)
                        isOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Attachments(attachments: List<StagedAttachment>, onRemove: (StagedAttachment) -> Unit) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = PlMailTheme.spacing.medium,
                    vertical = PlMailTheme.spacing.tiny,
                ),
        verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny),
    ) {
        attachments.forEach { attachment ->
            InputChip(
                selected = false,
                onClick = { onRemove(attachment) },
                label = {
                    // The size beside the name, because the interesting failure
                    // is a 40MB video the server will refuse, and that has to be
                    // visible before Send rather than after.
                    Text("${attachment.name} · ${attachment.size.asFileSize()}")
                },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription =
                            stringResource(R.string.compose_remove_attachment, attachment.name),
                    )
                },
            )
        }
    }
}

/**
 * The quoted original, collapsed.
 *
 * Not in the editor, and not expanded by default. A thread of thirty messages would otherwise open
 * the composer scrolled past the end of what the user is writing.
 */
@Composable
private fun Quote(
    isExpanded: Boolean,
    html: String,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = PlMailTheme.spacing.medium,
                    vertical = PlMailTheme.spacing.small,
                ),
        horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = isExpanded,
            onClick = onToggle,
            label = { Text(stringResource(R.string.compose_quoted_text)) },
        )

        AssistChip(
            onClick = onRemove,
            label = { Text(stringResource(R.string.compose_remove_quote)) },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
        )
    }

    if (isExpanded) {
        // Plain text rather than a WebView. This is the user's own outgoing
        // message being previewed, not someone else's mail being rendered, and
        // a second WebView on this screen would fight the editor for focus and
        // the IME.
        PlMailPane(
            tone = PaneTone.SUNKEN,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = PlMailTheme.spacing.medium)
                    .padding(bottom = PlMailTheme.spacing.medium),
        ) {
            Text(
                text = html.strippedOfTags(),
                style = MaterialTheme.typography.bodySmall,
                color = PlMailTheme.colors.inkMuted,
                modifier = Modifier.fillMaxWidth().padding(PlMailTheme.spacing.medium),
            )
        }
    }
}

@Composable
private fun SaveState(isSaved: Boolean, hasId: Boolean) {
    if (!hasId) return

    Text(
        text = stringResource(if (isSaved) R.string.compose_saved else R.string.compose_saving),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun OverflowMenu(onDiscard: () -> Unit) {
    var isOpen by remember { mutableStateOf(false) }

    IconButton(onClick = { isOpen = true }) {
        Icon(
            imageVector = Icons.Filled.MoreHoriz,
            contentDescription = stringResource(R.string.compose_more),
        )
    }

    DropdownMenu(expanded = isOpen, onDismissRequest = { isOpen = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.compose_discard)) },
            onClick = {
                isOpen = false
                onDiscard()
            },
        )
    }
}

/**
 * Bold, italic, underline and a bullet list.
 *
 * Deliberately four controls rather than a full toolbar. These are the ones people actually reach
 * for on a phone, and every additional one is another HTML construct the editor has to serialise
 * correctly into a message that has to render in someone else's client.
 */
@Composable
private fun Formatting(state: com.mohamedrejeb.richeditor.model.RichTextState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PlMailTheme.spacing.small),
        horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny),
    ) {
        IconButton(onClick = { state.toggleSpanStyle(BOLD) }) {
            Icon(Icons.Filled.FormatBold, stringResource(R.string.compose_bold))
        }

        IconButton(onClick = { state.toggleSpanStyle(ITALIC) }) {
            Icon(Icons.Filled.FormatItalic, stringResource(R.string.compose_italic))
        }

        IconButton(onClick = { state.toggleSpanStyle(UNDERLINE) }) {
            Icon(Icons.Filled.FormatUnderlined, stringResource(R.string.compose_underline))
        }

        IconButton(onClick = { state.toggleUnorderedList() }) {
            Icon(
                Icons.AutoMirrored.Filled.FormatListBulleted,
                stringResource(R.string.compose_bullets),
            )
        }
    }
}

@Composable
private fun composeStrings(): ComposeStrings =
    ComposeStrings(
        attributionFormat = stringResource(R.string.compose_attribution),
        forwardLabels =
            DraftComposer.ForwardLabels(
                heading = stringResource(R.string.compose_forwarded_heading),
                from = stringResource(R.string.compose_from_label),
                date = stringResource(R.string.compose_date_label),
                subject = stringResource(R.string.compose_subject_label),
                to = stringResource(R.string.compose_to_label),
                cc = stringResource(R.string.compose_cc_label),
            ),
    )

@Composable
private fun ComposeError.text(): String =
    when (this) {
        ComposeError.NoRecipients -> stringResource(R.string.compose_error_no_recipients)
        ComposeError.NoIdentity -> stringResource(R.string.compose_error_no_identity)
        ComposeError.OriginalUnavailable -> stringResource(R.string.compose_error_no_original)
        is ComposeError.SaveFailed -> stringResource(R.string.compose_error_save_failed, message)
    }

/**
 * A field with no box around it.
 *
 * The composer is a document, not a form: an outlined box per line turns "write a message" into
 * "fill this in". The separation comes from the hairlines between rows instead, and the focus
 * indicator is the cursor plus the accent underline Material draws anyway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun flatFieldColors() =
    TextFieldDefaults.colors(
        focusedContainerColor = PlMailTheme.colors.surface,
        unfocusedContainerColor = PlMailTheme.colors.surface,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        cursorColor = PlMailTheme.colors.accent,
        focusedPlaceholderColor = PlMailTheme.colors.fieldPlaceholder,
        unfocusedPlaceholderColor = PlMailTheme.colors.fieldPlaceholder,
    )

private suspend fun SnackbarHostState.showMessage(message: String) {
    // The previous one is dropped rather than queued: a stack of stale errors
    // behind a fresh one is noise, and only the newest is actionable.
    currentSnackbarData?.dismiss()
    showSnackbar(message)
}

/** Enough of the quote to recognise it, without a second HTML renderer on this screen. */
internal fun String.strippedOfTags(): String =
    replace(Regex("<br\\s*/?>|</p>|</div>|</blockquote>"), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .lines()
        .joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

private val BOLD =
    androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)

private val ITALIC =
    androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)

private val UNDERLINE =
    androidx.compose.ui.text.SpanStyle(
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
    )
