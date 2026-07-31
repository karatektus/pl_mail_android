package de.plmail.jmap.methods

import de.plmail.jmap.mail.EmailFilter
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.MethodResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `SearchSnippet/get`, against responses taken from the running server.
 *
 * The one worth keeping is the null case. The server answers a stopword search with a snippet whose
 * strings are both null rather than with an error or an omission, and a client that reads that as
 * "no result" hides a row the search legitimately returned.
 */
class SearchSnippetGetTest {

    private val account = AccountId("1")

    @Test
    fun `the filter is resent, because the spec has no stored query`() {
        val method =
            SearchSnippetGet(
                accountId = account,
                emailIds = listOf(EmailId("6")),
                filter = EmailFilter.Text("star"),
            )

        val arguments = method.arguments()

        assertEquals("star", arguments["filter"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ids can be back-referenced so the snippets describe the page that was returned`() {
        val query = EmailQuery(accountId = account, filter = EmailFilter.Text("star"))
        val handle = de.plmail.jmap.protocol.RequestBuilder().add(query)

        val arguments =
            SearchSnippetGet.byReference(
                    account,
                    handle.reference("/ids"),
                    EmailFilter.Text("star"),
                )
                .arguments()

        // The RFC 8620 spelling: the argument name gains a '#' and its value is
        // the reference object. Sent as a plain name, the server sees no ids at
        // all and answers with an empty list.
        val reference = arguments["#emailIds"]!!.jsonObject

        assertEquals("Email/query", reference["name"]!!.jsonPrimitive.content)
        assertEquals("/ids", reference["path"]!!.jsonPrimitive.content)
        assertNull(arguments["emailIds"], "the literal argument must not also be present")
    }

    /** Captured from the test stack: `text:Star` over the seeded mailbox. */
    @Test
    fun `a hit is HTML with mark around it`() {
        val result =
            decode(
                """
            {
              "accountId": "1",
              "list": [{"emailId": "6", "subject": "<mark>Star</mark> Me", "preview": null}],
              "notFound": []
            }
            """
            )

        val snippet = result.byEmail().getValue("6")

        assertEquals("<mark>Star</mark> Me", snippet.subject)
        assertNull(snippet.preview, "the body did not match, and saying so is the point")
        assertTrue(snippet.hasHighlight)
    }

    /**
     * A stopword search, which is the trap.
     *
     * `text:the` returns the row — the query matched — but `websearch_to_tsquery` compiles `the` to
     * an empty query, so nothing is highlighted. Both strings come back null. A row is still a
     * result; it simply has nothing extra to say.
     */
    @Test
    fun `a snippet with no highlight is a result, not a miss`() {
        val result =
            decode(
                """
            {
              "accountId": "1",
              "list": [{"emailId": "6", "subject": null, "preview": null}],
              "notFound": []
            }
            """
            )

        assertFalse(result.byEmail().getValue("6").hasHighlight)
        assertEquals(emptyList(), result.notFound, "null strings are not a not-found")
    }

    private fun decode(json: String): SearchSnippetGetResult =
        MethodResults.JMAP_JSON.decodeFromString(SearchSnippetGetResult.serializer(), json)
}
