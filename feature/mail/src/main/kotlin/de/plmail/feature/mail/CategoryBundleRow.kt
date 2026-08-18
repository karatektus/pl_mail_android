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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * **A tile, not a row of mail — and that is a correction.** This used to be shaped exactly like a
 * message: same inset, glyph in the avatar's column, hairline under it. The reasoning was that a
 * card would read as an interruption and the bundle is the quiet channel. It was half right. The
 * quiet part is achieved by *where* it sits and by scrolling away; making it look like mail as well
 * bought nothing and cost the one thing the row has to say instantly, which is **this is not a
 * message**. Two of them in a run made it worse: with a row of mail's spacing between them, four
 * lines of text read as one item until you looked twice.
 *
 * So each bundle is now inset on [PlMailColors.sunken] with a control radius, separated from its
 * neighbour by real space, and the mark at its end is a chevron rather than the new-mail dot — a
 * chevron says *a place you go*, and "4 new" beside the name already said the rest. Sunken rather
 * than raised is the deliberate half of it: this is a well in the page, not a card lifted over it,
 * because a lifted card is precisely the interruption the original note was right to avoid.
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
                // Outside the tile: the gutter keeps it in line with the mail
                // below, and the vertical gap is what stops two bundles reading
                // as one four-line item.
                .padding(horizontal = theme.spacing.gutter, vertical = theme.spacing.tiny)
                .clip(RoundedCornerShape(theme.radii.control))
                .background(theme.colors.sunken)
                .clickable(onClick = onClick)
                // One description for the whole row rather than four nodes
                // TalkBack reads in sequence. "Promotions, 4 new, from Rail
                // Europe and 1 other" is the sentence; the glyph, the chevron
                // and the two text runs are how it is drawn, not what it says.
                .clearAndSetSemantics {
                    contentDescription = listOfNotNull(name, count, senders).joinToString(", ")
                }
                // Inside the tile. Less than a mail row's, because the fill is
                // already doing the separating that a row of mail needs
                // whitespace for.
                .padding(horizontal = theme.spacing.medium, vertical = theme.spacing.small),
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A glyph on a tinted disc, which is what a sender's avatar is too --
        // and that is the point of the accent: the shape says "an item in a
        // list", the colour says "not a person".
        Box(
            modifier =
                Modifier.size(GLYPH_WELL)
                    .background(color = theme.colors.accentSoft, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = arrivals.category.icon(),
                contentDescription = null,
                tint = theme.colors.accent,
                modifier = Modifier.size(GLYPH),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.hair),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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

        // Where a mail row puts its date, and saying the opposite thing: a date
        // is a fact about a message, a chevron is an invitation to leave this
        // list for another one. It replaced the new-mail dot the sidebar
        // carries -- correct there, redundant here beside the words "4 new",
        // and one more round mark on a row that already had two.
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = theme.colors.inkMuted,
            modifier = Modifier.size(theme.spacing.xLarge),
        )
    }
}

/** The tinted disc a bundle's glyph sits on, matching the width of a sender's avatar. */
private val GLYPH_WELL = 40.dp

private val GLYPH = 22.dp
