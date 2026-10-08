package de.plmail.core.data

import de.plmail.core.datastore.RecentMovesStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Where mail was last moved to, for the top of the "Move to" sheet.
 *
 * A pass-through, and here only because of the module graph: the features see `:core:data` and not
 * the store behind it.
 */
@Singleton
class RecentMoves @Inject constructor(private val store: RecentMovesStore) {

    /** Label keys, newest first. */
    val keys: Flow<List<String>> = store.recent

    suspend fun record(label: Label) = store.record(label.key)
}
