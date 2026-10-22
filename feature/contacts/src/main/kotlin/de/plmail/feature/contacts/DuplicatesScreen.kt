@file:Suppress("LocalContextGetResourceValueCall")

package de.plmail.feature.contacts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.designsystem.PlMailEmptyState
import de.plmail.jmap.contacts.ContactObject
import de.plmail.jmap.contacts.DuplicatePair
import de.plmail.jmap.protocol.ContactId

/**
 * The duplicate finder: server-computed candidate pairs with confidence and human-readable reasons,
 * a keep-picker per pair, merge, and a 90-day undo.
 *
 * The pairs are the server's — same normalized email/phone, name+company, fuzzy matches — because
 * the normalization the tiers depend on lives server-side. The client draws them and asks which
 * contact survives; it never computes its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    onBack: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val context = LocalContext.current

    var merging by remember { mutableStateOf<DuplicatePair?>(null) }
    var keepId by remember { mutableStateOf<ContactId?>(null) }

    LaunchedEffect(Unit) { viewModel.loadDuplicates() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.contacts_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.contacts_duplicates)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        when {
            state.duplicatesLoading -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.duplicatesError != null -> {
                PlMailEmptyState(
                    icon = Icons.Filled.Group,
                    title = stringResource(R.string.contacts_duplicates),
                    body = stringResource(R.string.contacts_failed, state.duplicatesError ?: ""),
                    action = {
                        TextButton(onClick = viewModel::loadDuplicates) {
                            Text(stringResource(R.string.contacts_retry))
                        }
                    },
                    modifier = Modifier.padding(padding),
                )
            }
            state.duplicates.isEmpty() -> {
                PlMailEmptyState(
                    icon = Icons.Filled.Group,
                    title = stringResource(R.string.contacts_duplicates),
                    body = stringResource(R.string.contacts_duplicates_empty),
                    modifier = Modifier.padding(padding),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.duplicates, key = { it.ids.joinToString { id -> id.value } }) { pair
                        ->
                        DuplicatePairCard(
                            pair = pair,
                            contacts = state.contacts,
                            photoFor = viewModel::photoFor,
                            onMerge = { keep ->
                                keepId = keep
                                merging = pair
                            },
                        )
                    }
                }
            }
        }
    }

    val pair = merging
    if (pair != null) {
        val keep = keepId
        val others = pair.ids.filter { it != keep }
        val keptName = state.contacts.firstOrNull { it.id == keep }?.displayName.orEmpty()
        AlertDialog(
            onDismissRequest = { merging = null },
            confirmButton = {
                TextButton(
                    enabled = keep != null,
                    onClick = {
                        merging = null
                        if (keep != null) viewModel.merge(keep, others)
                    },
                ) {
                    Text(stringResource(R.string.contacts_merge))
                }
            },
            dismissButton = {
                TextButton(onClick = { merging = null }) {
                    Text(stringResource(R.string.contacts_cancel))
                }
            },
            text = {
                Text(stringResource(R.string.contacts_merge_confirm, others.size, keptName))
            },
        )
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ContactsEvent.Message -> snackbars.showSnackbar(event.text)
                is ContactsEvent.Merged -> {
                    val result =
                        snackbars.showSnackbar(
                            message =
                                context.getString(R.string.contacts_merged_into, event.keptName),
                            actionLabel = context.getString(R.string.contacts_undo),
                        )
                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                        viewModel.undoMerge(event.mergeRecordId)
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicatePairCard(
    pair: DuplicatePair,
    contacts: List<ContactObject>,
    photoFor: suspend (ContactObject) -> ByteArray?,
    onMerge: (ContactId) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Default keep: the most complete contact — the one with a photo wins ties, then the one
    // with more filled fields. The user can still pick the other.
    var keepId by
        remember(pair) {
            mutableStateOf(
                pair.ids
                    .mapNotNull { id -> contacts.firstOrNull { it.id == id } }
                    .maxByOrNull { (if (it.photoUrl != null) 1000 else 0) + completeness(it) }
                    ?.id
            )
        }

    Card(
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${pair.confidence}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                pair.reasons.forEach { reason ->
                    AssistChip(onClick = {}, label = { Text(reason) })
                    Spacer(Modifier.width(4.dp))
                }
            }

            if (!pair.autoMergeable) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.contacts_caution),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            pair.ids.forEach { id ->
                val contact = contacts.firstOrNull { it.id == id }
                if (contact != null) {
                    DuplicateCandidateRow(
                        contact = contact,
                        photoFor = photoFor,
                        selected = keepId == id,
                        onSelect = { keepId = id },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    enabled = keepId != null,
                    onClick = { keepId?.let(onMerge) },
                ) {
                    Text(stringResource(R.string.contacts_merge))
                }
            }
        }
    }
}

/** A rough completeness score for the default keep-picker: filled fields win. */
private fun completeness(contact: ContactObject): Int {
    var score = 0
    if (!contact.givenName.isNullOrBlank()) score++
    if (!contact.familyName.isNullOrBlank()) score++
    if (!contact.company.isNullOrBlank()) score++
    if (!contact.jobTitle.isNullOrBlank()) score++
    if (!contact.notes.isNullOrBlank()) score++
    score += contact.emails.size
    score += contact.phones.size
    score += contact.addresses.size
    return score
}

@Composable
private fun DuplicateCandidateRow(
    contact: ContactObject,
    photoFor: suspend (ContactObject) -> ByteArray?,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    var photo by remember(contact.photoUrl) { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(contact.photoUrl) {
        photo = photoFor(contact)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .padding(vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        ContactPhoto(bytes = photo, initials = contact.initials(), size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = contact.displayName, style = MaterialTheme.typography.titleSmall)
            val sub =
                listOfNotNull(
                        contact.company?.takeIf { it.isNotBlank() },
                        contact.primaryEmail()?.email?.takeIf { it.isNotBlank() },
                    )
                    .joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = stringResource(R.string.contacts_keep),
            style = MaterialTheme.typography.labelLarge,
            color =
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
