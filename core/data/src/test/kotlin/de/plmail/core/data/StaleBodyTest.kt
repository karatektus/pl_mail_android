package de.plmail.core.data

import de.plmail.core.database.EmailBodyEntity
import de.plmail.core.database.EmailEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.Keyword
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.ThreadId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cached bodies that stopped being true.
 *
 * The body table was written once and never invalidated, which is right for mail that has arrived
 * and wrong for the one row somebody is still writing. A reply's draft is autosaved to the server
 * the moment the composer opens, lands in the conversation it answers, and gets its body cached by
 * whatever downloads bodies next. Sending keeps the message id — the server only clears `$draft`
 * and moves it — so the sent mail inherited the unfinished one's cache entry and kept it.
 *
 * What the user saw was a message sent from the phone that read correctly in the collapsed row and
 * opened blank, because the autosave had beaten the first keystroke and "no body" had been recorded
 * as a permanent fact about a message that did not have one *yet*.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason the other Robolectric suites in this module give: a
// library module inherits compileSdk 37, which Robolectric has no image for.
@Config(sdk = [36])
class StaleBodyTest {

    private lateinit var database: PlMailDatabase

    @Before
    fun open() {
        database = inMemoryDatabase()
    }

    @After
    fun close() {
        database.close()
    }

    /** The bug, at the sync that reports the send. */
    @Test
    fun `a body cached while the message was a draft does not survive the send`() = runTest {
        database.seedAccount()
        database.seedDraft("m1")
        database.cacheBody("m1", text = "half a sentence")

        // A list page, which is what a delta sync carries: keywords and preview,
        // no body. The draft keyword is gone because the mail has been sent.
        MailRepository(database)
            .storeEmails(
                testAccountKey,
                listOf(wire("m1", preview = "the whole sentence")),
                fetchedAt = 1L,
            )

        assertNull(
            database.emails().body(uidOf("m1")),
            "the draft's body is not the sent message's, so it has to be fetched again",
        )
    }

    /**
     * The guard, at the moment the marker used to be written.
     *
     * An empty draft is the one message that acquires a body later, so recording it as permanently
     * bodiless is the claim that cannot be made here.
     */
    @Test
    fun `an empty draft is not recorded as having no body`() = runTest {
        database.seedAccount()
        database.seedDraft("m1")

        database.markFetchedBodylessMessages(
            testAccountKey,
            listOf(wire("m1", isDraft = true)),
            at = 1L,
        )

        assertNull(database.emails().body(uidOf("m1")), "a draft is never marked bodiless")
    }

    /** The same call still has to mark the mail the marker exists for. */
    @Test
    fun `a sent message with genuinely no body is still marked`() = runTest {
        database.seedAccount()
        database.seedDraft("m1", isDraft = false)

        database.markFetchedBodylessMessages(testAccountKey, listOf(wire("m1")), at = 1L)

        assertEquals("", database.emails().body(uidOf("m1"))?.textBody)
    }

    /**
     * The repair, for the installs already holding a poisoned marker.
     *
     * A preview is derived from the body, so "this message has no body" and "here is the start of
     * its text" cannot both be true. No migration: the next sync that touches the row clears it.
     */
    @Test
    fun `a bodiless marker is cleared when the server previews the message`() = runTest {
        database.seedAccount()
        database.seedDraft("m1", isDraft = false)
        database.cacheBody("m1", text = "")

        MailRepository(database)
            .storeEmails(
                testAccountKey,
                listOf(wire("m1", preview = "Im Zweifel gehts")),
                fetchedAt = 1L,
            )

        assertNull(database.emails().body(uidOf("m1")), "the marker contradicted the preview")
    }

    /** …and an ordinary received message keeps the body it already has. */
    @Test
    fun `a cached body for arrived mail is left alone`() = runTest {
        database.seedAccount()
        database.seedDraft("m1", isDraft = false)
        database.cacheBody("m1", text = "Seeded body.")

        MailRepository(database)
            .storeEmails(
                testAccountKey,
                listOf(wire("m1", preview = "Seeded body.")),
                fetchedAt = 1L,
            )

        assertEquals("Seeded body.", database.emails().body(uidOf("m1"))?.textBody)
    }

    // -- fixtures ------------------------------------------------------------

    private fun uidOf(emailId: String) = StoreKey.objectKey(testAccountKey, emailId)

    private suspend fun PlMailDatabase.seedDraft(emailId: String, isDraft: Boolean = true) {
        emails()
            .upsert(
                listOf(
                    EmailEntity(
                        uid = uidOf(emailId),
                        accountKey = testAccountKey,
                        emailId = emailId,
                        threadId = "t1",
                        receivedAt = 5_000,
                        isDraft = isDraft,
                    )
                )
            )
    }

    private suspend fun PlMailDatabase.cacheBody(emailId: String, text: String) {
        emails().upsertBody(EmailBodyEntity(uid = uidOf(emailId), textBody = text, fetchedAt = 1L))

        assertNotNull(emails().body(uidOf(emailId)), "the fixture has to actually cache something")
    }

    /** A list row as `Email/get` returns one: keywords and preview, no body. */
    private fun wire(emailId: String, preview: String = "", isDraft: Boolean = false): Email =
        Email(
            id = EmailId(emailId),
            threadId = ThreadId("t1"),
            receivedAt = "2026-09-22T10:35:00Z",
            preview = preview,
            keywords = if (isDraft) mapOf(Keyword.DRAFT.wire to true) else emptyMap(),
        )
}
