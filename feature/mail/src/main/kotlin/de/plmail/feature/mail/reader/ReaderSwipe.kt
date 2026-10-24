package de.plmail.feature.mail.reader

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import de.plmail.feature.mail.R
import kotlin.math.abs

/**
 * How much of the reader's width a swipe has to cross before it opens another conversation.
 *
 * The calendar's threshold, so the two horizontal swipes in the app ask for the same pull. A
 * fraction rather than a fixed distance, so a tablet's reader pane does not change conversation on
 * what is a twitch at its width.
 */
internal const val SWIPE_FRACTION = 0.2f

/**
 * A horizontal swipe across the reader opens the conversation beside it in the list.
 *
 * Judged when the finger lifts rather than followed as it moves, like the calendar's `pageOnSwipe`:
 * the conversation beside this one is not composed while the finger is down — composing it would
 * mark its newest message read on a glance the user never committed to — so there is nothing honest
 * to drag into view. The reader slides once the swipe has counted instead.
 *
 * Left is next, the direction the content moves rather than the one the finger did, as in every
 * pager on this platform. There is no right-to-left case because there is no right-to-left
 * translation; one would mirror this.
 *
 * This covers everything but the message bodies. A body is a WebView, which takes the gesture
 * before Compose sees it, and decides for itself — see `ReaderWebView`.
 *
 * Each direction is null where there is no conversation that way, which also keeps it out of the
 * accessibility actions: TalkBack cannot swipe through the reader, so the same two moves are
 * offered there by name.
 */
@Composable
internal fun Modifier.swipeBetweenConversations(
    onNext: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
): Modifier {
    if (onNext == null && onPrevious == null) return this

    // The gesture is installed once and must call whichever pair is current:
    // keyed on the lambdas instead, it would restart mid-drag on every
    // recomposition of the reader.
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)

    val nextLabel = stringResource(R.string.reader_next_conversation)
    val previousLabel = stringResource(R.string.reader_previous_conversation)

    return semantics {
            customActions = buildList {
                onNext?.let { go ->
                    add(
                        CustomAccessibilityAction(nextLabel) {
                            go()
                            true
                        }
                    )
                }
                onPrevious?.let { go ->
                    add(
                        CustomAccessibilityAction(previousLabel) {
                            go()
                            true
                        }
                    )
                }
            }
        }
        .pointerInput(Unit) {
            var travelled = 0f

            detectHorizontalDragGestures(
                onDragStart = { travelled = 0f },
                onDragEnd = {
                    if (abs(travelled) >= size.width * SWIPE_FRACTION) {
                        if (travelled < 0) next?.invoke() else previous?.invoke()
                    }
                },
                onHorizontalDrag = { _, delta -> travelled += delta },
            )
        }
}
