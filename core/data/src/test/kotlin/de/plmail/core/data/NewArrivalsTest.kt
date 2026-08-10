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
     */
    @Test
    fun `an unthreaded message falls back to its own id`() {
        val found = arrivals(email("5").copy(threadId = null))

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
     * A plMail that predates the classifier, or one whose backfill has not run, reports null for
     * every conversation it has. Reading null as "not Primary" would mean a phone that never makes
     * a sound for any message the user owns, and nothing on the screen would say why. Primary here
     * is not "the server said primary" but "in the inbox and not filed under one of the other
     * four".
     */
    @Test
    fun `an unclassified conversation counts as primary`() {
        val found = arrivals(email("5"), categories = emptyMap())

        assertEquals(listOf("5"), found.map { it.emailId })
    }

    /** A sixth category invented by a newer server falls the same way, and for the same reason. */
    @Test
    fun `a category this build cannot name counts as primary`() {
        val found = arrivals(email("5"), categories = mapOf("t5" to "purchases"))

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
        categories: Map<String, String?> = emptyMap(),
    ): List<NewMessage> =
        newArrivals(
            emails = emails.toList(),
            accountKey = account,
            accountName = "someone@example.com",
            inboxMailboxId = inbox,
            known = known,
            prefs = prefs,
            bindingKeys = bindings,
            threadCategories = categories,
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
