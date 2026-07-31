package de.plmail.core.data

import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.EmailAddress
import de.plmail.jmap.mail.EmailBodyPart
import de.plmail.jmap.mail.EmailBodyValue
import de.plmail.jmap.mail.Keyword
import de.plmail.jmap.mail.MailThread
import de.plmail.jmap.mail.Mailbox
import de.plmail.jmap.protocol.BlobId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.LabelId
import de.plmail.jmap.protocol.MailboxId
import de.plmail.jmap.protocol.ThreadId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire objects into cache rows.
 *
 * The interesting cases are the ones where the obvious mapping is wrong: a date that will not parse
 * must sort *last* rather than become 0 and sort first, a thread's title comes from its oldest
 * message rather than the newest "Re:", and its participants read oldest first because ordering
 * them by recency made every answered conversation look like it came from the user.
 */
class MappersTest {

    private val accountKey = "https://nas.local/13"

    @Test
    fun `an email maps onto a row keyed by account and id`() {
        val entity = email(id = "5", subject = "Invoice").toEntity(accountKey)

        assertEquals("https://nas.local/13#5", entity.uid)
        assertEquals(accountKey, entity.accountKey)
        assertEquals("5", entity.emailId)
        assertEquals("Invoice", entity.subject)
    }

    @Test
    fun `dates become epoch millis`() {
        val entity = email(receivedAt = "2026-07-31T16:55:43Z").toEntity(accountKey)

        assertEquals(Instant.parse("2026-07-31T16:55:43Z").toEpochMilli(), entity.receivedAt)
    }

    /**
     * An offset-bearing date is a valid instant that `Instant.parse` refuses.
     *
     * Dropping it would put the message at the bottom of the list with no date, for a value that is
     * perfectly well formed.
     */
    @Test
    fun `a date carrying an offset rather than Z still parses`() {
        val withZ = email(receivedAt = "2026-07-31T16:55:43Z").toEntity(accountKey).receivedAt
        val withOffset =
            email(receivedAt = "2026-07-31T18:55:43+02:00").toEntity(accountKey).receivedAt

        assertEquals(withZ, withOffset, "the same instant written two ways")
    }

    /**
     * Null rather than zero, and this is the whole point of the nullable column.
     *
     * Substituting an epoch default would sort an undated message *first* in an ascending list, and
     * put a mystery message from 1970 at the top of someone's inbox.
     */
    @Test
    fun `an unparseable date is null rather than zero`() {
        assertNull(email(receivedAt = "not a date").toEntity(accountKey).receivedAt)
        assertNull(email(receivedAt = "").toEntity(accountKey).receivedAt)
        assertNull(email(receivedAt = null).toEntity(accountKey).receivedAt)
    }

    @Test
    fun `only true mailbox memberships are stored`() {
        // JMAP's map form: a key set to false is not a membership.
        val entity =
            email(mailboxIds = mapOf("1" to true, "2" to false, "3" to true)).toEntity(accountKey)

        assertEquals(setOf("1", "3"), entity.mailboxIds.split(",").toSet())
    }

    @Test
    fun `keywords become the four flags`() {
        val entity =
            email(keywords = mapOf(Keyword.SEEN.wire to true, Keyword.FLAGGED.wire to true))
                .toEntity(accountKey)

        assertTrue(entity.isSeen)
        assertTrue(entity.isFlagged)
        assertFalse(entity.isDraft)
        assertFalse(entity.isAnswered)
    }

    @Test
    fun `recipients round-trip through their stored form`() {
        val to = listOf(EmailAddress("Ada", "ada@example.com"), EmailAddress(null, "b@example.com"))
        val entity = email(to = to).toEntity(accountKey)

        assertEquals(to, entity.toJson.toEmailAddresses())
        assertNull(entity.ccJson, "an empty list is null rather than an empty JSON array")
        assertEquals(emptyList(), entity.ccJson.toEmailAddresses())
    }

    @Test
    fun `references are stored the way the header writes them`() {
        val entity = email(references = listOf("a@x", "b@x")).toEntity(accountKey)

        assertEquals("a@x b@x", entity.references)
        assertNull(email(references = emptyList()).toEntity(accountKey).references)
    }

    @Test
    fun `a body is null when nothing was fetched`() {
        assertNull(email().toBodyEntity(accountKey, fetchedAt = 1))

        val fetched =
            email(
                    htmlBody = listOf(EmailBodyPart(partId = "html")),
                    bodyValues = mapOf("html" to EmailBodyValue("<p>hello</p>")),
                )
                .toBodyEntity(accountKey, fetchedAt = 7)

        assertEquals("<p>hello</p>", fetched?.htmlBody)
        assertEquals(7, fetched?.fetchedAt)
    }

    @Test
    fun `an attachment without a blob is skipped rather than listed`() {
        // A row for it would be a listing entry that fails when tapped.
        val entity =
            email(
                attachments =
                    listOf(
                        EmailBodyPart(partId = "1", blobId = BlobId("b-1"), name = "invoice.pdf"),
                        EmailBodyPart(partId = "2", name = "broken.pdf"),
                    )
            )

        val attachments = entity.toAttachmentEntities(accountKey)

        assertEquals(1, attachments.size)
        assertEquals("b-1", attachments.single().blobId)
        assertEquals("invoice.pdf", attachments.single().name)
    }

    @Test
    fun `a mailbox sorts by its role rather than by the server's sortOrder`() {
        // The server reports 0 for Inbox and for custom labels alike.
        val inbox = mailbox(id = "1", role = "inbox", sortOrder = 0).toEntity(accountKey)
        val trash = mailbox(id = "2", role = "trash", sortOrder = 0).toEntity(accountKey)
        val custom = mailbox(id = "3", role = null, sortOrder = 0).toEntity(accountKey)

        assertTrue(inbox.sortOrder < trash.sortOrder)
        assertTrue(trash.sortOrder < custom.sortOrder, "custom labels sort after every system one")
    }

