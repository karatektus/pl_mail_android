package de.plmail.jmap.methods

import de.plmail.jmap.mail.Keyword
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.IdentityId
import de.plmail.jmap.protocol.MailboxId
import de.plmail.jmap.protocol.ThreadId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the client *sends*.
 *
 * These are the mistakes the server cannot report: a patch that replaces instead of merging, a
 * "remove" expressed as `false` instead of `null`, an archive that adds a label rather than
 * removing one. All of them produce a successful response and the wrong mailbox.
 */
class WriteMethodsTest {

    private val account = AccountId("1")
    private val inbox = MailboxId("1")
    private val archiveLabel = MailboxId("9")
    private val json = Json { prettyPrint = false }

    private fun argumentsOf(method: de.plmail.jmap.protocol.JmapMethod<*>): JsonObject =
        method.arguments()

    @Test
    fun `removing a mailbox is a null, not a false`() {
        // A `false` sets the key to false rather than deleting it. The server
        // accepts both and only one of them archives anything.
        val patch = EmailPatch.build { removeMailbox(inbox) }

        assertEquals(JsonNull, patch.toJson()["mailboxIds/1"])
    }

    @Test
    fun `archiving removes Inbox and adds nothing`() {
        // "Archived" in this product means *carries no Inbox label*. Adding an
        // Archive label instead leaves the message in the inbox as well.
        val patch = EmailPatch.build { archive(inbox) }.toJson()

        assertEquals(setOf("mailboxIds/1"), patch.keys)
        assertEquals(JsonNull, patch["mailboxIds/1"])
        assertFalse(patch.keys.any { it.contains(archiveLabel.value) })
    }

    @Test
    fun `patches address individual keys rather than replacing the map`() {
        // Sending a whole new mailboxIds map means a client working from stale
        // state silently drops a label another client added a second ago.
        val patch = EmailPatch.build {
            addMailbox(archiveLabel)
            seen(true)
        }

        val fields = patch.toJson()

        assertEquals(setOf("mailboxIds/9", "keywords/\$seen"), fields.keys)
        assertTrue(fields.none { it.key == "mailboxIds" || it.key == "keywords" })
    }

    @Test
    fun `marking unread deletes the keyword rather than setting it false`() {
        val patch = EmailPatch.build { seen(false) }

        assertEquals(JsonNull, patch.toJson()["keywords/\$seen"])
    }

    @Test
    fun `only the four supported keywords are expressible`() {
        // There is no way to construct an invented keyword through this API,
        // which is the point — the server rejects unknown ones and they would
        // not round-trip anyway.
        assertEquals(
            listOf("\$seen", "\$flagged", "\$draft", "\$answered"),
            Keyword.entries.map { it.wire },
        )
    }

    @Test
    fun `destroy is sent as a plain id list`() {
        // And it means "move to Trash". There is no hard delete anywhere.
        val arguments = argumentsOf(EmailSet(account, destroy = listOf(EmailId("7"))))

        assertContains(
            json.encodeToString(JsonObject.serializer(), arguments),
            "\"destroy\":[\"7\"]",
        )
    }

    @Test
    fun `an empty set call omits create, update and destroy entirely`() {
        // Sending empty objects would be accepted but is noise on a link that
        // may be someone's ADSL uplink.
        val arguments = argumentsOf(EmailSet(account))

        assertEquals(setOf("accountId"), arguments.keys)
    }

    @Test
    fun `ifInState is sent when given, for conflict detection`() {
        val arguments =
            argumentsOf(
                EmailSet(
                    account,
                    destroy = listOf(EmailId("7")),
                    ifInState = de.plmail.jmap.protocol.StateToken("42"),
                )
            )

        assertEquals("42", arguments["ifInState"]?.jsonPrimitive?.content)
    }

    @Test
    fun `snooze sets a thread-level property, not a message flag`() {
        val arguments =
            argumentsOf(ThreadSet.snooze(account, ThreadId("3"), "2026-08-01T07:00:00Z"))

        val patch = arguments["update"]?.jsonObject?.get("3")?.jsonObject

        assertEquals("2026-08-01T07:00:00Z", patch?.get("snoozedUntil")?.jsonPrimitive?.content)
    }

    @Test
    fun `unsnooze nulls the property`() {
        val arguments = argumentsOf(ThreadSet.unsnooze(account, ThreadId("3")))

        assertEquals(
            JsonNull,
            arguments["update"]?.jsonObject?.get("3")?.jsonObject?.get("snoozedUntil"),
        )
    }

    @Test
    fun `sending references the submission by creation id with a hash`() {
        val arguments =
            argumentsOf(
                EmailSubmissionSet.send(
                    accountId = account,
                    emailId = EmailId("5"),
                    identityId = IdentityId("1"),
                    drafts = MailboxId("3"),
                    sent = MailboxId("2"),
                )
            )

        val onSuccess = arguments["onSuccessUpdateEmail"]?.jsonObject

        // '#s1' names the submission created in this same call. Without the
        // hash it would name an existing object that does not exist.
        assertEquals(setOf("#s1"), onSuccess?.keys)
        assertEquals(JsonNull, onSuccess?.get("#s1")?.jsonObject?.get("mailboxIds/3"))
    }

    @Test
    fun `a draft carries the draft and seen keywords`() {
        // A draft the user just typed is not unread mail, and every client
        // that forgets this shows a permanent unread badge on Drafts.
        val draft = DraftEmail(mailboxIds = listOf(MailboxId("3")), subject = "Hello")

        val keywords = draft.toJson()["keywords"]?.jsonObject

        assertEquals(setOf("\$draft", "\$seen"), keywords?.keys)
    }

    @Test
    fun `a reply carries inReplyTo and references so it continues the conversation`() {
        val draft =
            DraftEmail(
                mailboxIds = listOf(MailboxId("3")),
                inReplyTo = listOf("abc@example.com"),
                references = listOf("root@example.com", "abc@example.com"),
            )

        val fields = draft.toJson()

        assertTrue(fields.containsKey("inReplyTo"))
        assertTrue(fields.containsKey("references"))
    }

    @Test
    fun `push subscription types exclude Identity`() {
        // Identity changes only when the user edits their own addresses, which
        // they did in this app — waking the device for it is pure cost.
        assertFalse(NewPushSubscription.DEFAULT_TYPES.contains("Identity"))
        assertContains(NewPushSubscription.DEFAULT_TYPES, "Email")
    }

    @Test
    fun `push subscription sends keys the way RFC 8291 names them`() {
        val subscription =
            NewPushSubscription(
                deviceClientId = "android-1",
                url = "https://ntfy.example/abc",
                p256dh = "BPubKey",
                auth = "AuthSecret",
            )

        val keys = subscription.toJson()["keys"]?.jsonObject

        assertEquals("BPubKey", keys?.get("p256dh")?.jsonPrimitive?.content)
        assertEquals("AuthSecret", keys?.get("auth")?.jsonPrimitive?.content)
    }
}
