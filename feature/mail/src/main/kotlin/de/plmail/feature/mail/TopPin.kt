package de.plmail.feature.mail

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow

/**
 * Whether a list that was at its top should be put back there (#60).
 *
 * A keyed `LazyColumn` keeps its first visible *row* in place when rows are inserted before it.
 * That is right for somebody reading further down, and wrong at the top of an inbox: mail that
 * arrives there was inserted above the first row and sat out of sight, with nothing on screen to
 * say it had come. At the top the list stays at the top, as the web and every other mail app do.
 *
 * Fed every position the list reports. Only a position that moved *without* a scroll can be an
 * insertion, so a scroll — a drag, a fling, the jump to the top — decides whether the list is
 * pinned and is never undone. The first position is only recorded: a list restored part-way down,
 * after a rotation, was not at the top and must not be sent there.
 */
internal class TopPin {

    private var pinned: Boolean? = null

    /** True when the list was at the top and an insertion has moved it: put it back. */
    fun moved(index: Int, offset: Int, isScrolling: Boolean): Boolean {
        val atTop = index == 0 && offset == 0
        val wasPinned = pinned

        return when {
            wasPinned == null || atTop || isScrolling -> {
                pinned = atTop
                false
            }
            else -> wasPinned
        }
    }
}

/**
 * Keeps [listState] at its top while it is there and rows arrive above the first one. See [TopPin].
 */
@Composable
internal fun StayAtTopOnArrivals(listState: LazyListState) {
    LaunchedEffect(listState) {
        val pin = TopPin()

        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }
            .collect { (index, offset, scrolling) ->
                // Before the next layout and without an animation: the rows
                // that arrived are drawn where they belong, rather than the
                // list being seen to slide back to them.
                if (pin.moved(index, offset, scrolling)) listState.requestScrollToItem(0)
            }
    }
}
