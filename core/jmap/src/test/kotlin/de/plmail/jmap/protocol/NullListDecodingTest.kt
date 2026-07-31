package de.plmail.jmap.protocol

import de.plmail.jmap.methods.EmailGet
import de.plmail.jmap.methods.EmailGetResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server writes an absent list as an explicit `null`.
 *
 * Not an omitted key, and not `[]`. A `Email/get` for a message with no Cc comes back with `"cc":
 * null`, and the same is true of `bcc`, `replyTo`, `messageId` and `references` — so this is the
 * ordinary case rather than an unusual one.
 *
 * It matters because a Kotlin default only applies to a key that is *missing*. Without
 * `coerceInputValues` the response fails to decode with "Expected JsonArray, but had JsonNull at
 * path: $.cc", which names a field the caller never asked about and appears only once the reader
 * starts fetching the properties a list row does not need. The payload below is the shape the
 * running server actually returned.
 */
class NullListDecodingTest {

    private val withNullLists =
        """
        {
          "accountId": "1",
          "state": "0",
          "list": [
            {
              "id": "1",
              "threadId": "1",
              "subject": "Seeded",
              "to": [{"name": "E2E Mailbox", "email": "mailbox@e2e.test"}],
              "cc": null,
              "bcc": null,
              "replyTo": null,
              "messageId": null,
              "references": null,
              "inReplyTo": null,
              "textBody": [{"partId": "text", "type": "text/plain"}],
              "bodyValues": {"text": {"value": "Seeded body."}}
            }
          ],
          "notFound": []
        }
        """

    @Test
    fun `explicit nulls decode as empty rather than failing`() {
        val result =
            MethodResults.JMAP_JSON.decodeFromString(EmailGetResult.serializer(), withNullLists)

        val email = result.list.single()

        assertEquals(emptyList(), email.cc)
        assertEquals(emptyList(), email.bcc)
        assertEquals(emptyList(), email.replyTo)
        assertEquals(null, email.messageId)
        assertEquals(null, email.references)
    }

    @Test
    fun `the fields that are present still decode`() {
        val email =
            MethodResults.JMAP_JSON.decodeFromString(EmailGetResult.serializer(), withNullLists)
                .list
                .single()

        assertEquals("mailbox@e2e.test", email.to.single().email)
        assertEquals("Seeded body.", email.textContent)
    }

    /**
     * The reader's property set is what exposed this.
     *
     * A list row asks for `to` and never for `cc`, so the app ran for two milestones before the
     * first message body request hit it.
     */
    @Test
    fun `the reader asks for the properties that come back null`() {
        assertTrue(EmailGet.READER_PROPERTIES.containsAll(listOf("cc", "bcc", "replyTo")))
        assertTrue(EmailGet.LIST_ROW_PROPERTIES.none { it in setOf("cc", "bcc", "replyTo") })
    }
}
