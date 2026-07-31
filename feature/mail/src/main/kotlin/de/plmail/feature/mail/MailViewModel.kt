package de.plmail.feature.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.FeedRepository
import de.plmail.core.data.MailRepository
import de.plmail.core.database.ThreadEntity
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** An account that is not answering, named so the banner can say which. */
data class UnreachableAccount(val accountKey: String, val displayName: String)

@HiltViewModel
class MailViewModel @Inject constructor(feed: FeedRepository, mail: MailRepository) : ViewModel() {

    /**
     * `cachedIn` so the pages survive a rotation.
     *
     * Without it the list re-collects on every configuration change, which on this product means
     * re-querying somebody's NAS because the user turned their phone sideways.
     */
    val threads: Flow<PagingData<ThreadEntity>> = feed.unifiedInbox().cachedIn(viewModelScope)

    /**
     * Failures resolved to names the user recognises.
     *
     * The account row carries the address; the failure only carries a key. Joining them here keeps
     * the banner from saying `https://nas.local/13`.
     */
    val unreachable: StateFlow<List<UnreachableAccount>> =
        combine(feed.failures, mail.observeAccounts()) { failures, accounts ->
                val names = accounts.associate { it.uid to it.name }

                failures.map {
                    UnreachableAccount(
                        accountKey = it.accountKey,
                        displayName = names[it.accountKey] ?: it.accountKey,
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyList(),
            )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
