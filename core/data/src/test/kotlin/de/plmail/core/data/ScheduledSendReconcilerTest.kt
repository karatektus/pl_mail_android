package de.plmail.core.data

import de.plmail.jmap.methods.SubmissionRecord
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The schedule agreeing with the server, and refusing to agree with a server that cannot say.
 *
 * Two questions, and they pull in opposite directions, which is why they are pinned together.
 *
 * The first: **the server is now the record.** `EmailSubmission/get` reports a held submission as
 * `pending` with its real release time and a declined one as `canceled`, so a schedule made on a
 * laptop has to appear here and a cancel made there has to take the row away. Every one of those is
 * a case where the *right* answer is to overwrite what this device believed.
 *
 * The second: **an older plMail is silent about exactly the submissions it is holding.** There, a
 * `notFound` for a live hold is the ordinary answer, and a client that read absence as "cancelled"
 * would delete the only copy of a release time in existence — after which the mail leaves at a
 * moment nothing on the device can name and nothing can call it back. This is the failure worth
 * being paranoid about: the first question's mistakes are a stale row, the second's lose a
 * message's schedule.
 *
 * So the rule pinned throughout is that a record is dropped on the server's **word** — `canceled`,
 * `final`, or a release time far enough past that nothing will ever confirm it — and never on its
 * silence.
 *
 * Every wire shape below was probed against the live 8002 stack on 2026-08-06; see
 * [ScheduledSendReconciler] for what each probe returned. The one state that could not be observed
 * there is `final`, because that stack runs `in-memory://` with no consumer and no submission on it
 * ever completes.
 */
class ScheduledSendReconcilerTest {

    private val now = Instant.parse("2026-08-06T12:00:00Z").toEpochMilli()

    private fun at(minutes: Long): Long = now + minutes * 60_000

