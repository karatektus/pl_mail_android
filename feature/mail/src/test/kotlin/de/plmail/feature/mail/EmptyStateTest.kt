package de.plmail.feature.mail

import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.LoadStates
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the list is allowed to tell someone their mail is not there.
 *
 * This is the smallest possible surface and it is worth a file of its own, because getting it wrong
 * is silent: the app draws a calm, well-designed screen that says the label is empty, and the user
 * believes it. There is no crash, no log line, and no second opinion anywhere on screen.
 *
 * The states below are not invented. They are the ones Paging actually produces around a
 * `RemoteMediator` over a Room table, and [theGapBetweenTheMediatorWritingAndPagingReading] is the
 * one that shipped — reproduced here as data because reproducing it on a device needs the Room
 * invalidation to lose a race it usually wins.
 */
class EmptyStateTest {

    /**
     * The state that shipped, and the reason this function exists.
     *
     * The mediator has committed rows and returned; Paging has not been handed them yet, because
     * the invalidation that would tell it is still queued behind the message rows the same
     * transaction wrote. Every load state says idle. The item count says zero. Only the table knows
     * better.
     */
    @Test
    fun theGapBetweenTheMediatorWritingAndPagingReading() {
        assertFalse(hasNothingToShow(settled(), rowsInFeed = 1))
    }

    /**
     * Null is not zero.
     *
     * Before anything has read the table there is no answer, and drawing "nothing here" for it
     * would put the empty state on screen for a frame of every single cold launch — including on a
     * device whose inbox is sitting on disk, fully synced.
     */
    @Test
    fun anUnreadCountIsNotAnEmptyList() {
        assertFalse(hasNothingToShow(settled(), rowsInFeed = null))
    }

    @Test
    fun aLabelThatGenuinelyHasNoMailSaysSo() {
        assertTrue(hasNothingToShow(settled(), rowsInFeed = 0))
    }

    /**
     * The mediator is the only one loading, which is the whole first visit to a list.
     *
     * `CombinedLoadStates.refresh` follows the mediator once one exists, so this case would survive
     * a version that only looked at the convenience property. The next test is the one that would
     * not.
     */
    @Test
    fun nothingIsClaimedWhileTheServerIsStillBeingAsked() {
        assertFalse(
            hasNothingToShow(
                CombinedLoadStates(
                    refresh = LoadState.Loading,
                    prepend = idle,
                    append = idle,
                    source = LoadStates(refresh = idle, prepend = idle, append = idle),
                    mediator =
                        LoadStates(refresh = LoadState.Loading, prepend = idle, append = idle),
                ),
                rowsInFeed = 0,
            )
        )
    }

    /**
     * The source is reading rows the mediator has already finished writing.
     *
     * This is the second half of the same window: the invalidation has landed, a new generation is
     * querying, and the mediator — which `CombinedLoadStates.refresh` follows — has been idle for
     * some time. A check on the convenience property alone reads this as settled.
     */
    @Test
    fun nothingIsClaimedWhileTheTableIsStillBeingRead() {
        assertFalse(
            hasNothingToShow(
                CombinedLoadStates(
                    refresh = idle,
                    prepend = idle,
                    append = idle,
                    source = LoadStates(refresh = LoadState.Loading, prepend = idle, append = idle),
                    mediator = LoadStates(refresh = idle, prepend = idle, append = idle),
                ),
                rowsInFeed = 0,
            )
        )
    }

    /**
     * With no server reachable there is no mediator at all, and the table is the only answer there
     * is. It has to still work: this is the offline list.
     */
    @Test
    fun theCachedOnlyListWithNoMediatorStillAnswers() {
        val cachedOnly =
            CombinedLoadStates(
                refresh = idle,
                prepend = idle,
                append = idle,
                source = LoadStates(refresh = idle, prepend = idle, append = idle),
                mediator = null,
            )

        assertTrue(hasNothingToShow(cachedOnly, rowsInFeed = 0))
        assertFalse(hasNothingToShow(cachedOnly, rowsInFeed = 3))
    }

    /** Everything idle, which is what both halves of the window look like from outside. */
    private fun settled(): CombinedLoadStates =
        CombinedLoadStates(
            refresh = idle,
            prepend = idle,
            append = idle,
            source = LoadStates(refresh = idle, prepend = idle, append = idle),
            mediator = LoadStates(refresh = idle, prepend = idle, append = idle),
        )

    private val idle = LoadState.NotLoading(endOfPaginationReached = false)
}
