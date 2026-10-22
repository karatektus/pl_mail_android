package de.plmail.feature.contacts

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.jmap.contacts.ContactObject
import de.plmail.jmap.protocol.ContactId

/**
 * Contacts on the phone: the address book, detail with reference emails, the editor, and the
 * duplicate finder.
 *
 * The module owns its own detail, editor and duplicates screens and its own back between them; what
 * :app decides is only whether contacts or mail is on screen — the same way it does for the
 * calendar. The drawer entry is capability-gated in :app, so this screen is only ever opened where
 * the server publishes the contacts capability.
 */
@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val stack = remember { mutableStateListOf<ContactsDestination>(ContactsDestination.List) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    fun push(destination: ContactsDestination) {
        stack.add(destination)
    }

    fun pop(): Boolean {
        // The editor and the detail both manage their own unsaved-changes and
        // delete confirmations; popping here only moves between destinations.
        if (stack.size > 1) {
            val leaving = stack.removeAt(stack.lastIndex)
            if (leaving is ContactsDestination.Detail) viewModel.closeDetail()
            return true
        }
        return false
    }

    BackHandler { if (!pop()) onBack() }

    when (val current = stack.last()) {
        is ContactsDestination.List -> {
            ContactsListScreen(
                onBack = onBack,
                onOpenContact = { push(ContactsDestination.Detail(it)) },
                onNewContact = { push(ContactsDestination.Editor(null)) },
                onFindDuplicates = { push(ContactsDestination.Duplicates) },
                viewModel = viewModel,
            )
        }
        is ContactsDestination.Detail -> {
            ContactDetailScreen(
                contactId = current.id,
                onBack = { pop() },
                onEdit = { push(ContactsDestination.Editor(current.id)) },
                viewModel = viewModel,
            )
        }
        is ContactsDestination.Editor -> {
            val existing: ContactObject? =
                current.id?.let { id -> state.contacts.firstOrNull { it.id == id } }
            ContactEditorScreen(
                existing = existing,
                onBack = { pop() },
                onSaved = { pop() },
                viewModel = viewModel,
            )
        }
        is ContactsDestination.Duplicates -> {
            DuplicatesScreen(
                onBack = { pop() },
                viewModel = viewModel,
            )
        }
    }
}

/** Where the contacts module can be, for its own back stack. */
private sealed interface ContactsDestination {
    data object List : ContactsDestination

    data class Detail(val id: ContactId) : ContactsDestination

    /** Null id means a new contact. */
    data class Editor(val id: ContactId?) : ContactsDestination

    data object Duplicates : ContactsDestination
}