    @Test
    fun `a mailbox keeps the label id that collapses it across accounts`() {
        val entity = mailbox(id = "1", labelId = "label-9").toEntity(accountKey)

        assertEquals("label-9", entity.labelId)
        assertEquals("1", entity.mailboxId, "the binding id stays separate from the label id")
    }

    /**
     * The title comes from the oldest message.
     *
     * Taking the newest would rewrite a conversation's title to "Re: …" the moment anyone answered
     * it.
     */
    @Test
    fun `a thread takes its subject from the message that started it`() {
        val entity =
            thread("t1")
                .toEntity(
                    accountKey,
                    storedRows(
                        email(id = "1", subject = "Invoice", receivedAt = "2026-07-01T10:00:00Z"),
                        email(
                            id = "2",
                            subject = "Re: Invoice",
                            receivedAt = "2026-07-02T10:00:00Z",
                        ),
                    ),
                )

        assertEquals("Invoice", entity.subject)
    }

    /**
     * Participants oldest first.
     *
     * Ordering by recency made every conversation the user had answered appear to be from
     * themselves, because the last message in it was theirs.
     */
    @Test
    fun `thread participants read oldest first and are not repeated`() {
        val entity =
            thread("t1")
                .toEntity(
                    accountKey,
                    storedRows(
                        email(id = "1", from = "Ada", at = "2026-07-01T10:00:00Z"),
                        email(id = "2", from = "Me", at = "2026-07-02T10:00:00Z"),
                        email(id = "3", from = "Ada", at = "2026-07-03T10:00:00Z"),
                    ),
                )

        assertEquals("Ada, Me", entity.participantsSummary)
    }

    @Test
    fun `a thread is unread when any message in it is`() {
        val entity =
            thread("t1")
                .toEntity(
                    accountKey,
                    storedRows(
                        email(id = "1", keywords = mapOf(Keyword.SEEN.wire to true)),
                        email(id = "2"),
                    ),
                )

        assertTrue(entity.isUnread, "a new reply makes a read conversation bold again")
    }

    @Test
    fun `a thread sorts on its newest message`() {
        val entity =
            thread("t1")
                .toEntity(
                    accountKey,
                    storedRows(
                        email(id = "1", receivedAt = "2026-07-01T10:00:00Z"),
                        email(id = "2", receivedAt = "2026-07-03T10:00:00Z"),
                        email(id = "3", receivedAt = "2026-07-02T10:00:00Z"),
                    ),
                )

        assertEquals("2026-07-03T10:00:00Z".let { it.toEpochMillis() }, entity.latestReceivedAt)
    }

    @Test
    fun `the avatar is keyed on the address, not the display name`() {
        val entity =
            thread("t1")
                .toEntity(
                    accountKey,
                    storedRows(
                        email(
                            id = "1",
                            fromAddress = EmailAddress("Ada Lovelace", "Ada@Example.com"),
                        )
                    ),
                )

        // Lowercased, so the same person keeps one colour however their client
        // spells the address.
        assertEquals("ada@example.com", entity.participantsAddress)
    }

    @Test
    fun `a thread with no messages yet still produces a row`() {
        // Threads arrive over several pages; a row from what is known beats no row.
        val entity = thread("t1", emailIds = listOf("1", "2")).toEntity(accountKey, emptyList())

        assertEquals(0, entity.latestReceivedAt)
        assertEquals(2, entity.messageCount)
        assertFalse(entity.isUnread)
    }

    @Test
    fun `two accounts numbering from one do not collide`() {
        val first = email(id = "1").toEntity("https://nas.local/13")
        val second = email(id = "1").toEntity("https://nas.local/14")

        assertNotEquals(first.uid, second.uid)
    }

    /** Threads are summarised from stored rows, so the builders' output is mapped first. */
    private fun storedRows(vararg emails: Email) = emails.map { it.toEntity(accountKey) }

    // -- builders ----------------------------------------------------------

    private fun email(
        id: String = "1",
        subject: String? = null,
        receivedAt: String? = "2026-07-31T16:55:43Z",
        to: List<EmailAddress> = emptyList(),
        mailboxIds: Map<String, Boolean> = emptyMap(),
        keywords: Map<String, Boolean> = emptyMap(),
        references: List<String>? = null,
        attachments: List<EmailBodyPart> = emptyList(),
        htmlBody: List<EmailBodyPart> = emptyList(),
        bodyValues: Map<String, EmailBodyValue> = emptyMap(),
        from: String? = null,
        at: String? = null,
        fromAddress: EmailAddress? = null,
    ): Email =
        Email(
            id = EmailId(id),
            threadId = ThreadId("t1"),
            subject = subject,
            receivedAt = at ?: receivedAt,
            to = to,
            from =
                listOfNotNull(
                    fromAddress ?: from?.let { EmailAddress(it, "${it.lowercase()}@example.com") }
                ),
            mailboxIds = mailboxIds,
            keywords = keywords,
            references = references,
            attachments = attachments,
            htmlBody = htmlBody,
            bodyValues = bodyValues,
        )

    private fun mailbox(
        id: String,
        labelId: String? = null,
        role: String? = null,
        sortOrder: Int = 0,
    ): Mailbox =
        Mailbox(
            id = MailboxId(id),
            labelId = labelId?.let(::LabelId),
            name = "Label $id",
            role = role,
            sortOrder = sortOrder,
        )

    private fun thread(id: String, emailIds: List<String> = emptyList()): MailThread =
        MailThread(id = ThreadId(id), emailIds = emailIds.map(::EmailId))
}
