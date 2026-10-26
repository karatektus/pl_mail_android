package de.plmail.core.data

import de.plmail.core.database.IdentityEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.EmailAddress
import de.plmail.jmap.mail.EmailBodyPart
import de.plmail.jmap.mail.EmailBodyValue
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
 * A reply built from what reading the mail already downloaded.
 *
 * The bug this pins is a wait: the composer fetched the message being answered from the server
 * every time, between the tap on Reply and the composer appearing.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason FeedProjectionTest gives.
@Config(sdk = [36])
class CachedOriginalTest {

    private lateinit var database: PlMailDatabase

    @Before
    fun open() {
        database = inMemoryDatabase()
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun `a message that was read answers from the cache, Reply-To and sender included`() = runTest {
        seedIdentity("i2", "alias@example.org")
        store(read(to = "alias@example.org"))

        val original = assertNotNull(database.cachedOriginal(testAccountKey, "m1"))

        assertEquals(listOf("list@example.org"), original.replyTo.map { it.email })
        assertEquals(listOf("cc@example.org"), original.cc.map { it.email })
        assertEquals(listOf("<m1@example.org>"), original.messageId)
        assertEquals(listOf("<m0@example.org>"), original.references)
        assertEquals("<p>Hello</p>", original.htmlContent)
        assertEquals("i2", original.replyFrom?.id?.value)
    }

    @Test
    fun `a message only listed, never read, goes to the server`() = runTest {
        seedIdentity("i2", "alias@example.org")
        store(read(to = "alias@example.org").copy(htmlBody = emptyList(), bodyValues = emptyMap()))

        assertNull(database.cachedOriginal(testAccountKey, "m1"))
    }

    /** A catch-all address: only the server's `replyFrom` can name it. */
    @Test
    fun `a message to none of the account's addresses goes to the server`() = runTest {
        seedIdentity("i2", "alias@example.org")
        store(read(to = "anything@example.org"))

        assertNull(database.cachedOriginal(testAccountKey, "m1"))
    }

    /** What a list page writes over the message row once it has been read. */
    @Test
    fun `the headers survive the message row being listed again`() = runTest {
        seedIdentity("i2", "alias@example.org")
        store(read(to = "alias@example.org"))
        store(
            read(to = "alias@example.org")
                .copy(
                    replyTo = emptyList(),
                    cc = emptyList(),
                    htmlBody = emptyList(),
                    bodyValues = emptyMap(),
                )
        )

        val original = assertNotNull(database.cachedOriginal(testAccountKey, "m1"))

        assertEquals(listOf("list@example.org"), original.replyTo.map { it.email })
    }

    private suspend fun store(email: Email) =
        MailRepository(database).storeEmails(testAccountKey, listOf(email), fetchedAt = 1)

    private fun read(to: String) =
        Email(
            id = EmailId("m1"),
            threadId = ThreadId("t1"),
            subject = "Hi",
            from = listOf(EmailAddress("Sven", "sven@example.org")),
            to = listOf(EmailAddress(null, to)),
            cc = listOf(EmailAddress(null, "cc@example.org")),
            replyTo = listOf(EmailAddress(null, "list@example.org")),
            receivedAt = "2026-10-25T15:17:00Z",
            messageId = listOf("<m1@example.org>"),
            references = listOf("<m0@example.org>"),
            htmlBody = listOf(EmailBodyPart(partId = "1", type = "text/html")),
            bodyValues = mapOf("1" to EmailBodyValue("<p>Hello</p>")),
        )

    private suspend fun seedIdentity(id: String, email: String) {
        database
            .identities()
            .upsert(
                listOf(
                    IdentityEntity(
                        uid = StoreKey.objectKey(testAccountKey, id),
                        accountKey = testAccountKey,
                        identityId = id,
                        email = email,
                    )
                )
            )
    }
}
