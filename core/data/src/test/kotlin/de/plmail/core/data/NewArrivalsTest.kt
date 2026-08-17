package de.plmail.core.data

import de.plmail.core.database.StoreKey
import de.plmail.core.datastore.NotificationPrefs
import de.plmail.jmap.mail.Email
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a sync is allowed to interrupt somebody for.
 *
 * Every filter here fails in the same direction — towards *more* notifications, at three in the
 * morning, for mail the user has already dealt with — and none of them throws when it is wrong.
 * That is the whole reason this is a pure function with a test rather than four lines inside the
 * sync loop.
 *
 * Since notifications became per-label there is a second direction to fail in, and it is worse:
 * silence. A default that resolved to nothing would look exactly like push being broken, so the
 * cases about an unclassified conversation and about a server with no classifier at all are load
 * bearing rather than thorough.
 */
class NewArrivalsTest {

    private val account = "https://nas.local/13"
    private val inbox = "1"
    private val work = "7"

    /** Inbox and one user label, as the mailbox table resolves them. */
    private val bindings = mapOf(inbox to "label-inbox", work to "label-work")

    private val untouched = NotificationPrefs()

    @Test
    fun `unread mail in the inbox is announced`() {
        val message = arrivals(email("5", subject = "Hello")).single()

        assertEquals("5", message.emailId)
        assertEquals("t5", message.threadId)
        assertEquals("Ada Lovelace", message.sender)
        assertEquals("Hello", message.subject)
        assertEquals("someone@example.com", message.accountName)
    }

    /**
     * "New" means new to this device, not new to the server.
     *
     * A cursor that is discarded and rebuilt re-fetches everything it already had, and a server
     * that reports a touched row as created does the same for one message. Both would announce old
     * mail, and the second is the reason this does not simply trust `Email/changes`'s `created`.
     */
    @Test
    fun `mail the cache already holds is not new, however the server describes it`() {
        val found =
            arrivals(email("5"), email("6"), known = setOf(StoreKey.objectKey(account, "5")))

        assertEquals(listOf("6"), found.map { it.emailId })
    }

    /**
     * The same message on a second sync, which is what every later `Email/changes` delivers.
     *
     * A label applied on the web re-reports a message this device already holds, and so does a read
     * receipt, a flag, and the server re-indexing. None of them is new mail. The cache is what
     * knows, and it knows because `storeEmails` ran between the two syncs.
     */
    @Test
    fun `a message already synced is not announced again when it changes`() {
        val first = arrivals(email("5"))

        assertEquals(listOf("5"), first.map { it.emailId })

        // Exactly what the second sync sees: the same id, now also in Work,
        // and the cache holding it because the first sync wrote it.
        val second =
            arrivals(
                email("5", boxes = listOf(inbox, work)),
                known = setOf(StoreKey.objectKey(account, "5")),
            )

        assertTrue(second.isEmpty())
    }

    /** Read elsewhere is dealt with. Announcing it announces the user's own past. */
    @Test
    fun `mail already read is not announced`() {
        assertTrue(arrivals(email("5", seen = true)).isEmpty())
    }

    /**
     * Sent mail, drafts, and anything a server-side rule has already filed.
     *
     * All three arrive through `Email/changes` exactly like inbox mail, and all three are changes
     * worth syncing that nobody wants a buzz for. The user's own sent message is the one that
     * really stings — and it is silent here without any rule naming Sent, because a message outside
     * the inbox carries no category scope and the label scope it does carry is one the settings
     * screen never offers.
     */
    @Test
    fun `mail outside the inbox is synced but not announced`() {
        assertTrue(arrivals(email("5", boxes = listOf("3"))).isEmpty())
    }

    /** A binding set to `false` is not membership; JMAP sends the key either way. */
    @Test
    fun `a mailbox binding turned off does not count as being in the inbox`() {
        val off = email("5").copy(mailboxIds = mapOf(inbox to false))

        assertTrue(arrivals(off).isEmpty())
    }

