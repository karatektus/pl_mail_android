package de.plmail.jmap.methods

import de.plmail.jmap.Fixture
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.MethodHandle
import de.plmail.jmap.protocol.MethodResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

/**
 * `Contact/autocomplete`, in both directions.
 *
 * The request half matters more than usual, because the two ways to get it wrong are both
 * *refusals*: a blank query and an unexpected argument are `invalidArguments`, so a client that
 * builds either one spends a round trip against a Raspberry Pi to be told no, once per keystroke.
 */
class ContactAutocompleteTest {

    private val account = AccountId("1")

    @Test
    fun `a blank query is refused before it costs a request`() {
        // The server answers `invalidArguments` — a `LIKE '%%'` would match
        // everybody, so "the field is empty" and "nobody matches" have to stay
        // different facts. Nothing should discover that over the network.
        assertNull(ContactAutocomplete.of(account, ""))
        assertNull(ContactAutocomplete.of(account, "   "))
    }

    @Test
    fun `sends the three accepted arguments, trimmed and capped`() {
        val arguments = ContactAutocomplete.of(account, "  anna ", limit = 500)!!.arguments()

        // `filter`, `sort` and `position` are the reasonable guess — every other
        // query-ish method here takes them — and the server refuses them by
        // name. The query is trimmed as the server trims it, so the echoed
        // value matches what was sent; the limit is capped at the advertised 50.
        assertEquals(setOf("accountId", "query", "limit"), arguments.keys)
        assertEquals("anna", arguments["query"]?.jsonPrimitive?.content)
        assertEquals("50", arguments["limit"]?.jsonPrimitive?.content)

        // Omitted rather than defaulted: 8 is the server's number to change,
        // and hardcoding it freezes this client against something it does not
        // own.
        assertTrue("limit" !in ContactAutocomplete.of(account, "anna")!!.arguments().keys)
    }

    @Test
    fun `decodes the echoed arguments and a suggestion`() {
        val handle = MethodHandle(ContactAutocomplete.of(account, "an")!!, "c1")
        val results =
            MethodResults.decode(
                Fixture.read("contact-autocomplete.json").encodeToByteArray(),
                status = 200,
            )

        val result = results.result(handle)

        // Both arguments are normalised on the way in and echoed on the way
        // out, which is the only way a client tells "you asked for 500 and got
        // 50" from "this address book holds fifty people".
        assertEquals("1", result.accountId)
        assertEquals("an", result.query)
        assertEquals(50, result.limit)

        // The list itself is hand-built, and deliberately: the 8002 stack
        // harvests contacts through a Messenger message and runs no consumer,
        // so its address book is empty and a non-empty `list` cannot be
        // captured. Field names are `ContactAutocompleteMethod::toSuggestion`
        // verbatim; the envelope around them is pinned by the real fixture
        // above.
        val suggestion =
            Fixture.json.decodeFromString<ContactSuggestion>(
                """
                {
                  "name": "Anna Meyer",
                  "email": "anna.meyer@example.test",
                  "frequency": 42,
                  "lastSeenAt": "2026-08-05T18:03:00Z",
                  "isCorrespondent": true
                }
                """
                    .trimIndent()
            )

        assertEquals(42, suggestion.frequency)
        assertEquals("2026-08-05T18:03:00Z", suggestion.lastSeenAt)
        assertTrue(suggestion.isCorrespondent)

        // The `{name, email}` half is an RFC 8621 EmailAddress, so it goes into
        // an `Email/set` create untranslated rather than through a rename.
        assertEquals("Anna Meyer", suggestion.address.name)
        assertEquals("anna.meyer@example.test", suggestion.address.email)
    }
}
