package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A preferences store that emits on **every** write, equal or not.
 *
 * A `MutableStateFlow` would conflate duplicate values itself, which would make a test for
 * `distinctUntilChanged` pass whether or not the store under test has one — the shape of a test
 * that guards nothing. The real DataStore emits once per successful write regardless of what
 * changed, so this does too, and that is the entire reason it exists.
 *
 * Shared rather than repeated per test file: two copies in one package do not compile, and the
 * second one being written is the moment somebody discovers that.
 */
internal class EmittingDataStore : DataStore<Preferences> {
    private var current: Preferences = emptyPreferences()
    private val emissions = MutableSharedFlow<Preferences>(replay = 1)

    init {
        emissions.tryEmit(current)
    }

    override val data: Flow<Preferences>
        get() = emissions

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
        current = transform(current)
        emissions.emit(current)

        return current
    }

    /** Writes a key nothing under test cares about, to stand in for an unrelated component. */
    suspend fun write(key: Preferences.Key<String>, value: String) {
        updateData { existing ->
            mutablePreferencesOf(*existing.asMap().map { (k, v) -> pair(k, v) }.toTypedArray())
                .apply { this[key] = value }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun pair(key: Preferences.Key<*>, value: Any): Preferences.Pair<Any> =
        (key as Preferences.Key<Any>) to value
}
