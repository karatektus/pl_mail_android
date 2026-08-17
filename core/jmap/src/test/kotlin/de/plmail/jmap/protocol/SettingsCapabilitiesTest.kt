package de.plmail.jmap.protocol

import de.plmail.jmap.Fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The three capabilities that arrive with discovery and need no method call.
 *
 * Read out of a session captured from the live stack after the 2026-08-06 batch landed. The sync
 * capability is the one that replaced a request: the accounts screen used to ask every account for
 * its oldest message, and everything it wanted is in this object.
 *
 * The fixture predates the removal of the server's sync cap, so it still carries a `syncLimit` —
 * which makes it exactly the right fixture to keep. Decoding it proves an older self-hosted server
 * is read without complaint rather than crashing a client that no longer knows the field.
 */
class SettingsCapabilitiesTest {

    // --- account health ------------------------------------------------------

    /**
     * A revoked grant, published where a client can reach it.
     *
     * The account otherwise stays in the session and every method keeps answering, so the app shows
     * an inbox that has gone quiet with nothing to explain it.
     */
    @Test
    fun `an account needing attention reports its kind and severity`() {
        val sync =
            syncWindow(
                """{"backfillTarget":0,"backfillPending":false,"needsAttention":true,""" +
                    """"attentionKind":"account_reconnect","attentionSeverity":"critical"}"""
            )

        assertTrue(sync.needsAttention)
        assertEquals("account_reconnect", sync.attentionKind)
        assertEquals("critical", sync.attentionSeverity)
    }

    /**
     * **A JSON null is not the string "null".**
     *
     * `JsonNull` is itself a `JsonPrimitive` in kotlinx.serialization, and its `content` is the
     * four characters `null` — so the obvious cast-and-read leaves a healthy account carrying an
     * attention kind of `"null"`, which every `when` downstream reads as a kind it does not
     * recognise. The server sends explicit nulls here for every working account, which is to say
     * almost all of them.
     */
    @Test
    fun `an explicit null attention kind reads as absent rather than as the word null`() {
        val sync =
            syncWindow("""{"needsAttention":false,"attentionKind":null,"attentionSeverity":null}""")

        assertFalse(sync.needsAttention)
        assertNull(sync.attentionKind)
        assertNull(sync.attentionSeverity)
    }

    /**
     * An older plMail publishes none of these. Silence is health: warning about every account of
     * every server that predates the feature would be worse than saying nothing.
     */
    @Test
    fun `a server that says nothing about health is taken to be healthy`() {
        val sync = syncWindow("""{"backfillTarget":0,"backfillPending":false}""")

        assertFalse(sync.needsAttention)
        assertNull(sync.attentionKind)
    }

    private fun syncWindow(json: String): SyncWindow =
        SyncWindow.from(Json.parseToJsonElement(json).jsonObject)

    private val session = Fixture.decode<Session>("session-settings.json")

    @Test
    fun `backfill progress is per account, and an older server's extra fields are ignored`() {
        val window = assertNotNull(session.syncWindow(AccountId("1")))

        // Null is "no backfill has completed"; 0 would be "one completed and
        // reached the whole mailbox". Opposite facts, which collapse into each
        // other the moment this is decoded as an Int with a default.
        assertNull(window.backfillTarget)
        assertTrue(window.backfillPending, "there is mail still coming")

        // Two accounts behind one credential, each with its own window: read
        // once at session level, both mailboxes would get the first one's
        // answer.
        assertNotNull(session.syncWindow(AccountId("2")))

        // Absence is the signal, as with calendars. "This server does not say"
        // and "this server keeps everything" are different sentences.
        val stripped =
            session.copy(
                accounts =
                    session.accounts.mapValues { (_, account) ->
                        account.copy(
                            accountCapabilities = account.accountCapabilities - Capability.SYNC
                        )
                    }
            )

        assertNull(stripped.syncWindow(AccountId("1")))
    }

