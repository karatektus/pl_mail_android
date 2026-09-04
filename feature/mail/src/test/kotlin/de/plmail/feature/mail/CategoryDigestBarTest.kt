package de.plmail.feature.mail

import de.plmail.core.data.CategoryArrivals
import de.plmail.core.data.MailCategory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When Primary pins the one-line digest over the list.
 *
 * The bundles scroll away with the mail, which is what keeps four rows of other people's promotions
 * from sitting permanently over the inbox — and is also why the only way to find out that something
 * landed in Social was to scroll all the way back to the top and look. [CategoryDigestBar] is the
 * answer to the second problem that does not reintroduce the first, and this is the rule that
 * decides when it is on screen.
 */
class CategoryDigestBarTest {

    private fun arrivals(vararg categories: MailCategory) = categories.map {
        CategoryArrivals(category = it, count = 3, senders = listOf("Someone"), moreSenders = 0)
    }

    /** At the top of the list the bundles are right there, and a bar would be saying it twice. */
    @Test
    fun `the bar stays away while the bundles are on screen`() {
        assertFalse(digestHasScrolledAway(0, arrivals(MailCategory.PROMOTIONS)))
    }

    /**
     * A bundle half scrolled out is still a bundle you can read.
     *
     * `firstVisibleItemIndex` does not advance until a row's top edge has gone, so this is the
     * boundary case rather than an arbitrary one: index 0 means the first bundle is still partly
     * visible, and the bar appearing over it would be two copies of the same sentence.
     */
    @Test
    fun `the bar waits until the last bundle has actually gone`() {
        val two = arrivals(MailCategory.PROMOTIONS, MailCategory.SOCIAL)

        assertFalse(digestHasScrolledAway(1, two), "one bundle is still on screen")
        assertTrue(digestHasScrolledAway(2, two))
    }

    /**
     * The case that would have pinned an empty bar over every other list in the app.
     *
     * With no arrivals the comparison is `0 >= 0`, which is true — so the emptiness check is doing
     * real work rather than guarding against a state that cannot happen. Every list but Primary is
     * in this state permanently.
     */
    @Test
    fun `a list with no bundles never shows a bar`() {
        assertFalse(digestHasScrolledAway(0, emptyList()))
        assertFalse(digestHasScrolledAway(40, emptyList()))
    }
}
