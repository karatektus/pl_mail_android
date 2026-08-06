package de.plmail.core.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.plmail.core.database.PlMailDatabase
import de.plmail.jmap.mail.EmailAddress
import de.plmail.jmap.methods.ContactAutocomplete
import de.plmail.jmap.methods.ContactSuggestion
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.RequestBuilder
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Who the composer offers while a recipient is being typed.
 *
 * **The server ranks, and that is the whole change.** `Contact/autocomplete` reads plMail's own
 * harvested address book — every address this user has written to or heard from, however long ago,
 * ordered `frequency DESC, lastSeenAt DESC` — which is the same query and the same order the web
 * composer runs. Two rankings would make the suggestion order depend on which device somebody
 * happened to be composing from, and a freshly paired phone would offer nothing at all until it had
 * synced enough mail to build an address book of its own.
 *
 * Three sources, in this order, and each is there for a different reason:
 *
 * 1. **The server**, for the ranking above.
 * 2. **The device address book**, when the permission has already been granted. A supplement, not a
 *    ranking: it holds people this user has never mailed, which is precisely what the server cannot
 *    know. Never asked for on its own account — a mail client demanding contacts access on first
 *    launch is the behaviour this product's audience left other clients over — so the field offers
 *    it and works without it.
 * 3. **Mail in the local cache**, only when the server could not be reached. The old primary
 *    source, kept as the offline answer rather than deleted: a phone in a tunnel still has the last
 *    few hundred messages it synced, and the person being addressed is usually in them. Its ranking
 *    is worse than the server's — recency over the cache, rather than frequency over everything —
 *    which is exactly why it is no longer first.
 *
 * **Debounced here rather than at the call site.** The server is frequently a Raspberry Pi
 * advertising four concurrent requests, and a recipient field emits one query per keystroke; a
 * type-ahead that spends the whole request budget is a type-ahead that makes the message list stop
 * loading. A call superseded by a later keystroke cancels itself rather than returning, so the
 * suggestions can never be overwritten by an answer to a query the user has already typed past.
 */
@Singleton
class ContactSuggestions
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val database: PlMailDatabase,
    private val clients: AccountClients,
) {

    /**
     * Which keystroke is current.
     *
     * A plain counter rather than a job or a channel because the caller owns the coroutine: the
     * composer launches one per keystroke and does not cancel the last, so the only thing this can
     * do is decline to be the answer.
     */
    private val generation = AtomicLong(0)

    /**
     * The last thing the server said, so a network that drops mid-typing does not empty the list.
     */
    @Volatile private var lastFromServer: List<ContactSuggestion> = emptyList()

    /**
     * Addresses matching [query], best first.
     *
     * Suspends for [DEBOUNCE_MILLIS] first. If another call has started in the meantime this one
     * abandons itself — a cancellation rather than a return, so the caller's `launch` ends quietly
     * and nothing writes a stale list over a fresh one.
     */
    suspend fun suggest(query: String, limit: Int = LIMIT): List<EmailAddress> =
        suggestions(query, limit).map { it.address }

    /**
     * The same answer with the ranking signals still attached.
     *
     * `frequency`, `lastSeenAt` and `isCorrespondent` are what let a list *explain* its order
     * rather than merely have one — marking somebody the user actually writes to, for instance.
     * Nothing draws them yet; they are decoded rather than dropped because a field a client never
     * parsed is a field nobody notices has arrived.
     */
    suspend fun suggestions(query: String, limit: Int = LIMIT): List<ContactSuggestion> {
        val term = query.trim()
        if (term.length < MIN_QUERY) return emptyList()

        val mine = generation.incrementAndGet()
        delay(DEBOUNCE_MILLIS)
        if (generation.get() != mine)
            throw CancellationException("Superseded by a later keystroke.")

        val ranked = fromServer(term, limit)
        if (generation.get() != mine)
            throw CancellationException("Superseded by a later keystroke.")

        val supplement =
            withContext(Dispatchers.IO) { deviceContacts(term, limit).map(::asSuggestion) }

        // The address is the key, as the server's own unique index has it. An
        // entry without one cannot be put in a recipient field and cannot be
        // told apart from any other entry without one.
        return (ranked + supplement)
            .filter { it.email.isNotBlank() }
            .distinctBy { it.email.lowercase() }
            .take(limit)
    }

    /** Whether the device address book may be read. Checked, never assumed. */
    fun mayReadDeviceContacts(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * `Contact/autocomplete`, or the offline answer.
     *
     * The account is whichever one the session nominates for contacts: **every** account answers
     * from the same user-wide address book, unlike calendars, because a suggestion has no id and so
     * none of the (accountId, id) collisions that made calendars single-account.
     */
    private suspend fun fromServer(term: String, limit: Int): List<ContactSuggestion> {
        val client = clients.current() ?: return offline(term, limit)

        return try {
            val session = client.session()

            // Absence is the signal, as everywhere else: an instance without the
            // extension is a supported instance, not a broken one, and the local
            // scan is the honest answer there rather than a workaround.
            if (session.contacts == null) return offline(term, limit)

            val account = session.primaryContactsAccount ?: return offline(term, limit)
            val cap = session.contacts?.maxSuggestions ?: ContactAutocomplete.MAX_LIMIT

            val method =
                ContactAutocomplete.of(account, term, limit.coerceAtMost(cap)) ?: return emptyList()

            val request = RequestBuilder(using = Capability.USING_CONTACTS)
            val handle = request.add(method)

            client.send(request).result(handle).list.also { lastFromServer = it }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // A server that is asleep, a NAS rebooting, a phone in a lift. The
            // composer must keep suggesting; the last answer is kept because
            // somebody halfway through typing a name has usually just seen it.
            (lastFromServer.filter { it.matches(term) } + offline(term, limit))
                .distinctBy { it.email.lowercase() }
                .take(limit)
        }
    }

    /**
     * Everyone in the cached mail who matches, newest first.
     *
     * Case-insensitive and matched anywhere in the string rather than only at the start: people
     * search for a surname, and `LIKE '%x%'` is what makes "meyer" find "anna.meyer@…".
     */
    private suspend fun offline(term: String, limit: Int): List<ContactSuggestion> =
        withContext(Dispatchers.IO) {
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

            found.values.take(limit).map(::asSuggestion)
        }

    /**
     * The device address book, or nothing at all.
     *
     * `CONTENT_FILTER_URI` rather than a hand-built selection: it is the query the platform
     * optimises, it matches names and addresses together, and it applies the user's own
     * display-name settings. A failure is swallowed deliberately — a contacts provider that refuses
     * (a work profile, a locked device, an OEM that ships none) must not take the ranked
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

    private fun ContactSuggestion.matches(term: String): Boolean =
        email.contains(term, ignoreCase = true) || name?.contains(term, ignoreCase = true) == true

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
        const val LIMIT = ContactAutocomplete.DEFAULT_LIMIT

        /**
         * How long a keystroke has to be the last one before it costs a request.
         *
         * Long enough to swallow a burst of typing, short enough that a pause reads as instant. The
         * budget being protected is the server's: four concurrent requests, shared with the message
         * list somebody may still be scrolling.
         */
        const val DEBOUNCE_MILLIS = 220L

        /**
         * How far back the offline scan reaches.
         *
         * A ceiling rather than the whole table: the answer somebody wants is always in recent
         * mail, and scanning 200k rows to find an address last used in 2019 would make the field
         * stutter for everyone.
         */
        const val SCAN_ROWS = 400

        fun asSuggestion(address: EmailAddress): ContactSuggestion =
            ContactSuggestion(name = address.name, email = address.email.orEmpty())
    }
}