    /**
     * A message the server has not threaded is a conversation of one.
     *
     * The notification is keyed on the thread, so a null there would key every unthreaded message
     * to the same notification and each would replace the last.
     *
     * Set against a server with no classifier, because that is the only one on which an unthreaded
     * message is announced at all: with no conversation there is no category, and on a classifying
     * server that is the unclassified case below. The id fallback is what this is about either way.
     */
    @Test
    fun `an unthreaded message falls back to its own id`() {
        val found = arrivals(email("5").copy(threadId = null), serverClassifies = false)

        assertEquals("5", found.single().threadId)
    }

    // --- The default: Primary and nothing else -------------------------------

    /** The four other tabs are silent out of the box. That is the entire feature. */
    @Test
    fun `by default the non-primary categories do not interrupt`() {
        val quiet =
            listOf("social", "promotions", "updates", "forums").flatMap {
                arrivals(email("5"), categories = mapOf("t5" to it))
            }

        assertTrue(quiet.isEmpty())
    }

    @Test
    fun `by default a conversation the server calls primary interrupts`() {
        val found = arrivals(email("5"), categories = mapOf("t5" to "primary"))

        assertEquals(listOf("5"), found.map { it.emailId })
    }

    /**
     * **The case that must never go silent.**
     *
     * A plMail that predates the classifier, or one whose backfill has never run, reports null for
     * every conversation it has. Reading null as "not Primary" there would mean a phone that never
     * makes a sound for any message the user owns, and nothing on the screen would say why. On such
     * a server the inbox is one undifferentiated list, and Primary is the switch that describes it.
     */
    @Test
    fun `on a server with no classifier everything in the inbox counts as primary`() {
        val found = arrivals(email("5"), categories = emptyMap(), serverClassifies = false)

        assertEquals(listOf("5"), found.map { it.emailId })
    }

    /**
     * **The bug: notifications for every category rather than Primary alone.**
     *
     * The same null, on a server that *does* classify, means something entirely different — this
     * conversation has not been classified — and the server's own inbox query puts it in no tab.
     * Announcing it as Primary made every conversation the classifier had not reached ring the
     * phone, which is indistinguishable from Primary being the only switch that ever mattered.
     */
    @Test
    fun `an unclassified conversation is silent where the server does classify`() {
        assertTrue(arrivals(email("5"), categories = emptyMap()).isEmpty())
    }

    /**
     * A sixth category invented by a newer server still falls to Primary, and the asymmetry with
     * the case above is deliberate: the server said *something* and only this build is out of date.
     * A name this app has never heard of is far more likely to be mail worth hearing about than a
     * promotion, and reading it as Primary is what keeps a server upgrade from silencing a phone.
     */
    @Test
    fun `a category this build cannot name counts as primary`() {
        val found = arrivals(email("5"), categories = mapOf("t5" to "purchases"))

        assertEquals(listOf("5"), found.map { it.emailId })
    }

    // --- The inbox label is not a sixth switch over the five -------------------

    /**
     * **The other half of the bug.**
     *
     * The inbox is a mailbox like any other, so it resolves to a label key and used to contribute
     * one on top of the category scope. Scopes are matched with `any`, so switching Inbox on
     * announced every category regardless of the five switches above it — and switching Promotions
     * off could not take it back. Where the categories are drawn they are the inbox's controls.
     */
    @Test
    fun `the inbox label does not override the category switches`() {
        val prefs = NotificationPrefs(enabled = setOf("label:label-inbox"))

        val quiet = arrivals(email("5"), categories = mapOf("t5" to "promotions"), prefs = prefs)

        assertTrue(quiet.isEmpty())
    }

    /**
     * And on a server with no classifier it is the honest control, so it keeps working. There are
     * no category switches to contradict there — the settings screen draws none.
     */
    @Test
    fun `the inbox label still speaks where there are no categories`() {
        val prefs =
            NotificationPrefs(
                enabled = setOf("label:label-inbox"),
                disabled = setOf("category:primary"),
            )

        val found =
            arrivals(
                email("5"),
                categories = emptyMap(),
                prefs = prefs,
                serverClassifies = false,
            )

        assertEquals(listOf("5"), found.map { it.emailId })
    }

    // --- Per-label switches --------------------------------------------------

