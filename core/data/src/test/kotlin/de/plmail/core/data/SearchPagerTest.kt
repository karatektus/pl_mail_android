package de.plmail.core.data

import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.mail.EmailFilter
import de.plmail.jmap.methods.SearchSnippet
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Search paging: query, get and snippets in one request.
 *
 * The snippets are the reason this is not [AccountPager] with a flag. They have to describe the
 * page that came back, which means travelling with it — and the filter has to be the one the query
 * ran with, or the highlight belongs to a search nobody performed.
 */
class SearchPagerTest {

    private val account = AccountId("13")
    private val filter = EmailFilter.Text("star")

    private val session =
        """
        {
          "capabilities": {"urn:ietf:params:jmap:core": {}},
          "accounts": {"13": {"name": "someone@example.com"}},
          "username": "someone@example.com",
          "apiUrl": "https://nas.local/jmap/api",
          "downloadUrl": "https://nas.local/jmap/download",
          "uploadUrl": "https://nas.local/jmap/upload"
        }
        """

    @Test
    fun `one request carries the query, the get and the snippets`() = runTest {
        val transport = transport()

        pager(transport).page(null, emptySet(), 10)

        val api = transport.requests.filter { it.url.endsWith("/jmap/api") }
        assertEquals(1, api.size, "three round trips per page would be unusable on a home uplink")

        val calls = methodNames(api.single().body!!.decodeToString())
        assertEquals(listOf("Email/query", "Email/get", "SearchSnippet/get"), calls)
    }

    /**
     * The snippets must describe *this* page.
     *
     * Asked with literal ids from a previous response they could be answered after new mail landed,
     * and the reader would see a highlight sitting on a row that does not contain it.
     */
    @Test
    fun `snippets are back-referenced against the same query`() = runTest {
        val transport = transport()

        pager(transport).page(null, emptySet(), 10)

        val body = transport.requests.last().body!!.decodeToString()
        val snippetCall =
            Json.parseToJsonElement(body)
                .jsonObject["methodCalls"]!!
                .jsonArray
                .map { it.jsonArray }
                .single { it[0].jsonPrimitive.content == "SearchSnippet/get" }

        val arguments = snippetCall[1].jsonObject

        assertEquals(
            "Email/query",
            arguments["#emailIds"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )

        // The same filter the query ran with, resent per RFC 8621.
        assertEquals("star", arguments["filter"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the page is reported with its snippets, keyed by email id`() = runTest {
        var received: Map<String, SearchSnippet> = emptyMap()

        pager(transport()) { _, snippets -> received = snippets }.page(null, emptySet(), 10)

        assertEquals("<mark>Star</mark> Me", received.getValue("6").subject)
        assertTrue(
            received.getValue("7").subject == null,
            "no hit in that field is a null, not a miss",
        )
    }

    @Test
    fun `rows follow the query's order, not the order Email-get replied in`() = runTest {
        val page = pager(transport()).page(null, emptySet(), 10)

        assertEquals(listOf("6", "7"), page.rows.map { it.id })
    }

    // -- helpers -----------------------------------------------------------

    private fun methodNames(body: String): List<String> =
        Json.parseToJsonElement(body).jsonObject["methodCalls"]!!.jsonArray.map {
            it.jsonArray[0].jsonPrimitive.content
        }

    private fun pager(
        transport: RecordingTransport,
        onPage: suspend (List<de.plmail.jmap.mail.Email>, Map<String, SearchSnippet>) -> Unit =
            { _, _ ->
            },
    ) =
        SearchPager(
            accountKey = "https://nas.local/13",
            accountId = account,
            client =
                JmapClient(
                    discoveryUrl = "https://nas.local/.well-known/jmap",
                    credential = Credential.AppPassword("plmail_" + "a".repeat(64)),
                    transport = transport,
                ),
            filter = filter,
            onPage = onPage,
        )

    /** The query answers `[6, 7]`; the get replies in repository order, as the real server does. */
    private fun transport(): RecordingTransport = RecordingTransport { request ->
        val body =
            if (request.url.endsWith("/.well-known/jmap")) {
                session
            } else {
                """
                {
                  "sessionState": "s",
                  "methodResponses": [
                    ["Email/query", {"accountId":"13","queryState":"q","ids":["6","7"]}, "c0"],
                    ["Email/get", {"accountId":"13","state":"e","list":[
                      {"id":"7","threadId":"t7","receivedAt":"2026-07-01T10:00:01Z","preview":"p7"},
                      {"id":"6","threadId":"t6","receivedAt":"2026-07-01T10:00:02Z","preview":"p6"}
                    ]}, "c1"],
                    ["SearchSnippet/get", {"accountId":"13","list":[
                      {"emailId":"6","subject":"<mark>Star</mark> Me","preview":null},
                      {"emailId":"7","subject":null,"preview":null}
                    ],"notFound":[]}, "c2"]
                  ]
                }
                """
            }

        HttpResponse(200, mapOf("Content-Type" to "application/json"), body.encodeToByteArray())
    }
}