    private fun wire(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun local(
        emailId: String,
        sendAt: Long,
        subject: String = "Sent from here",
        accountKey: String = testAccountKey,
    ) =
        ScheduledSend(
            accountKey = accountKey,
            emailId = emailId,
            identityId = "1",
            subject = subject,
            sendAt = sendAt,
        )

    private fun pending(id: String, sendAt: Long) =
        SubmissionRecord(
            id = id,
            identityId = "1",
            emailId = id,
            sendAt = wire(sendAt),
            undoStatus = SubmissionRecord.PENDING,
        )

    // --------------------------------------------------------- the pure rules

    @Test
    fun `a schedule made on another device appears, with the server's release time`() {
        // The whole point of the change. This device has never heard of 900; the
        // server says it is held until half past. Nothing local can supply the
        // subject, so it is asked for separately.
        val outcome =
            reconcile(
                accountKey = testAccountKey,
                known = emptyList(),
                found = listOf(pending("900", at(30))),
                notFound = emptySet(),
                now = now,
                graceMs = SendQueue.SETTLE_GRACE_MS,
            )

        val discovered = outcome.keep.single()

        assertEquals("900", discovered.emailId)
        assertEquals(at(30), discovered.sendAt)
        assertEquals(testAccountKey, discovered.accountKey)
        assertEquals(listOf("900"), outcome.needSubject)
        assertEquals(SubmissionVisibility.REPORTS_HELD, outcome.visibility)
    }

    @Test
    fun `a cancel made on another device takes the row away`() {
        // `canceled` rather than `notFound`, which is the change: the server
        // keeps the submission and re-labels it, so this is a positive statement
        // rather than an absence to be interpreted.
        val outcome =
            reconcile(
                accountKey = testAccountKey,
                known = listOf(local("900", at(30))),
                found = listOf(pending("900", at(30)).copy(undoStatus = SubmissionRecord.CANCELED)),
                notFound = emptySet(),
                now = now,
                graceMs = SendQueue.SETTLE_GRACE_MS,
            )

        assertTrue(outcome.keep.isEmpty())
        assertEquals(SubmissionVisibility.REPORTS_HELD, outcome.visibility)
    }

    @Test
    fun `a send that has gone stops being a schedule`() {
        val outcome =
            reconcile(
                accountKey = testAccountKey,
                known = listOf(local("900", at(-5))),
                found = listOf(pending("900", at(-5)).copy(undoStatus = SubmissionRecord.FINAL)),
                notFound = emptySet(),
                now = now,
                graceMs = SendQueue.SETTLE_GRACE_MS,
            )

        assertTrue(outcome.keep.isEmpty())
    }

    @Test
    fun `the server's sendAt wins over the local copy`() {
        // A schedule this device made and the server rounded, or one another
        // device moved. There is one release time and it is the server's; the
        // row has to draw that or it is telling the user the wrong minute.
        val outcome =
            reconcile(
                accountKey = testAccountKey,
                known = listOf(local("900", at(30))),
                found = listOf(pending("900", at(45))),
                notFound = emptySet(),
                now = now,
                graceMs = SendQueue.SETTLE_GRACE_MS,
            )

        val kept = outcome.keep.single()

        assertEquals(at(45), kept.sendAt)
        // The subject the user typed survives. Re-reading it from the server
        // would replace it with whatever the draft stored, which for a message
        // with no subject is an empty string.
        assertEquals("Sent from here", kept.subject)
        assertTrue(outcome.needSubject.isEmpty())
    }

    // ------------------------------------------------ the fallback, and why it

    @Test
    fun `a held submission the server will not admit to is kept, and detected`() {
        // The feature detection, and the case the whole conservative rule exists
        // for. This device submitted 900 and the release time is half an hour
        // away, so a server that reports held submissions would be reporting
        // this one. It answered `notFound`, so it does not report them -- it is
        // an older plMail, its silence means nothing, and the local record is
        // the only copy of the release time anywhere.
        val outcome =
            reconcile(
                accountKey = testAccountKey,
                known = listOf(local("900", at(30))),
                found = emptyList(),
                notFound = setOf("900"),
                now = now,
                graceMs = SendQueue.SETTLE_GRACE_MS,
            )

        assertEquals(listOf("900"), outcome.keep.map { it.emailId })
        assertEquals(at(30), outcome.keep.single().sendAt)
        assertEquals(SubmissionVisibility.HIDES_HELD, outcome.visibility)
    }

    @Test
    fun `a completed send alone does not prove the server reports holds`() {
        // `final` was the *old* server's only answer as well, so reading it as
        // evidence of the new behaviour would be exactly the mistake that lets an
        // old server's silence retire a live schedule on the next pass.
        val outcome =
            reconcile(
                accountKey = testAccountKey,
                known = listOf(local("900", at(-5))),
                found = listOf(pending("900", at(-5)).copy(undoStatus = SubmissionRecord.FINAL)),
                notFound = emptySet(),
                now = now,
                graceMs = SendQueue.SETTLE_GRACE_MS,
            )

        assertEquals(SubmissionVisibility.UNKNOWN, outcome.visibility)
    }

    @Test
    fun `an account with nothing scheduled teaches nothing`() {
        val outcome =
            reconcile(
                accountKey = testAccountKey,
                known = emptyList(),
                found = emptyList(),
                notFound = emptySet(),
                now = now,
                graceMs = SendQueue.SETTLE_GRACE_MS,
            )

        assertEquals(SubmissionVisibility.UNKNOWN, outcome.visibility)
        assertTrue(outcome.keep.isEmpty())
    }

    @Test
    fun `one reported hold settles the question even beside an unreported one`() {
        // A mixed answer is not a contradiction. An account can hold one
        // submission this server reports and carry a stale local record for a
        // message somebody destroyed, and proof beats absence of proof --
        // otherwise a single unexplained row would put a modern server into the
        // fallback forever.
        val outcome =
            reconcile(
                accountKey = testAccountKey,
                known = listOf(local("900", at(30)), local("901", at(40))),
                found = listOf(pending("900", at(30))),
                notFound = setOf("901"),
                now = now,
                graceMs = SendQueue.SETTLE_GRACE_MS,
            )

        assertEquals(SubmissionVisibility.REPORTS_HELD, outcome.visibility)
        // Kept regardless of the verdict: the rule is about the server's word,
        // and it has said nothing about 901.
        assertEquals(listOf("900", "901"), outcome.keep.map { it.emailId })
    }

    // -------------------------------------------------- past the release time

    @Test
    fun `an unconfirmed record past its time survives the grace and no longer`() {
        val inside =
            reconcile(
                accountKey = testAccountKey,
                known = listOf(local("900", now - SendQueue.SETTLE_GRACE_MS + 1_000)),
                found = emptyList(),
                notFound = setOf("900"),
                now = now,
                graceMs = SendQueue.SETTLE_GRACE_MS,
            )

        assertEquals(1, inside.keep.size)
        // Silence past the release time is ambiguous on *both* generations of
        // server, so it is not evidence of either.
        assertEquals(SubmissionVisibility.UNKNOWN, inside.visibility)

        val outside =
            reconcile(
                accountKey = testAccountKey,
                known = listOf(local("900", now - SendQueue.SETTLE_GRACE_MS - 1_000)),
                found = emptyList(),
                notFound = setOf("900"),
                now = now,
                graceMs = SendQueue.SETTLE_GRACE_MS,
            )

        assertTrue(outside.keep.isEmpty())
    }

    @Test
    fun `a still-pending submission whose worker is late is believed for the grace`() {
        // The regression the new API makes possible. A queue running a minute
        // behind answers `pending` with a release time in the past; dropping the
        // row there would make it vanish while the mail is still sitting in the
        // queue.
        val outcome =
            reconcile(
                accountKey = testAccountKey,
                known = listOf(local("900", at(-1))),
                found = listOf(pending("900", at(-1))),
                notFound = emptySet(),
                now = now,
                graceMs = SendQueue.SETTLE_GRACE_MS,
            )

        assertEquals(listOf("900"), outcome.keep.map { it.emailId })
        assertEquals(SubmissionVisibility.REPORTS_HELD, outcome.visibility)
    }

    // ------------------------------------------------------ the whole machine

    @Test
    fun `a reconcile writes one account's schedule without touching another's`() = runTest {
        // `replaceAccount` rather than a `forget` per drop: two accounts behind
        // one credential are reconciled in turn, and a pass that rewrote the
        // whole list would erase the first account's schedule while reporting
        // the second's.
        val scheduled = scheduledSends()

        scheduled.record(local("900", at(30)))
        scheduled.record(local("500", at(30), accountKey = "other"))

        val directory =
            FakeDirectory(
                accounts = listOf(testAccountKey),
                records = mapOf("900" to pending("900", at(45))),
            )

        ScheduledSendReconciler(scheduled, directory).reconcile(testAccountKey, now)

        val all = scheduled.all.first()

        assertEquals(at(45), all.single { it.emailId == "900" }.sendAt)
        assertEquals(at(30), all.single { it.emailId == "500" }.sendAt)
    }

    @Test
    fun `a discovered schedule is given the subject the server has for it`() = runTest {
        val scheduled = scheduledSends()
        val directory =
            FakeDirectory(
                accounts = listOf(testAccountKey),
                changes = listOf(SubmissionDelta(newState = "s9", changed = listOf("900"))),
                records = mapOf("900" to pending("900", at(30))),
                subjects = mapOf("900" to "Written on the laptop"),
            )

        ScheduledSendReconciler(scheduled, directory).reconcile(testAccountKey, now)

        val discovered = scheduled.all.first().single()

        assertEquals("Written on the laptop", discovered.subject)
        assertEquals(testAccountKey, discovered.accountKey)
        // Cancellable from here, which is the product claim: the id is what the
        // cancel names, and it was never device-bound.
        assertEquals("900", discovered.emailId)
    }

    @Test
    fun `a subject the server has nothing to say about is left blank rather than invented`() =
        runTest {
            // The bar draws "(no subject)" for a blank one, which is the same
            // thing the composer shows for a draft with none. Anything else here
            // would be the client making up a name for somebody's message.
            val scheduled = scheduledSends()
            val directory =
                FakeDirectory(
                    accounts = listOf(testAccountKey),
                    changes = listOf(SubmissionDelta(newState = "s9", changed = listOf("900"))),
                    records = mapOf("900" to pending("900", at(30))),
                )

            ScheduledSendReconciler(scheduled, directory).reconcile(testAccountKey, now)

            assertEquals("", scheduled.all.first().single().subject)
        }

    @Test
    fun `the change cursor advances and the next run starts from it`() = runTest {
        val scheduled = scheduledSends()
        val directory =
            FakeDirectory(
                accounts = listOf(testAccountKey),
                changes = listOf(SubmissionDelta(newState = "s9", changed = listOf("900"))),
                records = mapOf("900" to pending("900", at(30))),
            )
        val reconciler = ScheduledSendReconciler(scheduled, directory)

        reconciler.reconcile(testAccountKey, now)

        // From the beginning on a device that has never looked, because there is
        // no other way to hear about a submission it did not make.
        assertEquals(listOf("0"), directory.askedSince)
        assertEquals("s9", scheduled.cursors()[testAccountKey])

        directory.changes = listOf(SubmissionDelta(newState = "s9"))
        reconciler.reconcile(testAccountKey, now)

        assertEquals(listOf("0", "s9"), directory.askedSince)
    }

    @Test
    fun `a server that answers with the state it was handed does not spin the walk`() = runTest {
        // Belt and braces around a `while`: the loop's exit is `hasMore`, and a
        // server that reports no movement *and* leaves `hasMore` set would
        // otherwise cost MAX_PAGES round trips per reconcile forever.
        val scheduled = scheduledSends()
        val directory =
            FakeDirectory(
                accounts = listOf(testAccountKey),
                changes = listOf(SubmissionDelta(newState = "0", hasMore = true)),
            )

        ScheduledSendReconciler(scheduled, directory).reconcile(testAccountKey, now)

        assertEquals(1, directory.askedSince.size)
    }

    @Test
    fun `a paged change log is walked to the end`() = runTest {
        val scheduled = scheduledSends()
        val directory =
            FakeDirectory(
                accounts = listOf(testAccountKey),
                changes =
                    listOf(
                        SubmissionDelta("s1", listOf("801"), hasMore = true),
                        SubmissionDelta("s2", listOf("900"), hasMore = false),
                    ),
                records = mapOf("900" to pending("900", at(30))),
            )

        ScheduledSendReconciler(scheduled, directory).reconcile(testAccountKey, now)

        assertEquals(listOf("0", "s1"), directory.askedSince)
        // Both ids were asked about; only the one the server still holds stayed.
        assertTrue(directory.asked.containsAll(listOf("801", "900")))
        assertEquals(listOf("900"), scheduled.all.first().map { it.emailId })
    }

    @Test
    fun `one account's unreachable server does not stop the next`() = runTest {
        val scheduled = scheduledSends()
        val directory =
            FakeDirectory(
                accounts = listOf("broken", testAccountKey),
                changes = listOf(SubmissionDelta(newState = "s9", changed = listOf("900"))),
                records = mapOf("900" to pending("900", at(30))),
                failFor = "broken",
            )

        ScheduledSendReconciler(scheduled, directory).reconcileAll(now)

        assertEquals(listOf("900"), scheduled.all.first().map { it.emailId })
    }

    /**
     * The submission methods, as a variable.
     *
     * [asked] and [askedSince] are half the point: what this class *sends* is where the paging and
     * the chunking live, and neither is visible from the records it ends up with.
     */
    private class FakeDirectory(
        val accounts: List<String>,
        changes: List<SubmissionDelta> = emptyList(),
        val records: Map<String, SubmissionRecord> = emptyMap(),
        val subjects: Map<String, String> = emptyMap(),
        val failFor: String? = null,
    ) : SubmissionDirectory {
        val asked = mutableListOf<String>()
        val askedSince = mutableListOf<String>()
        private var page = 0

        /** Re-scripting the change log starts it again, so a test can run two reconciles. */
        var changes: List<SubmissionDelta> = changes
            set(value) {
                field = value
                page = 0
            }

        override suspend fun accountKeys(): List<String> = accounts

        override suspend fun submissions(
            accountKey: String,
            ids: List<String>,
        ): SubmissionSnapshot {
            if (accountKey == failFor) error("unreachable")

            asked += ids

            return SubmissionSnapshot(
                found = ids.mapNotNull { records[it] },
                notFound = ids.filterNot { records.containsKey(it) },
            )
        }

        override suspend fun submissionChanges(
            accountKey: String,
            sinceState: String,
        ): SubmissionDelta {
            if (accountKey == failFor) error("unreachable")

            askedSince += sinceState

            return changes.getOrNull(page++) ?: SubmissionDelta(newState = sinceState)
        }

        override suspend fun subjects(
            accountKey: String,
            emailIds: List<String>,
        ): Map<String, String> = subjects.filterKeys { it in emailIds }
    }
}