    /**
     * Mail a rule filed under Work without leaving it in the inbox: invisible until Work is
     * switched on, which is the whole reason per-label notifications are worth having.
     */
    @Test
    fun `a label switched on announces mail that never reached the inbox`() {
        val filed = email("5", boxes = listOf(work))

        assertTrue(arrivals(filed).isEmpty())

        val found = arrivals(filed, prefs = NotificationPrefs(enabled = setOf("label:label-work")))

        assertEquals(listOf("5"), found.map { it.emailId })
    }

    /**
     * Switching Primary off has to stick.
     *
     * The reason the store keeps an explicit "disabled" set: an empty "enabled" set would be
     * indistinguishable from a user who has never opened the screen, and the default would switch
     * Primary straight back on.
     */
    @Test
    fun `primary switched off is silent`() {
        val muted = NotificationPrefs(disabled = setOf("category:primary"))

        assertTrue(arrivals(email("5"), prefs = muted).isEmpty())
    }

    /**
     * A label switched on beats Primary switched off for mail carrying both, because the scopes are
     * a set matched with `any` — the user asked to hear about Work, and this is Work.
     */
    @Test
    fun `a switched-on label still speaks when primary is off`() {
        val prefs =
            NotificationPrefs(
                enabled = setOf("label:label-work"),
                disabled = setOf("category:primary"),
            )

        val found = arrivals(email("5", boxes = listOf(inbox, work)), prefs = prefs)

        assertEquals(listOf("5"), found.map { it.emailId })
    }

    /**
     * A label nobody has said anything about is off — the opposite of how an *account* defaults.
     *
     * A label created on the web and synced overnight must not start interrupting on its own.
     */
    @Test
    fun `a label the user has never seen is off`() {
        val found = arrivals(email("5", boxes = listOf(work)), prefs = untouched)

        assertTrue(found.isEmpty())
    }

    // --- Deduplication -------------------------------------------------------

    /**
     * **One message, one notification, however many switches it trips.**
     *
     * The mistake this guards is `flatMap` over the matching scopes, which reads perfectly well and
     * buzzes three times for one email.
     */
    @Test
    fun `a message under several switched-on scopes is announced exactly once`() {
        val prefs =
            NotificationPrefs(
                enabled = setOf("label:label-work", "label:label-inbox", "category:primary")
            )

        val found = arrivals(email("5", boxes = listOf(inbox, work)), prefs = prefs)

        assertEquals(1, found.size)
        assertEquals("5", found.single().emailId)
    }

    /** Belt and braces for a server that answers the same id twice in one `Email/get`. */
    @Test
    fun `a duplicated id in one response is announced once`() {
        val found = arrivals(email("5"), email("5"))

        assertEquals(1, found.size)
    }

    private fun arrivals(
        vararg emails: Email,
        known: Set<String> = emptySet(),
        prefs: NotificationPrefs = untouched,
        /**
         * Null classifies every conversation under test as Primary, which is what a working plMail
         * reports for ordinary mail and therefore the right background for cases about something
         * else. Pass a map — `emptyMap()` included — to say what the server actually answered.
         */
        categories: Map<String, String?>? = null,
        /**
         * Defaults to the *classifying* server, because that is what every plMail in service is and
         * what the interesting cases are about. The unclassified-server cases say so explicitly.
         */
        serverClassifies: Boolean = true,
    ): List<NewMessage> =
        newArrivals(
            emails = emails.toList(),
            accountKey = account,
            accountName = "someone@example.com",
            inboxMailboxId = inbox,
            known = known,
            prefs = prefs,
            bindingKeys = bindings,
            threadCategories =
                categories ?: emails.mapNotNull { it.threadId?.value }.associateWith { "primary" },
            serverClassifies = serverClassifies,
        )

    private fun email(
        id: String,
        subject: String? = "The quarterly figures",
        seen: Boolean = false,
        boxes: List<String> = listOf("1"),
    ): Email =
        Email(
            id = EmailId(id),
            threadId = ThreadId("t$id"),
            subject = subject,
            from =
                listOf(
                    de.plmail.jmap.mail.EmailAddress(name = "Ada Lovelace", email = "ada@x.test")
                ),
            receivedAt = "2026-08-01T09:05:00Z",
            preview = "Attached is the full breakdown.",
            mailboxIds = boxes.associateWith { true },
            keywords = if (seen) mapOf("\$seen" to true) else emptyMap(),
        )
}