    @Test
    fun `the appearance capability paints the first frame and names the seventh theme`() {
        val appearance = assertNotNull(session.appearance)

        // Captured with a non-default appearance on purpose: a hint that
        // happened to equal the defaults would pass whether it was read or not.
        assertEquals("nord", appearance.hint.theme)
        assertEquals("boxed", appearance.hint.layout)
        assertEquals("comfortable", appearance.hint.density)

        // Seven, and the app has six. `paper` is the extra one — this is how a
        // client discovers a value it has to answer for without being told.
        assertEquals(
            listOf("system", "light", "paper", "dark", "nord", "dusk", "solar"),
            appearance.themes,
        )

        val range = assertNotNull(appearance.range("paneAlpha"))

        assertEquals(1f, range.clamp(1.4f))
        assertEquals(0.15f, range.clamp(0f))

        // The capture predates v0.0.35, so the two new vocabularies are empty
        // here — which is the state a self-hosted server one release behind is
        // in, and the reason a picker must be drawn from these rather than from
        // a list compiled into the app. The patch is validated whole, so one
        // choice this server would refuse takes down every property sent beside
        // it.
        assertEquals(emptyList(), appearance.unreadEmphases)
        assertEquals(emptyList(), appearance.fontFamilies)
        assertNull(appearance.range("fontScale"))
    }

    @Test
    fun `a v0_0_36 server publishes two more vocabularies and two more ranges`() {
        // Constructed to `SessionBuilder::appearanceCapabilities` rather than
        // captured, for the reason the fixtures README gives: the capture would
        // have to come off a server this repo cannot upgrade, and a hand-written
        // file sitting among the real ones would look like evidence. Laid over
        // the captured session so the accessor under test is the real one.
        val upgraded =
            session.copy(
                capabilities =
                    session.capabilities +
                        (Capability.APPEARANCE to Fixture.json.decodeFromString(APPEARANCE_V36))
            )

        val appearance = assertNotNull(upgraded.appearance)

        assertEquals(listOf("subtle", "standard", "strong"), appearance.unreadEmphases)
        assertEquals(listOf("system", "grotesk", "serif", "monospace"), appearance.fontFamilies)

        // Read by name, which is why these two arrived needing no new field.
        // `previewLines` publishes whole numbers and clamps like any other knob.
        val scale = assertNotNull(appearance.range("fontScale"))

        assertEquals(1.25f, scale.clamp(2f))
        assertEquals(0.875f, scale.clamp(0.5f))

        val lines = assertNotNull(appearance.range("previewLines"))

        assertEquals(2f, lines.clamp(3f))
        assertEquals(0f, lines.clamp(-1f))
    }

    @Test
    fun `contacts advertise their limits, and a bare instance advertises nothing`() {
        val contacts = assertNotNull(session.contacts)

        assertEquals(8, contacts.defaultSuggestions)
        assertEquals(50, contacts.maxSuggestions)

        // Named for contacts rather than reused from mail. The two agree on this
        // server, which is exactly what would let a wrong assumption go
        // unnoticed until an instance disagreed.
        assertEquals(AccountId("1"), session.primaryContactsAccount)

        // A supported instance, not a broken one: the app keeps its local
        // appearance and falls back to the cached-mail suggester.
        val bare =
            session.copy(
                capabilities = session.capabilities - Capability.APPEARANCE - Capability.CONTACTS
            )

        assertNull(bare.appearance)
        assertNull(bare.contacts)
    }

    private companion object {
        /** The appearance capability as v0.0.36 publishes it. Constructed, see the test above. */
        const val APPEARANCE_V36 =
            """
            {
              "appearance": {"theme":"nord","layout":"boxed","accent":"#2563eb","density":"cosy"},
              "themes": ["system","light","paper","dark","nord","dusk","solar"],
              "layouts": ["flat","boxed"],
              "densities": ["comfortable","cosy","compact"],
              "unreadEmphases": ["subtle","standard","strong"],
              "fontFamilies": ["system","grotesk","serif","monospace"],
              "ranges": {
                "paneAlpha": {"min":0.15,"max":1},
                "previewLines": {"min":0,"max":2},
                "fontScale": {"min":0.875,"max":1.25}
              }
            }
            """
    }
}
