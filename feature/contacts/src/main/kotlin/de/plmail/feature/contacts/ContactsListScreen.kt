@file:Suppress("LocalContextGetResourceValueCall")

package de.plmail.feature.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.core.designsystem.PlMailEmptyState
import de.plmail.jmap.contacts.ContactObject
import de.plmail.jmap.protocol.ContactId

/**
 * The address book: search and the contact list.
 *
 * One round trip per search (debounced in the view model); the address book is small enough that
 * paging is invisible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsListScreen(
    onBack: () -> Unit,
    onOpenContact: (ContactId) -> Unit,
    onNewContact: () -> Unit,
    onFindDuplicates: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val context = LocalContext.current

    var searchActive by remember { mutableStateOf(false) }

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
                title = { Text(stringResource(R.string.contacts)) },
                actions = {
                    IconButton(onClick = onFindDuplicates) {
                        Icon(Icons.Filled.Group, stringResource(R.string.contacts_duplicates))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewContact) {
                Icon(Icons.Filled.Add, stringResource(R.string.contacts_new))
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // The query/active overload: the newer inputField one needs a material3 newer
            // than this project's floor for the same look.
            @Suppress("DEPRECATION")
            SearchBar(
                query = state.query,
                onQueryChange = viewModel::setQuery,
                onSearch = { searchActive = false },
                active = searchActive,
                onActiveChange = { searchActive = it },
                placeholder = { Text(stringResource(R.string.contacts_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Filled.Clear, stringResource(R.string.contacts_close))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {}

            when {
                state.unavailable -> {
                    PlMailEmptyState(
                        icon = Icons.Filled.Contacts,
                        title = stringResource(R.string.contacts),
                        body = stringResource(R.string.contacts_unavailable),
                    )
                }
                state.isLoading && state.contacts.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.loadError != null && state.contacts.isEmpty() -> {
                    PlMailEmptyState(
                        icon = Icons.Filled.Contacts,
                        title = stringResource(R.string.contacts),
                        body = stringResource(R.string.contacts_failed, state.loadError ?: ""),
                        action = {
                            TextButton(onClick = viewModel::refresh) {
                                Text(stringResource(R.string.contacts_retry))
                            }
                        },
                    )
                }
                state.contacts.isEmpty() -> {
                    if (state.query.isNotBlank()) {
                        PlMailEmptyState(
                            icon = Icons.Filled.Search,
                            title = stringResource(R.string.contacts_no_results, state.query),
                        )
                    } else {
                        PlMailEmptyState(
                            icon = Icons.Filled.Contacts,
                            title = stringResource(R.string.contacts_empty),
                            body = stringResource(R.string.contacts_empty_body),
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(state.contacts, key = { it.id.value }) { contact ->
                            ContactRow(
                                contact = contact,
                                photoFor = viewModel::photoFor,
                                onClick = { onOpenContact(contact.id) },
                            )
                        }
                    }
                }
            }
        }
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
private fun ContactRow(
    contact: ContactObject,
    photoFor: suspend (ContactObject) -> ByteArray?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var photo by remember(contact.photoUrl) { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(contact.photoUrl) {
        photo = photoFor(contact)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        ContactPhoto(bytes = photo, initials = contact.initials(), size = 48.dp)

        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            Text(
                text = contact.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle =
                listOfNotNull(
                        contact.company?.takeIf { it.isNotBlank() },
                        contact.primaryEmail()?.email?.takeIf { it.isNotBlank() },
                    )
                    .joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
