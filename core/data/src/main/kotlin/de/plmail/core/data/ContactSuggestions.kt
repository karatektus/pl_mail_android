package de.plmail.core.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.plmail.core.database.PlMailDatabase
import de.plmail.jmap.mail.EmailAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Who the composer offers while a recipient is being typed.
 *
 * Two sources, in this order:
 *
 * 1. **Mail already in the cache** — everyone who has written to this user, and everyone this user
 *    has written to. Free, always available, and by far the better ranking signal: the person
 *    mailed yesterday is the one being typed.
 * 2. **The device address book**, when the permission has been granted. Never asked for on its own
 *    account — a mail client demanding contacts access on first launch is exactly the behaviour
 *    this product's audience left other clients over — so the field offers it and works without it.
 *
 * plMail harvests contacts server-side and exposes them at `/contacts/autocomplete`, but that is an
 * HTML route rather than a JMAP method, and building against those is a non-negotiable. The ask is
 * queued in `docs/SERVER_REQUESTS.md`; until it lands, this is a local answer to a local question
 * rather than a second implementation of the server's ranking.
 */
@Singleton
class ContactSuggestions
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val database: PlMailDatabase,
) {

    /**
     * Addresses matching [query], best first.
     *
     * Case-insensitive and matched anywhere in the string rather than only at the start: people
     * search for a surname, and `LIKE '%x%'` is what makes "meyer" find "anna.meyer@…".
     */
    suspend fun suggest(query: String, limit: Int = LIMIT): List<EmailAddress> =
        withContext(Dispatchers.IO) {
            val term = query.trim()
            if (term.length < MIN_QUERY) return@withContext emptyList()

            val pattern = "%${term.escapeForLike()}%"
            val found = LinkedHashMap<String, EmailAddress>()

            // Recency order is preserved by insertion order: the DAO returns
            // newest first, and a LinkedHashMap keeps the first spelling of a
            // name that appears more than once.
            database.emails().sendersLike(pattern, SCAN_ROWS).forEach { row ->
                found.remember(EmailAddress(row.name, row.address))
            }

            database.emails().recipientsLike(pattern, SCAN_ROWS).forEach { row ->
                (row.toJson.parseAddresses() + row.ccJson.parseAddresses())
                    // The LIKE matched the row, not the address inside it, so
                    // every recipient of a matching message would otherwise be
                    // offered — including the four people who happened to be
                    // copied in.
                    .filter { it.matches(term) }
                    .forEach { found.remember(it) }
            }

            (found.values + deviceContacts(term, limit)).distinctBy { it.identity }.take(limit)
        }

    /** Whether the device address book may be read. Checked, never assumed. */
    fun mayReadDeviceContacts(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The device address book, or nothing at all.
     *
     * `CONTENT_FILTER_URI` rather than a hand-built selection: it is the query the platform
     * optimises, it matches names and addresses together, and it applies the user's own
     * display-name settings. A failure is swallowed deliberately — a contacts provider that refuses
     * (a work profile, a locked device, an OEM that ships none) must not take the mail-derived
     * suggestions down with it.
     */
    private fun deviceContacts(query: String, limit: Int): List<EmailAddress> {
        if (!mayReadDeviceContacts()) return emptyList()

        return runCatching {
                val uri =
                    ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI.buildUpon()
                        .appendPath(query)
                        .build()

                context.contentResolver
                    .query(
                        uri,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
                            ContactsContract.CommonDataKinds.Email.ADDRESS,
                        ),
                        null,
                        null,
                        null,
                    )
                    ?.use { cursor ->
                        buildList {
                            var seen = 0

                            while (cursor.moveToNext() && seen < limit) {
                                val address = cursor.getString(1)?.trim().orEmpty()
                                if (address.isEmpty()) continue

                                add(EmailAddress(cursor.getString(0), address))
                                seen++
                            }
                        }
                    }
                    .orEmpty()
            }
            .getOrDefault(emptyList())
    }

    private fun MutableMap<String, EmailAddress>.remember(address: EmailAddress) {
        val key = address.identity
        if (key.isEmpty()) return

        putIfAbsent(key, address)
    }

    private fun String?.parseAddresses(): List<EmailAddress> =
        this?.let { runCatching { Wire.json.decodeFromString(Wire.addresses, it) }.getOrNull() }
            .orEmpty()

    private fun EmailAddress.matches(term: String): Boolean =
        email?.contains(term, ignoreCase = true) == true ||
            name?.contains(term, ignoreCase = true) == true

    /**
     * Escapes the two characters `LIKE` treats as wildcards.
     *
     * A user typing `%` would otherwise match every address they have ever seen, and `_` would
     * match one character of anything — neither looks like a bug, they just quietly return the
     * wrong people.
     */
    private fun String.escapeForLike(): String = replace("%", "").replace("_", "")

    private companion object {
        const val MIN_QUERY = 2
        const val LIMIT = 8

        /**
         * How far back the address scan reaches.
         *
         * A ceiling rather than the whole table: this runs on every keystroke behind a debounce,
         * and the answer someone wants is always in recent mail. Scanning 200k rows to find an
         * address last used in 2019 would make the field stutter for everyone.
         */
        const val SCAN_ROWS = 400
    }
}
