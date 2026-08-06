package de.plmail.jmap.protocol

import de.plmail.jmap.Fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three capabilities that arrive with discovery and need no method call.
 *
 * Read out of a session captured from the live stack after the 2026-08-06 batch landed. The sync
 * window is the one that replaced a request: the accounts screen used to ask every account for its
 * oldest message, and everything it wanted is in this object.
 */
class SettingsCapabilitiesTest {

    private val session = Fixture.decode<Session>("session-settings.json")

    @Test
    fun `the sync window is per account, and zero means uncapped`() {
        val window = assertNotNull(session.syncWindow(AccountId("1")))

        // The single most dangerous misreading. `syncLimit: 0` is "no cap", and
        // a client treating it as a count would tell somebody their server keeps
        // no mail at all. It is also what a Microsoft account always reports,
        // because Graph cannot honour the cap whatever is stored.
        assertTrue(window.isUncapped)

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
}
