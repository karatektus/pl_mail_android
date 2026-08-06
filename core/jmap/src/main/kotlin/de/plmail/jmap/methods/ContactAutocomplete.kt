package de.plmail.jmap.methods

import de.plmail.jmap.mail.EmailAddress
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.JmapMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `Contact/autocomplete` — who to offer while a recipient is being typed.
 *
 * **Not RFC 8621 and not the JMAP Contacts draft**, which is why the verb is `autocomplete` rather
 * than `query`: a `/query` promises ids for a `/get` to resolve, and there is no `Contact/get`, no
 * id space and nothing here a client may create. The suggestion carries the address itself, and the
 * address is the key — the server's own `uniq_contact_user_email` is what makes that true.
 *
 * The ranking is the server's, deliberately. It is `frequency DESC, lastSeenAt DESC` over the
 * *whole* harvested address book, which is exactly the thing a phone cannot do for itself: a client
 * ranking the mail it happens to have cached offers a different order from the web composer, and a
 * freshly paired device offers nothing at all.
 *
 * Two ways to get this wrong, both of which the server reports rather than absorbs:
 *
 * - **A blank query is `invalidArguments`**, not an empty list. A `LIKE '%%'` matches everybody, so
 *   the eight most-mailed people would appear under a field nobody has typed into. [of] refuses to
 *   build one, because the round trip that would be refused is a round trip against a Raspberry Pi.
 * - **`filter`, `sort` and `position` are refused by name.** They are the reasonable guess, since
 *   every other query-ish method here takes them, and a server that ignored them would answer with
 *   a correct-looking unfiltered page.
 */
class ContactAutocomplete
private constructor(
    private val accountId: AccountId,
    private val query: String,
    private val limit: Int?,
) : JmapMethod<ContactAutocompleteResult> {

    override val name = "Contact/autocomplete"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put("query", query)
        limit?.let { put("limit", it) }
    }

    override fun decode(json: Json, arguments: JsonObject): ContactAutocompleteResult =
        json.decodeFromJsonElement(ContactAutocompleteResult.serializer(), arguments)

    companion object {
        /** What the server uses when the client says nothing. Advertised in the session. */
        const val DEFAULT_LIMIT = 8

        /** The server's ceiling. Advertised too; asking past it is capped, not refused. */
        const val MAX_LIMIT = 50

        /**
         * A call for [query], or null when there is nothing to ask.
         *
         * The guard is here rather than at the call site because "the user has cleared the field"
         * and "the user has typed a space" are the same input, they reach the composer from three
         * places, and the server's answer to both is a refused request. Trimmed as the server
         * trims, so the query echoed back matches the one that was sent.
         */
        fun of(accountId: AccountId, query: String, limit: Int? = null): ContactAutocomplete? {
            val term = query.trim()
            if (term.isEmpty()) return null

            return ContactAutocomplete(accountId, term, limit?.coerceIn(1, MAX_LIMIT))
        }
    }
}

/**
 * The answer, with both normalised arguments echoed back.
 *
 * [query] is the trimmed form and [limit] the capped one, so a client that asked for 500 can see it
 * was given 50 rather than conclude the address book holds fifty people.
 */
@Serializable
data class ContactAutocompleteResult(
    val accountId: String = "",
    val query: String = "",
    val limit: Int = ContactAutocomplete.DEFAULT_LIMIT,
    val list: List<ContactSuggestion> = emptyList(),
)

/**
 * One suggestion: a JMAP `EmailAddress` with the ranking signals hung off it.
 *
 * The `{name, email}` half is the shape `Email/set` already takes, so a suggestion goes straight
 * into a draft without a translation step. The rest is what a client needs to *explain* the order
 * rather than merely trust it — and [isCorrespondent], which is not a sort key at all: it says the
 * user has written to this address rather than only heard from it, which is the difference between
 * a person and a mailing list.
 */
@Serializable
data class ContactSuggestion(
    val name: String? = null,
    val email: String = "",
    /** How often this address has appeared in a header, in either direction. The first sort key. */
    val frequency: Int = 0,
    /** UTC, `2026-08-06T09:12:00Z`. The tie-break. Null on a contact never dated. */
    val lastSeenAt: String? = null,
    val isCorrespondent: Boolean = false,
) {
    /** The address as the composer wants it. */
    val address: EmailAddress
        get() = EmailAddress(name = name?.takeIf { it.isNotBlank() }, email = email)
}
