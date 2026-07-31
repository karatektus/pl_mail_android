package de.plmail.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The searches this person has run, most recent first.
 *
 * Kept because a mail search is usually a *re-*search — the same sender, the same project — and
 * retyping `from:accounts@ is:unread` on a phone keyboard is the difference between the feature
 * being used and not.
 *
 * Stored as one newline-separated string rather than as a `stringSetPreferencesKey`: a set has no
 * order, and order is the entire content of a "recent" list.
 *
 * Newline is a safe separator because [record] flattens whitespace first, which a query wants
 * regardless — the tokeniser splits on the space character alone, so a tab or newline inside a
 * search string is already junk that would silently become part of a term.
 *
 * **Deliberately not encrypted, and deliberately capped.** These are queries, not credentials — but
 * a query names people the user corresponds with, so an unbounded history is a growing record of
 * that on disk. [LIMIT] is what keeps it a convenience rather than an archive.
 */
@Singleton
class RecentSearchStore @Inject constructor(private val preferences: DataStore<Preferences>) {

    val recent: Flow<List<String>> = preferences.data.map { stored -> decode(stored[KEY]) }

    /**
     * Records [query], moving it to the front if it is already there.
     *
     * Moved rather than duplicated, so running the same search twice does not fill the list with
     * one entry. Blank queries are ignored: an empty search box was not a search.
     */
    suspend fun record(query: String) {
        val flattened = query.replace(WHITESPACE, " ").trim()

        if (flattened.isEmpty()) return

        preferences.edit { store ->
            val existing = decode(store[KEY])
            val updated = (listOf(flattened) + existing.filterNot { it == flattened }).take(LIMIT)

            store[KEY] = updated.joinToString("\n")
        }
    }

    /** Forgets one entry — the dismiss on a chip. */
    suspend fun forget(query: String) {
        preferences.edit { store ->
            store[KEY] = decode(store[KEY]).filterNot { it == query }.joinToString("\n")
        }
    }

    /** Forgets all of them. The user asked; there is no soft version of this. */
    suspend fun clear() {
        preferences.edit { it.remove(KEY) }
    }

    /** Blank lines are dropped, so a trailing newline cannot become an empty chip. */
    private fun decode(raw: String?): List<String> =
        raw?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

    private companion object {
        val KEY = stringPreferencesKey("recent_searches")

        /**
         * Enough to cover "the thing I was just looking at"; short enough to stay a convenience.
         */
        const val LIMIT = 8

        val WHITESPACE = Regex("\\s+")
    }
}
