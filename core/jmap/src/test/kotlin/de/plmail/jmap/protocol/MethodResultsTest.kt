package de.plmail.jmap.protocol

import de.plmail.jmap.Fixture
import de.plmail.jmap.methods.EmailGet
import de.plmail.jmap.methods.EmailQuery
import de.plmail.jmap.methods.ThreadGet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Decoding real responses.
 *
 * Every fixture here came off a live server. Several of these assertions record behaviour that
 * contradicts the obvious assumption, which is exactly why they are pinned rather than trusted.
 */
class MethodResultsTest {

    private val account = AccountId("1")

    private fun results(fixture: String) =
        MethodResults.decode(Fixture.read(fixture).encodeToByteArray(), status = 200)

    // The fixtures were captured with the server's own call ids, so a handle
    // has to be built against those rather than the ones RequestBuilder would
    // have generated.
    private fun <R> handle(method: JmapMethod<R>, callId: String) = MethodHandle(method, callId)

    @Test
    fun `decodes a query result`() {
        val query = handle(EmailQuery(account), "q0")
        val result = results("email-query.json").result(query)

        assertEquals(listOf("5", "1", "2", "3", "4"), result.ids.map { it.value })
        assertEquals(5, result.total)
        assertEquals(20, result.limit, "the requested limit is echoed, not the server's cap")
    }

    @Test
    fun `query reports that changes cannot be calculated`() {
        val query = handle(EmailQuery(account), "q0")

        // Email/queryChanges is not implemented, so refreshing a list means
        // re-running the query. A client that trusted a true here would wait
        // forever for deltas that never come.
        assertFalse(results("email-query.json").result(query).canCalculateChanges)
    }

    @Test
    fun `Email-get returns messages in repository order, not the order requested`() {
        val results = results("email-query-get-backref.json")
        val query = handle(EmailQuery(account), "q0")
        val get = handle(EmailGet(account), "g0")

        val requested = results.result(query).ids
        val returned = results.result(get).list.map { it.id }

        assertEquals(listOf("5", "1", "2", "3", "4"), requested.map { it.value })
        assertEquals(
            listOf("1", "2", "3", "4", "5"),
            returned.map { it.value },
            "if this ever matches the request order, the server changed — do not relax ordered()",
        )
    }

    @Test
    fun `ordered puts the messages back into the order asked for`() {
        val results = results("email-query-get-backref.json")
        val requested = results.result(handle(EmailQuery(account), "q0")).ids

        val ordered = results.result(handle(EmailGet(account), "g0")).ordered(requested)

        assertEquals(requested, ordered.map { it.id })
        assertEquals("5", ordered.first().id.value, "the newest message must render first")
    }

    @Test
    fun `Thread-get reorders too, and not into id order`() {
        // The client documentation warns about Email/get only, which makes
        // this the easier of the two to be caught by.
        val threads = results("thread-get.json").result(handle(ThreadGet(account), "t0"))

        assertEquals(listOf("4", "3", "2", "1", "5"), threads.list.map { it.id.value })
    }

    @Test
    fun `an integer accountId is rejected rather than coerced`() {
        val query = handle(EmailQuery(account), "q0")
        val results = results("error-account-id-integer.json")

        val failure = results.failure(query)

        assertNotNull(failure)
        assertEquals("invalidArguments", failure.type)
        // No description field — the server does not say which argument.
        assertEquals(null, failure.description)
        assertFailsWith<JmapError.MethodFailed> { results.result(query) }
    }

    @Test
    fun `an unknown keyword raises unsupportedFilter rather than being ignored`() {
        // Silently dropping the condition would return too much mail with no
        // way for the client to notice.
        val failure =
            results("error-unsupported-filter.json").failure(handle(EmailQuery(account), "q0"))

        assertEquals("unsupportedFilter", failure?.type)
    }

    @Test
    fun `anchor paging fails indistinguishably from a bad filter`() {
        val failure =
            results("error-unsupported-anchor.json").failure(handle(EmailQuery(account), "q0"))

        // Same type, no description: a query builder cannot tell from the
        // error which of the two mistakes it made.
        assertEquals("unsupportedFilter", failure?.type)
        assertEquals(null, failure?.description)
    }

    @Test
    fun `a 401 is an authentication failure, not a generic bad status`() {
        val error =
            assertFailsWith<JmapError.NotAuthenticated> {
                MethodResults.decode(
                    Fixture.read("error-401.json").encodeToByteArray(),
                    status = 401,
                )
            }

        assertEquals("Invalid or revoked app password.", error.detail)
    }

    @Test
    fun `a non-request body is a request-level rejection with its own status`() {
        // Note the level difference: this is HTTP 400 with no methodResponses
        // at all, where a bad argument is HTTP 200 with an error entry inside.
        val error =
            assertFailsWith<JmapError.RequestRejected> {
                MethodResults.decode(
                    Fixture.read("error-not-request.json").encodeToByteArray(),
                    status = 400,
                )
            }

        assertEquals("urn:ietf:params:jmap:error:notRequest", error.type)
        assertEquals(400, error.status)
    }

    @Test
    fun `a batch can fail partly and still be readable`() {
        // The shape that matters for a unified inbox: one account failing must
        // never blank the list, so failure() has to be askable per call
        // without throwing.
        val results = results("error-account-id-integer.json")
        val failed = handle(EmailQuery(account), "q0")
        val absent = handle(EmailQuery(account), "nosuchcall")

        assertNotNull(results.failure(failed))
        assertTrue(results.failure(absent) == null)
    }
}
