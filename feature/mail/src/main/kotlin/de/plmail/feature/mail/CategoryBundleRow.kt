package de.plmail.feature.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.core.data.CategoryArrivals
import de.plmail.core.designsystem.PlMailTheme

/**
 * "Promotions · 3 new — Rail Europe, Duolingo and 2 more".
 *
 * One row per category that has mail the user has not looked at, sitting above Primary's own rows.
 * This is what makes Primary a defensible place to open the app: the four other tabs are out of the
 * way, and the one thing they could not afford to lose — that something arrived — is still here.
 *
 * **Shaped like a row of mail, not like a banner.** It sits in the same list, at the same height,
 * with its glyph in the avatar's column, because it is a place to go rather than a notice to
 * dismiss. A card or a coloured strip would read as an interruption, and the whole point is that
 * this is the *quiet* channel — the one that does not buzz.
 *
 * The count and the names are both drawn, and neither is redundant. "3 new" is the size of the
 * decision; the names are what the decision is about, and somebody who recognises none of them
 * scrolls past without opening anything.
 */
@Composable
internal fun CategoryBundleRow(arrivals: CategoryArrivals, onClick: () -> Unit) {
    val theme = PlMailTheme.values
    val name = arrivals.category.displayName()

    val senders =
        when {
            arrivals.senders.isEmpty() -> null
            arrivals.moreSenders > 0 ->
                pluralStringResource(
                    R.plurals.category_senders_and_more,
                    arrivals.moreSenders,
                    arrivals.senders.joinToString(", "),
                    arrivals.moreSenders,
                )

            else -> arrivals.senders.joinToString(", ")
        }

    val count = pluralStringResource(R.plurals.category_new_count, arrivals.count, arrivals.count)

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                // One description for the whole row rather than three nodes
                // TalkBack reads in sequence. "Promotions, 3 new, from Rail
                // Europe and 2 others" is the sentence; the glyph, the dot and
                // the two text runs are how it is drawn, not what it says.
                .clearAndSetSemantics {
                    contentDescription = listOfNotNull(name, count, senders).joinToString(", ")
                }
                .padding(
                    horizontal = theme.spacing.large,
                    vertical = theme.spacing.medium,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The category's own glyph, in the column the sender avatars occupy, so
        // the row lines up with the mail below it rather than starting its own
        // margin.
        Icon(
            imageVector = arrivals.category.icon(),
            contentDescription = null,
            tint = theme.colors.accent,
            modifier = Modifier.size(GLYPH),
        )

        Column(
            modifier = Modifier.weight(1f).padding(start = theme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.hair),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    // Bold, like an unread row's sender, and for the same
                    // reason: this is mail nobody has read.
                    fontWeight = FontWeight.SemiBold,
                    color = theme.colors.ink,
                )

                Text(
                    text = count,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.colors.accent,
                    modifier = Modifier.padding(start = theme.spacing.small),
                )
            }

            senders?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // The same dot the sidebar row carries, so the two say the same thing in
        // the same mark. Trailing, where a row's date sits, because that is the
        // column the eye already checks for "how recent".
        Box(
            modifier =
                Modifier.size(DOT).background(color = theme.colors.accent, shape = CircleShape)
        )
    }
}

/** The avatar column's width, so a bundle's glyph sits where a sender's disc does. */
private val GLYPH = 24.dp

private val DOT = 8.dp
