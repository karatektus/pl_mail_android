package de.plmail.core.data

import de.plmail.core.datastore.NotificationPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scope vocabulary and the rule that resolves it.
 *
 * The keys are worth pinning in a test because they are **persisted**. They sit in somebody's
 * preferences file for as long as the install lasts, so renaming one is not a refactor — it is
 * every switch on the notification screen silently reverting to its default on the next update,
 * with nothing in the UI to suggest anything happened.
 */
class NotificationScopesTest {

    @Test
    fun `a category key is its wire token, which is what the server sends`() {
        assertEquals("category:primary", NotifyScope.Category(MailCategory.PRIMARY).key)
        assertEquals("category:promotions", NotifyScope.Category(MailCategory.PROMOTIONS).key)
    }

    /**
     * The **collapse** key, so one switch covers a label in every account that binds it. Keying on
     * a binding would give somebody with three accounts three identical rows that can disagree.
     */
    @Test
    fun `a label key is the collapse key rather than a binding`() {
        assertEquals("label:lbl-7", NotifyScope.Labelled("lbl-7").key)
    }

    @Test
    fun `primary is the only scope that is on before anybody says anything`() {
        val on = MailCategory.entries.filter { NotifyScope.Category(it).isOnByDefault }

        assertEquals(listOf(MailCategory.PRIMARY), on)
        assertFalse(NotifyScope.Labelled("anything").isOnByDefault)
    }

    @Test
    fun `an untouched store allows primary and nothing else`() {
        val untouched = NotificationPrefs()

        assertTrue(untouched.allows("category:primary"))
        assertFalse(untouched.allows("category:social"))
        assertFalse(untouched.allows("label:lbl-7"))
    }

    /**
     * pl_mail#369: where the server keeps the switches, its list is the whole answer — the phone's
     * own sets, made before the server kept them, are not consulted at all.
     */
    @Test
    fun `the server's list decides alone where there is one`() {
        val shared =
            NotificationPrefs(
                enabled = setOf("category:social"),
                disabled = setOf("label:lbl-7"),
                server = setOf("label:lbl-7"),
            )

        assertTrue(shared.allows("label:lbl-7"))
        assertFalse(shared.allows("category:social"), "the phone's own choice no longer counts")
        assertFalse(shared.allows("category:primary"), "nor the default: the server spells it out")
        assertFalse(
            NotificationPrefs(server = emptySet()).allows("category:primary"),
            "an empty list is nothing on",
        )
    }

    @Test
    fun `an explicit switch wins over the default in both directions`() {
        val chosen =
            NotificationPrefs(enabled = setOf("label:lbl-7"), disabled = setOf("category:primary"))

        assertFalse(chosen.allows("category:primary"))
        assertTrue(chosen.allows("label:lbl-7"))
    }

    /**
     * A key that somehow reached both sets must not be answered by whichever check ran first. The
     * store never writes that state; this pins which way it resolves if a hand-edited file or a
     * future writer ever produces one, and "off" is the safe direction for an interruption.
     */
    @Test
    fun `off wins when a key is somehow in both sets`() {
        val contradictory =
            NotificationPrefs(
                enabled = setOf("category:primary"),
                disabled = setOf("category:primary"),
            )

        assertFalse(contradictory.allows("category:primary"))
    }

    /** The roles that never get a switch, so nothing can be switched on for the user's own post. */
    @Test
    fun `sent, drafts and the bins are never offered`() {
        listOf("sent", "drafts", "trash", "junk", "archive").forEach {
            assertTrue(it in NEVER_NOTIFIABLE_ROLES, it)
        }

        // Inbox is not in this set, and that is deliberate: it is still the
        // honest control on a server with no classifier. Where the five
        // category switches *are* drawn it is suppressed instead, by
        // NotificationSettingsRepository rather than here, because the reason
        // is about what else is on the screen rather than about the role.
        //
        // A label the user made carries no role at all, and the repository
        // filters on `role !in` this set, so a null role has to fall through it.
        val userMade: String? = null

        assertFalse("inbox" in NEVER_NOTIFIABLE_ROLES)
        assertFalse(userMade in NEVER_NOTIFIABLE_ROLES)
    }

    /**
     * A tab of the reader's own is keyed by its tab key, starts off, and a built-in is its
     * category.
     */
    @Test
    fun `an own tab has a scope of its own`() {
        assertEquals("tab:c12", NotifyScope.Tab("c12").key)
        assertFalse(NotifyScope.Tab("c12").isOnByDefault)
        assertEquals(NotifyScope.Tab("c12"), NotifyScope.ofTab("c12"))
        assertEquals(NotifyScope.Category(MailCategory.SOCIAL), NotifyScope.ofTab("social"))
    }
}
