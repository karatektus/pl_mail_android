package de.plmail.jmap.methods

import de.plmail.jmap.mail.Keyword
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.BlobId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.IdentityId
import de.plmail.jmap.protocol.MailboxId
import de.plmail.jmap.protocol.Session
import de.plmail.jmap.protocol.ThreadId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
    fun `an edited body sends the part list and the values together`() {
        // bodyValues is keyed by partId, so values sent without the htmlBody
        // part that names them leave the server with a map it cannot look
        // anything up in. It answers `updated` and changes nothing.
        val patch = EmailPatch.build { html("<p>Rewritten</p>") }.toJson()

        assertTrue(patch.containsKey("htmlBody"))
        assertEquals(
            "html",
            patch["htmlBody"]!!.jsonArray[0].jsonObject["partId"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "<p>Rewritten</p>",
            patch["bodyValues"]!!
                .jsonObject["html"]
                ?.jsonObject
                ?.get("value")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `clearing a field sends null rather than an empty string`() {
        // The server treats an absent key as "leave it alone", so a subject the
        // user has just deleted needs the null to actually go away. An empty
        // string would be stored as an empty subject on some backends and
        // ignored on others.
        val patch = EmailPatch.build { text("subject", "   ") }.toJson()

        assertEquals(JsonNull, patch["subject"])
    }

    @Test
    fun `emptying a recipient list sends null, not an empty array`() {
        val patch = EmailPatch.build { addresses("cc", emptyList()) }.toJson()

        assertEquals(JsonNull, patch["cc"])
    }

    @Test
    fun `a submission omits the mailbox move unless both bindings are known`() {
        // A patch that removes Drafts without adding Sent leaves the message in
        // no mailbox, which the server refuses -- from inside
        // onSuccessUpdateEmail, after the send has already been queued. An
        // account that has never sent anything has no Sent binding, so this is
        // the ordinary case rather than an exotic one.
        val onlyDrafts =
            EmailSubmissionSet.send(account, EmailId("7"), IdentityId("1"), MailboxId("3"), null)

        assertFalse(argumentsOf(onlyDrafts).containsKey("onSuccessUpdateEmail"))

        val both =
            EmailSubmissionSet.send(
                account,
                EmailId("7"),
                IdentityId("1"),
                MailboxId("3"),
                MailboxId("5"),
            )

        val patch = argumentsOf(both)["onSuccessUpdateEmail"]!!.jsonObject["#s1"]!!.jsonObject

        assertEquals(JsonNull, patch["mailboxIds/3"])
        assertEquals(true, patch["mailboxIds/5"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `a draft created in the same request is submitted by its creation id`() {
        // "#c1" names an Email/set creation id. A result reference to
        // /created/c1/id resolves to a bare string instead, and Email/get then
        // rejects the argument with no description saying why.
        val submission = EmailSubmissionSet.sendNew(account, "c1", IdentityId("1"))
        val create = argumentsOf(submission)["create"]!!.jsonObject["s1"]!!.jsonObject

        assertEquals("#c1", create["emailId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the attachment set is sent whole, and an empty one is an empty array`() {
        // Whole-value: what is sent is what the draft ends up with. An empty
        // list has to be an empty *array* rather than a null or an absent key --
        // absent means "leave them alone", so removing the last attachment would
        // silently do nothing and the message would go out carrying it.
        val kept = DraftAttachment(blobId = BlobId("p-7308"), type = "text/plain", name = "a.txt")

        val patch = EmailPatch.build { attachments(listOf(kept)) }.toJson()

        assertEquals(
            "p-7308",
            patch["attachments"]!!.jsonArray[0].jsonObject["blobId"]?.jsonPrimitive?.content,
        )

        val cleared = EmailPatch.build { attachments(emptyList()) }.toJson()

        assertEquals(0, cleared["attachments"]!!.jsonArray.size)
    }

    @Test
    fun `a hold rides on the envelope parameters and nothing else`() {
        // Deliberately no `email` and no `rcptTo`. The server checks both
        // against the message it is about to send -- a different sender is
        // `forbiddenFrom`, a different recipient set `invalidRecipients` -- so
        // repeating what it already knows can only turn a working send into a
        // refused one.
        val submission =
            EmailSubmissionSet.send(
                accountId = account,
                emailId = EmailId("5"),
                identityId = IdentityId("1"),
                drafts = null,
                sent = null,
                hold = SendHold.Until("2026-08-07T06:00:00Z"),
            )

        val create = argumentsOf(submission)["create"]!!.jsonObject["s1"]!!.jsonObject
        val mailFrom = create["envelope"]!!.jsonObject["mailFrom"]!!.jsonObject

        assertEquals(setOf("parameters"), mailFrom.keys)
        assertEquals(
            "2026-08-07T06:00:00Z",
            mailFrom["parameters"]?.jsonObject?.get("HOLDUNTIL")?.jsonPrimitive?.content,
        )

        // HOLDFOR is text, as ESMTP parameters are.
        val relative = Submission(EmailId("5"), IdentityId("1"), SendHold.For(6)).toJson()

        assertEquals(
            "6",
            relative["envelope"]
                ?.jsonObject
                ?.get("mailFrom")
                ?.jsonObject
                ?.get("parameters")
                ?.jsonObject
                ?.get("HOLDFOR")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `a send with no hold carries no envelope at all`() {
        // An envelope the server has to validate for nothing, on the ordinary
        // path taken by every message anybody sends.
        val create =
            argumentsOf(
                    EmailSubmissionSet.send(account, EmailId("5"), IdentityId("1"), null, null)
                )["create"]!!
                .jsonObject["s1"]!!
                .jsonObject

        assertFalse(create.containsKey("envelope"))
    }

    @Test
    fun `cancelling is an update of undoStatus and only that`() {
        // The server accepts exactly one key in this patch and refuses the
        // whole update when a second one is present.
        val arguments = argumentsOf(EmailSubmissionSet.cancel(account, "42"))

        assertFalse(arguments.containsKey("create"))

        val patch = arguments["update"]!!.jsonObject["42"]!!.jsonObject

        assertEquals(setOf("undoStatus"), patch.keys)
        // The American spelling, which is RFC 8621's. "cancelled" is refused.
        assertEquals("canceled", patch["undoStatus"]?.jsonPrimitive?.content)
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

/**
 * Whether "send later" exists, read from the session exactly as the server writes it.
 *
 * One test rather than a suite, but the load-bearing one: `maxDelayedSend` lives in
 * **accountCapabilities**, not in the session-level capability object, and a client that looked in
 * the wrong place would find `{}` and conclude that no plMail anywhere can schedule a send.
 */
class SubmissionCapabilityTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** The 8002 stack's own answer, copied off the wire on 2026-08-06. */
    private val session =
        """
        {
          "accounts": {
            "1": {
              "name": "E2E Mailbox",
              "accountCapabilities": {
                "urn:ietf:params:jmap:submission": {
                  "maxDelayedSend": 2592000,
                  "submissionExtensions": {"FUTURERELEASE": ["HOLDFOR", "HOLDUNTIL"]}
                }
              }
            },
            "2": {"name": "Old server", "accountCapabilities": {}}
          },
          "apiUrl": "http://localhost:8002/jmap/api",
          "downloadUrl": "http://localhost:8002/jmap/download",
          "uploadUrl": "http://localhost:8002/jmap/upload"
        }
        """

    @Test
    fun `the ceiling is read per account, and absence means the feature is off`() {
        val decoded = json.decodeFromString(Session.serializer(), session)

        val first = decoded.submission(AccountId("1"))

        assertEquals(2_592_000, first.maxDelayedSend)
        assertTrue(first.supportsHoldFor)
        assertTrue(first.supportsHoldUntil)
        assertTrue(first.supportsScheduledSend)

        // An account that publishes nothing about delayed send is one that does
        // not do it. The spec's default is zero, and so is this.
        val second = decoded.submission(AccountId("2"))

        assertEquals(0, second.maxDelayedSend)
        assertFalse(second.supportsScheduledSend)
    }
}
