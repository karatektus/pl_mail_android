package de.plmail.jmap.mail

import de.plmail.jmap.methods.MailboxPatch
import de.plmail.jmap.methods.NewMailbox
import de.plmail.jmap.protocol.MailboxId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The two plMail extensions this client grew for the inbox tabs and for label colour.
 *
 * Both are values the wire layer deliberately does **not** interpret. `:core:jmap` is Android-free
 * and cannot turn `"blue"` into a colour, and the categories are a vocabulary the design system and
 * the data layer own — so what is under test here is that the string survives the round trip
 * unchanged, including one the server may invent after this build shipped.
 */
class CategoriesAndColourTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a mailbox carries its colour token as the server sent it`() {
        val mailbox =
            json.decodeFromString(
                Mailbox.serializer(),
                """{"id":"4","name":"Steuer","color":"amber"}""",
            )

        assertEquals("amber", mailbox.color)
    }

    /**
     * A token this build has never heard of has to reach the cache.
     *
     * The vocabulary is closed *today* and the server owns it, so a tenth colour added there must
     * not be erased on the way through the protocol layer — an app update would then start drawing
     * it with no resync. The design system is where an unknown token degrades to neutral.
     */
    @Test
    fun `an unknown colour is carried rather than dropped`() {
        val mailbox =
            json.decodeFromString(
                Mailbox.serializer(),
                """{"id":"4","name":"Steuer","color":"chartreuse"}""",
            )

        assertEquals("chartreuse", mailbox.color)
    }

    /** A server without the extension omits the key entirely, which is "no colour chosen". */
    @Test
    fun `a mailbox without a colour decodes rather than failing`() {
        assertNull(
            json.decodeFromString(Mailbox.serializer(), """{"id":"4","name":"Steuer"}""").color
        )
    }

    @Test
    fun `a thread carries the resolved category, and null when it has none`() {
        val classified =
            json.decodeFromString(
                MailThread.serializer(),
                """{"id":"9","emailIds":["1"],"category":"promotions"}""",
            )

        assertEquals("promotions", classified.category)

        // The state a plMail older than the extension reports for every
        // conversation, and the one the sidebar uses to decide the category
        // group is not worth drawing.
        assertNull(
            json
                .decodeFromString(MailThread.serializer(), """{"id":"9","emailIds":["1"]}""")
                .category
        )
    }

    @Test
    fun `an email carries its own raw category, which may disagree with its thread`() {
        val email =
            json.decodeFromString(
                Email.serializer(),
                """{"id":"5","threadId":"9","category":"primary"}""",
            )

        assertEquals("primary", email.category)
    }

    @Test
    fun `the category filter is sent as threadCategory`() {
        // The name matters: `category` is the per-message property, which the
        // server refuses as a condition precisely so a client cannot filter a
        // conversation into two tabs.
        val filter = EmailFilter.ThreadCategory("promotions").toJson()

        assertEquals(setOf("threadCategory"), filter.keys)
        assertEquals("promotions", filter["threadCategory"]?.jsonPrimitive?.content)
    }

    /**
     * A tab is "in this mailbox **and** in this category".
     *
     * The two conditions are orthogonal on the server and have to stay so here: a category filter
     * that implied the inbox would be a second definition of what the inbox is, living in a client.
     */
    @Test
    fun `the category composes with a mailbox under AND`() {
        val filter =
            EmailFilter.And(
                    listOf(
                        EmailFilter.InMailbox(MailboxId("1")),
                        EmailFilter.ThreadCategory("social"),
                    )
                )
                .toJson()

        assertEquals("AND", filter["operator"]?.jsonPrimitive?.content)
        assertTrue(
            json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), filter).let {
                it.contains("\"inMailbox\":\"1\"") && it.contains("\"threadCategory\":\"social\"")
            }
        )
    }

    /** An empty token would be refused by the server with no useful description. */
    @Test
    fun `an empty category is rejected before it is sent`() {
        assertFailsWith<IllegalArgumentException> { EmailFilter.ThreadCategory("") }
    }

    @Test
    fun `creating a label omits the colour when there is none`() {
        // An omitted key and a JSON null mean the same thing on a create, and
        // the omission cannot be mistaken for a client trying to clear
        // something it never set.
        assertTrue("color" !in NewMailbox(name = "Steuer").toJson().keys)
        assertEquals(
            "teal",
            NewMailbox(name = "Steuer", color = "teal").toJson()["color"]?.jsonPrimitive?.content,
        )
    }

    /**
     * On a patch the two are different, and this is the one that matters.
     *
     * An omitted key means "leave it alone", so without an explicit null a user could set a colour
     * from this client and have no way to take it off again.
     */
    @Test
    fun `clearing a colour sends a null rather than omitting the key`() {
        val patch = MailboxPatch.build { color(null) }.toJson()

        assertEquals(setOf("color"), patch.keys)
        assertEquals(JsonNull, patch["color"])
    }

    /**
     * Colour alone, with no name in the patch.
     *
     * The case a system label needs: `Mailbox/set` refuses a rename of Inbox with `forbidden` for
     * the whole patch, so sending an unchanged name beside the colour would take the colour down
     * with it.
     */
    @Test
    fun `a colour can be patched without a name`() {
        val patch = MailboxPatch.build { color("violet") }.toJson()

        assertEquals(setOf("color"), patch.keys)
    }
}
