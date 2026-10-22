@file:Suppress("LocalContextGetResourceValueCall")

package de.plmail.feature.contacts

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.plmail.core.data.NewContactPhoto
import de.plmail.jmap.contacts.ContactAddressEntry
import de.plmail.jmap.contacts.ContactDraft
import de.plmail.jmap.contacts.ContactEmailEntry
import de.plmail.jmap.contacts.ContactObject
import de.plmail.jmap.contacts.ContactPhoneEntry
import kotlinx.coroutines.launch

/**
 * One row of the editor's dynamic lists — a value, a label, and whether it is the primary.
 *
 * A class rather than a Triple so the rows read as what they are.
 */
class FormEntry(
    value: String = "",
    label: String = "other",
    isPrimary: Boolean = false,
) {
    var value by mutableStateOf(value)
    var label by mutableStateOf(label)
    var isPrimary by mutableStateOf(isPrimary)
}

/** An address as the editor holds it. */
class FormAddress(
    label: String = "other",
    street: String = "",
    city: String = "",
    region: String = "",
    postcode: String = "",
    country: String = "",
) {
    var label by mutableStateOf(label)
    var street by mutableStateOf(street)
    var city by mutableStateOf(city)
    var region by mutableStateOf(region)
    var postcode by mutableStateOf(postcode)
    var country by mutableStateOf(country)

    fun isBlank(): Boolean = listOf(street, city, region, postcode, country).all { it.isBlank() }
}

/**
 * The editor's state, seeded from a contact or blank for a new one.
 *
 * Held in the composable rather than the view model: it is form state, and the view model only
 * needs the finished [ContactDraft] at save time.
 */
class ContactFormState(contact: ContactObject?) {
    var displayName by mutableStateOf(contact?.displayName.orEmpty())
    var givenName by mutableStateOf(contact?.givenName.orEmpty())
    var familyName by mutableStateOf(contact?.familyName.orEmpty())
    var nickname by mutableStateOf(contact?.nickname.orEmpty())
    var company by mutableStateOf(contact?.company.orEmpty())
    var jobTitle by mutableStateOf(contact?.jobTitle.orEmpty())
    var notes by mutableStateOf(contact?.notes.orEmpty())
    var tags by mutableStateOf(contact?.tags?.joinToString(", ").orEmpty())

    val emails =
        mutableStateListOf<FormEntry>().also { list ->
            contact?.emails?.forEach { list += FormEntry(it.email, it.label, it.isPrimary) }
            if (list.isEmpty()) list += FormEntry()
        }
    val phones =
        mutableStateListOf<FormEntry>().also { list ->
            contact?.phones?.forEach { list += FormEntry(it.phone, it.label, it.isPrimary) }
            if (list.isEmpty()) list += FormEntry()
        }
    val addresses =
        mutableStateListOf<FormAddress>().also { list ->
            contact?.addresses?.forEach {
                list +=
                    FormAddress(
                        it.label,
                        it.street.orEmpty(),
                        it.city.orEmpty(),
                        it.region.orEmpty(),
                        it.postcode.orEmpty(),
                        it.country.orEmpty(),
                    )
            }
        }

    var nameError by mutableStateOf(false)

    fun toDraft(): ContactDraft? {
        nameError = displayName.isBlank()
        if (nameError) return null

        fun List<FormEntry>.toEmailEntries() = filter {
            it.value.isNotBlank()
        }
            .map { ContactEmailEntry(it.value.trim(), it.label, it.isPrimary) }
            .demoteExtraPrimaries()

        fun List<FormEntry>.toPhoneEntries() = filter {
            it.value.isNotBlank()
        }
            .map { ContactPhoneEntry(it.value.trim(), it.label, it.isPrimary) }
            .demoteExtraPrimaries()

        return ContactDraft(
            displayName = displayName.trim(),
            givenName = givenName.trim(),
            familyName = familyName.trim(),
            nickname = nickname.trim(),
            company = company.trim(),
            jobTitle = jobTitle.trim(),
            notes = notes.trim(),
            tags = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            emails = emails.toEmailEntries(),
            phones = phones.toPhoneEntries(),
            addresses =
                addresses
                    .filter { !it.isBlank() }
                    .map {
                        ContactAddressEntry(
                            label = it.label,
                            street = it.street.trim().ifEmpty { null },
                            city = it.city.trim().ifEmpty { null },
                            region = it.region.trim().ifEmpty { null },
                            postcode = it.postcode.trim().ifEmpty { null },
                            country = it.country.trim().ifEmpty { null },
                        )
                    },
        )
    }

