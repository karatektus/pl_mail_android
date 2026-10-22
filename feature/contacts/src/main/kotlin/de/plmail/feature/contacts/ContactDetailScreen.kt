package de.plmail.feature.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.jmap.contacts.ContactAddressEntry
import de.plmail.jmap.contacts.ContactEmailEntry
import de.plmail.jmap.contacts.ContactPhoneEntry
import de.plmail.jmap.contacts.ReferenceEmail
import de.plmail.jmap.protocol.ContactId
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * One contact: profile header, details, notes, and the REFERENCE EMAILS section — every non-deleted
 * message to or from them (inbox, sent, drafts, trash, labels, starred), newest first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    contactId: ContactId,
    onBack: () -> Unit,
    onEdit: (ContactId) -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detail?.takeIf { it.contact.id == contactId }
    val snackbars = remember { SnackbarHostState() }

    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(contactId) { viewModel.openDetail(contactId) }

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
                title = { Text(detail?.contact?.displayName ?: "") },
                actions = {
                    IconButton(onClick = { onEdit(contactId) }) {
                        Icon(Icons.Filled.Edit, stringResource(R.string.contacts_edit))
                    }
                    IconButton(onClick = { confirmingDelete = true }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.contacts_delete))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        when (val d = detail) {
            null -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                ContactDetailContent(
                    detail = d,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }
    }

    if (confirmingDelete) {
        val name = detail?.contact?.displayName.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        viewModel.deleteContact(contactId)
                    }
                ) {
                    Text(stringResource(R.string.contacts_delete_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.contacts_cancel))
                }
            },
            text = { Text(stringResource(R.string.contacts_delete_confirm, name)) },
        )
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ContactsEvent.Message -> snackbars.showSnackbar(event.text)
                is ContactsEvent.Merged -> Unit
            }
        }
    }
}

@Composable
private fun ContactDetailContent(
    detail: ContactDetailState,
    modifier: Modifier = Modifier,
) {
    val contact = detail.contact

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        // Profile header.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        ) {
            ContactPhoto(bytes = detail.photo, initials = contact.initials(), size = 72.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = contact.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                val sub =
                    listOfNotNull(
                            contact.jobTitle?.takeIf { it.isNotBlank() },
                            contact.company?.takeIf { it.isNotBlank() },
                        )
                        .joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (contact.tags.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                contact.tags.forEach { tag ->
                    AssistChip(onClick = {}, label = { Text(tag) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        DetailSection {
            contact.emails.forEach { EmailRow(it) }
            contact.phones.forEach { PhoneRow(it) }
            contact.addresses.forEach { AddressRow(it) }
        }

        contact.notes
            ?.takeIf { it.isNotBlank() }
            ?.let { notes ->
                DetailSection(title = null) {
                    Text(text = notes, style = MaterialTheme.typography.bodyLarge)
                }
            }

        // REFERENCE EMAILS — every non-deleted message tied to this contact.
        DetailSection(title = stringResource(R.string.contacts_reference_emails)) {
            when {
                detail.referencesLoading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
                detail.referencesError != null -> {
                    Text(
                        text = stringResource(R.string.contacts_failed, detail.referencesError),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                detail.referenceEmails.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.contacts_reference_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    detail.referenceEmails.forEach { email ->
                        ReferenceEmailRow(email)
                        HorizontalDivider()
                    }
                }
            }
        }

        // Notes about this person, and a way to add one. Draws nothing on a
        // server without notes.
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            de.plmail.feature.notes.LinkedNotesSection(
                type = de.plmail.jmap.methods.NoteLink.CONTACT,
                id = contact.id.value,
                title = contact.displayName,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DetailSection(
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        title?.let {
            Text(
                text = it.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        content()
    }
}

@Composable
private fun EmailRow(entry: ContactEmailEntry) {
    LabeledRow(
        label = entry.label.replaceFirstChar { it.uppercase() },
        primary = entry.isPrimary,
    ) {
        Text(text = entry.email, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PhoneRow(entry: ContactPhoneEntry) {
    LabeledRow(
        label = entry.label.replaceFirstChar { it.uppercase() },
        primary = entry.isPrimary,
    ) {
        Text(text = entry.phone, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AddressRow(entry: ContactAddressEntry) {
    val line = entry.oneLine()
    if (line.isEmpty()) return
    LabeledRow(label = entry.label.replaceFirstChar { it.uppercase() }, primary = false) {
        Text(text = line, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LabeledRow(
    label: String,
    primary: Boolean,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.width(72.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (primary) {
                Text(
                    text = "★",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        content()
    }
}

@Composable
private fun ReferenceEmailRow(email: ReferenceEmail) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Email,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = email.subject?.takeIf { it.isNotBlank() } ?: "(no subject)",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val from = email.from
            val fromLine =
                listOfNotNull(
                        from?.name?.takeIf { it.isNotBlank() },
                        from?.email?.takeIf { it.isNotBlank() },
                    )
                    .joinToString(" <")
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(email.mailbox) })
                if (fromLine.isNotEmpty()) {
                    Text(
                        text = fromLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            email.date?.let { date ->
                Text(
                    text = shortDate(date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (email.isStarred) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.StarOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** A short, forgiving date — the server sends ISO 8601; anything else shows as-is. */
private fun shortDate(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}
    .getOrDefault(iso)
