package de.plmail.jmap.methods

import de.plmail.jmap.Fixture
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.MethodHandle
import de.plmail.jmap.protocol.MethodResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reading a submission back, now that there is something there to read.
 *
 * **A server behaviour changed under this client and the fixtures are the proof.** plMail used to
 * reconstruct a submission from the Message and skip any with no `sentAt`, so a held submission
 * answered `notFound` — the same answer as a draft nobody had ever submitted — and always reported
 * `undoStatus: "final"` for anything that did resolve. The release time therefore existed in the
 * create response and nowhere else, and a schedule could not be shared between two devices.
 *
 * All five fixtures here were captured verbatim from `:8002` on 2026-08-06, in one sequence: submit
 * with `HOLDUNTIL` eight hours out, get, read the change log, cancel, get again, read the change
 * log again. What they pin is the shape the reconciler's feature detection rests on — and in
 * particular that `notFound` now means one thing only.
 *
 * **What is not here, and cannot be:** the `final` arm. That stack runs
 * `MESSENGER_TRANSPORT_DSN=in-memory://` with no consumer, so no submission on it ever completes
 * and no fixture of a sent one can be captured. It is handled from the documented contract and
 * asserted against a hand-built record rather than a captured one, which is said out loud here
 * because a fixture directory that quietly mixed the two would be worth less than one that did not
 * claim to have it.
 */
class EmailSubmissionReadTest {

    private fun results(fixture: String) =
        MethodResults.decode(Fixture.read(fixture).encodeToByteArray(), status = 200)

    private fun getResult(fixture: String) =
        results(fixture).result(MethodHandle(EmailSubmissionGet(AccountId("1"), emptyList()), "c0"))

    @Test
    fun `a held submission is pending, with the release time the create response promised`() {
        val created =
            results("submission-set-held.json")
                .result(MethodHandle(EmailSubmissionSet(AccountId("1")), "c0"))
                .submission

        val record = getResult("submission-get-pending.json").list.single()

        assertTrue(record.isPending)
        assertFalse(record.isFinal)
        // The same instant, to the second, as the create response reported. That
        // agreement is what lets a schedule made on one device be believed on
        // another -- and it used to be untestable, because there was nothing to
        // compare the create response against.
        assertEquals(created?.sendAt, record.sendAt)
        assertEquals("2026-08-07T00:56:24Z", record.sendAt)
    }

    @Test
    fun `the submission id is the Email id, and both are published`() {
        // plMail sends each draft at most once, so the mapping is one-to-one --
        // but it is read back rather than assumed, because a cancel names the
        // id and getting it from the wrong field would call off nothing.
        val record = getResult("submission-get-pending.json").list.single()

        assertEquals("38744", record.id)
        assertEquals("38744", record.emailId)
        assertEquals("1", record.identityId)
    }

    @Test
    fun `notFound now means never submitted, and nothing else`() {
        // The whole of the feature detection rests on this. Both ids went into
        // one get: one is held, one is a draft that was never submitted, and the
        // server puts them on opposite sides. Under the old behaviour they would
        // both have been in `notFound`, which is why a client could not tell a
        // live hold from a message that was never sent.
        val result = getResult("submission-get-pending.json")

        assertEquals(listOf("38744"), result.list.map { it.id })
        assertEquals(listOf("38745"), result.notFound)
    }

    @Test
    fun `a cancelled submission keeps the time it would have left at`() {
        // `canceled` rather than gone. The record survives, which is what lets a
        // cancel made on a laptop take the row off a phone -- absence would have
        // been indistinguishable from the old server holding it.
        val record = getResult("submission-get-canceled.json").list.single()

        assertTrue(record.isCanceled)
        assertFalse(record.isPending)
        assertEquals("2026-08-07T00:56:24Z", record.sendAt)
    }

    @Test
    fun `a cancel is accepted while the mail is held`() {
        val result =
            results("submission-get-canceled.json")
                .result(MethodHandle(EmailSubmissionGet(AccountId("1"), emptyList()), "c0"))

        // And the state moved, so `/changes` has something to report.
        assertEquals("29577", result.state)
    }

    @Test
    fun `the change log reports the submit as created and the cancel as updated`() {
        val submitted =
            results("submission-changes.json")
                .result(MethodHandle(EmailSubmissionChanges(AccountId("1"), "0"), "c0"))

        assertEquals(listOf("38744"), submitted.created)
        assertTrue(submitted.updated.isEmpty())
        assertFalse(submitted.hasMoreChanges)

        val cancelled =
            results("submission-changes-cancel.json")
                .result(MethodHandle(EmailSubmissionChanges(AccountId("1"), "0"), "c0"))

        assertEquals(listOf("38744"), cancelled.updated)
        assertTrue(cancelled.created.isEmpty())

        // Both are "ask `/get` about this one", which is why the reconciler
        // treats them alike: what the id is doing is the record's answer, not
        // the list's.
        assertEquals(listOf("38744"), cancelled.changed)
    }

    @Test
    fun `an absent undoStatus reads as final, never as pending`() {
        // A server that predates the three-state answer. There, the only
        // submission that resolved at all was a completed one -- so reading an
        // absent field as "pending" would invent a hold that does not exist and
        // put a Cancel button over a message that has gone.
        val record = Fixture.json.decodeFromString<SubmissionRecord>("""{"id":"1","emailId":"1"}""")

        assertTrue(record.isFinal)
        assertFalse(record.isPending)
    }

    @Test
    fun `a sent submission is final, from the contract rather than from a fixture`() {
        // See the class note: `:8002` cannot complete a send, so there is no
        // capture of this arm. Hand-built and labelled as such.
        val record =
            Fixture.json.decodeFromString<SubmissionRecord>(
                """{"id":"1","emailId":"1","sendAt":"2026-08-06T10:00:00Z","undoStatus":"final"}"""
            )

        assertTrue(record.isFinal)
        assertFalse(record.isCanceled)
    }

    @Test
    fun `a get names its ids explicitly, because the server refuses to enumerate`() {
        // `ids: null` and an absent `ids` key are both `requestTooLarge` on
        // plMail -- probed, not read -- so there is no listing call and
        // `EmailSubmission/changes` is the only route to an id nobody
        // remembered. A client that sent null would get an error it could not
        // act on.
        val arguments = EmailSubmissionGet(AccountId("1"), listOf("38744", "38745")).arguments()

        assertEquals(
            listOf("38744", "38745"),
            arguments["ids"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `changes are asked for a page at a time, from the state the client holds`() {
        val arguments = EmailSubmissionChanges(AccountId("1"), "29572").arguments()

        assertEquals("29572", arguments["sinceState"]!!.jsonPrimitive.content)
        assertEquals(
            EmailSubmissionChanges.MAX_CHANGES,
            arguments["maxChanges"]!!.jsonPrimitive.content.toInt(),
        )
    }
}