    /**
     * One primary per list: the first row marked primary wins, the rest are demoted. A form with
     * two primaries is a mistake, not a choice.
     */
    private fun <T> List<T>.demoteExtraPrimaries(): List<T> where T : Any {
        var seen = false
        return map {
            val isPrimary =
                when (it) {
                    is ContactEmailEntry -> it.isPrimary
                    is ContactPhoneEntry -> it.isPrimary
                    else -> false
                }
            if (!isPrimary || !seen) {
                seen = seen || isPrimary
                it
            } else {
                @Suppress("UNCHECKED_CAST")
                when (it) {
                    is ContactEmailEntry -> it.copy(isPrimary = false) as T
                    is ContactPhoneEntry -> it.copy(isPrimary = false) as T
                    else -> it
                }
            }
        }
    }
}

/**
 * Create or edit a contact: names, company, dynamic email/phone/address rows, tags, notes, and a
 * photo picked from the gallery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditorScreen(
    existing: ContactObject?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val form = remember(existing?.id?.value) { ContactFormState(existing) }

    var pickedPhoto by remember { mutableStateOf<NewContactPhoto?>(null) }
    var pickedPreview by remember { mutableStateOf<ByteArray?>(null) }
    var removePhoto by remember { mutableStateOf(false) }
    var existingPhoto by remember { mutableStateOf<ByteArray?>(null) }
    var discarding by remember { mutableStateOf(false) }

    // The existing photo, so the editor shows what it is replacing.
    LaunchedEffect(existing?.id?.value) {
        existing?.let { existingPhoto = viewModel.photoFor(it) }
    }

    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
                .getOrNull()
            if (bytes == null || bytes.isEmpty()) return@rememberLauncherForActivityResult
            if (bytes.size > 5_000_000) {
                // Mirrors the repository's own ceiling; the message names the limit.
                scope.launch {
                    snackbars.showSnackbar(context.getString(R.string.contacts_photo_too_large))
                }
                return@rememberLauncherForActivityResult
            }
            val type = context.contentResolver.getType(uri) ?: "image/jpeg"
            pickedPhoto = NewContactPhoto("photo", type, bytes)
            pickedPreview = bytes
            removePhoto = false
        }

    fun save() {
        val draft = form.toDraft() ?: return
        if (existing == null) {
            viewModel.createContact(draft, pickedPhoto)
        } else {
            viewModel.updateContact(existing.id, draft, pickedPhoto, removePhoto)
        }
    }

    BackHandler { discarding = true }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { discarding = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.contacts_back),
                        )
                    }
                },
                title = {
                    Text(
                        stringResource(
                            if (existing == null) R.string.contacts_new else R.string.contacts_edit
                        )
                    )
                },
                actions = {
                    IconButton(onClick = ::save) {
                        Icon(Icons.Filled.Check, stringResource(R.string.contacts_save))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Photo row.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val previewBytes = pickedPreview ?: existingPhoto?.takeUnless { removePhoto }
                ContactPhoto(
                    bytes = previewBytes,
                    initials = form.displayName.take(2).ifBlank { "?" },
                    size = 96.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { photoPicker.launch("image/*") }) {
                        Text(stringResource(R.string.contacts_pick_photo))
                    }
                    if (previewBytes != null || existing?.photoUrl != null) {
                        TextButton(
                            onClick = {
                                pickedPhoto = null
                                pickedPreview = null
                                removePhoto = true
                            }
                        ) {
                            Text(stringResource(R.string.contacts_remove_photo))
                        }
                    }
                }
            }

            OutlinedTextField(
                value = form.displayName,
                onValueChange = {
                    form.displayName = it
                    form.nameError = false
                },
                label = { Text(stringResource(R.string.contacts_display_name)) },
                isError = form.nameError,
                supportingText = {
                    if (form.nameError)
                        Text(stringResource(R.string.contacts_display_name_required))
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.givenName,
                    onValueChange = { form.givenName = it },
                    label = { Text(stringResource(R.string.contacts_given_name)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.familyName,
                    onValueChange = { form.familyName = it },
                    label = { Text(stringResource(R.string.contacts_family_name)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
            OutlinedTextField(
                value = form.nickname,
                onValueChange = { form.nickname = it },
                label = { Text(stringResource(R.string.contacts_nickname)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.company,
                    onValueChange = { form.company = it },
                    label = { Text(stringResource(R.string.contacts_company)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.jobTitle,
                    onValueChange = { form.jobTitle = it },
                    label = { Text(stringResource(R.string.contacts_job_title)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }

            EditorSection(title = stringResource(R.string.contacts_emails)) {
                form.emails.forEachIndexed { index, entry ->
                    EntryRow(
                        entry = entry,
                        onRemove = { form.emails.removeAt(index) },
                        valueLabel = "Email",
                    )
                }
                TextButton(onClick = { form.emails += FormEntry() }) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.contacts_add_email))
                }
            }

            EditorSection(title = stringResource(R.string.contacts_phones)) {
                form.phones.forEachIndexed { index, entry ->
                    EntryRow(
                        entry = entry,
                        onRemove = { form.phones.removeAt(index) },
                        valueLabel = "Phone",
                    )
                }
                TextButton(onClick = { form.phones += FormEntry() }) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.contacts_add_phone))
                }
            }

            EditorSection(title = stringResource(R.string.contacts_addresses)) {
                form.addresses.forEachIndexed { index, address ->
                    AddressEditor(address = address, onRemove = { form.addresses.removeAt(index) })
                }
                TextButton(onClick = { form.addresses += FormAddress() }) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.contacts_add_address))
                }
            }

            OutlinedTextField(
                value = form.tags,
                onValueChange = { form.tags = it },
                label = { Text(stringResource(R.string.contacts_tags)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = form.notes,
                onValueChange = { form.notes = it },
                label = { Text(stringResource(R.string.contacts_notes)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (discarding) {
        AlertDialog(
            onDismissRequest = { discarding = false },
            confirmButton = {
                TextButton(onClick = onBack) { Text(stringResource(R.string.contacts_close)) }
            },
            dismissButton = {
                TextButton(onClick = { discarding = false }) {
                    Text(stringResource(R.string.contacts_cancel))
                }
            },
            text = { Text(stringResource(R.string.contacts_cancel)) },
        )
    }

    // Leave the editor once the save lands — the list refreshes underneath.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is ContactsEvent.Message && event.text == ContactsViewModel.SAVED) {
                onSaved()
            } else if (event is ContactsEvent.Message) {
                snackbars.showSnackbar(event.text)
            }
        }
    }
}

@Composable
private fun EditorSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        content()
    }
}

@Composable
private fun EntryRow(
    entry: FormEntry,
    onRemove: () -> Unit,
    valueLabel: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = entry.value,
            onValueChange = { entry.value = it },
            label = { Text(valueLabel) },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        Spacer(Modifier.width(8.dp))
        LabelPicker(label = entry.label, onLabel = { entry.label = it })
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Checkbox(checked = entry.isPrimary, onCheckedChange = { entry.isPrimary = it })
            Text(
                text = stringResource(R.string.contacts_primary),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, stringResource(R.string.contacts_delete))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelPicker(
    label: String,
    onLabel: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("home", "work", "other")
    val labels =
        mapOf(
            "home" to stringResource(R.string.contacts_label_home),
            "work" to stringResource(R.string.contacts_label_work),
            "other" to stringResource(R.string.contacts_label_other),
        )

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = labels[label] ?: label,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).width(120.dp),
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labels[option] ?: option) },
                    onClick = {
                        onLabel(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AddressEditor(
    address: FormAddress,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabelPicker(label = address.label, onLabel = { address.label = it })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, stringResource(R.string.contacts_delete))
            }
        }
        OutlinedTextField(
            value = address.street,
            onValueChange = { address.street = it },
            label = { Text(stringResource(R.string.contacts_street)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = address.city,
                onValueChange = { address.city = it },
                label = { Text(stringResource(R.string.contacts_city)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = address.postcode,
                onValueChange = { address.postcode = it },
                label = { Text(stringResource(R.string.contacts_postcode)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = address.region,
                onValueChange = { address.region = it },
                label = { Text(stringResource(R.string.contacts_region)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = address.country,
                onValueChange = { address.country = it },
                label = { Text(stringResource(R.string.contacts_country)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
    }
}
