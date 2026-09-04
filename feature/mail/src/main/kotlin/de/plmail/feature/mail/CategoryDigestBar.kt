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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.core.data.CategoryArrivals
import de.plmail.core.designsystem.PlMailDivider
import de.plmail.core.designsystem.PlMailTheme

/**
 * "Promotions, Social · 7 new", pinned, once the bundles themselves have scrolled away.
 *
 * ## Why this exists
 *
 * [CategoryBundleRow] sits inside the list's own scroller, and the note above it argued that
 * scrolling away is "what makes this a courtesy rather than a tax". That reasoning is still right
 * about the thing it was defending against — four rows of other people's promotions held
 * permanently over the mail somebody opened the app to read is a tax, and nobody wants it — but it
 * only weighed one of the two failures. The other is the one that actually gets hit: mail arrives
 * in Social while you are twenty rows down Primary, and the only way to find out is to scroll all
 * the way back to the top on the chance that something is there. A courtesy you have to go looking
 * for is not a courtesy.
 *
 * So the digest stops being one thing that is either present or gone, and becomes two: the full
 * bundles at the top, unchanged, and this — **one line, not four** — for the rest of the list. The
 * tax the original note refused to pay was four rows; this is a single row, and it carries the only
 * two facts that cannot wait, which are *which* tabs and *how many*. The senders, which are the
 * part that needs room to be worth reading, stay upstairs where there is room.
 *
 * ## Why it returns rather than opens
 *
 * Tapping scrolls back to the bundles instead of opening a category. With two categories waiting
 * there is no single destination to open, and picking one for the user would be guessing; with one
 * there would be a rule that changes behaviour based on how much mail happens to have arrived,
 * which is worse than either branch. Going back to the bundles is the same answer in every case,
 * and it lands the user on the surface that *does* let them choose — with the senders visible,
 * which is what the choice is actually made on.
 */
@Composable
internal fun CategoryDigestBar(arrivals: List<CategoryArrivals>, onClick: () -> Unit) {
    val theme = PlMailTheme.values

    // Resolved through [map] and joined afterwards, rather than in a
    // `joinToString` transform. `displayName()` is itself @Composable, and
    // `joinToString`'s transform is a *nullable* function type -- so it cannot
    // be inlined, and a @Composable call inside it will not compile. `map`'s
    // lambda is inline and non-nullable, which is the whole difference.
    val names = arrivals.map { it.category.displayName() }.joinToString(", ")

    // Summed rather than per-category, because the bar has one line and four
    // counts do not fit on it. The split is a tap away, and the total is the
    // part that decides whether to take it.
    val total = arrivals.sumOf { it.count }
    val count = pluralStringResource(R.plurals.category_new_count, total, total)

    // One sentence for the whole bar, assembled before the modifier chain so
    // the semantics block stays a single assignment. The glyphs, the two text
    // runs and the chevron are how it is drawn, not four nodes to read out.
    val spoken = stringResource(R.string.category_digest_bar_a11y, names, count)

    Column(
        // `raised` rather than the `sunken` the bundles use, and that is not an
        // inconsistency. A bundle is a well in the page and can afford to be,
        // because the page is not moving underneath it. This floats over live
        // content, so it has to be the brighter of the two surfaces or the rows
        // sliding beneath show through as a smear -- and it needs the rule at
        // the bottom for the same reason.
        modifier =
            Modifier.fillMaxWidth()
                .background(theme.colors.raised)
                .clickable(onClick = onClick)
                .clearAndSetSemantics { contentDescription = spoken }
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = theme.spacing.gutter, vertical = theme.spacing.small),
            horizontalArrangement = Arrangement.spacedBy(theme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The categories' own glyphs, at a chip's size rather than a
            // bundle's avatar. Capped at [MAX_GLYPHS]: past three they stop
            // being recognisable this small and start being texture, and the
            // names beside them are already saying it in words.
            Row(horizontalArrangement = Arrangement.spacedBy(theme.spacing.hair)) {
                arrivals.take(MAX_GLYPHS).forEach { bundle ->
                    Box(
                        modifier =
                            Modifier.size(GLYPH_WELL)
                                .background(theme.colors.accentSoft, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = bundle.category.icon(),
                            contentDescription = null,
                            tint = theme.colors.accent,
                            modifier = Modifier.size(GLYPH),
                        )
                    }
                }
            }

            Text(
                text = names,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = theme.colors.ink,
                maxLines = 1,
                // The names are what gets given up when the bar is too narrow,
                // never the count: "Promotions, Soc… · 7 new" still says both
                // things, and a truncated count says neither. The weight is
                // what makes the names the flexible one and pushes the count
                // and the chevron to the far edge.
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = count,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = theme.colors.accent,
                maxLines = 1,
            )

            // Up, not right. The bundle's chevron points right because it
            // leaves this list for another one; this goes back to the top of
            // the list already on screen, and an arrow that lied about which
            // would be worse than no arrow at all.
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowUp,
                contentDescription = null,
                tint = theme.colors.inkMuted,
                modifier = Modifier.size(theme.spacing.xLarge),
            )
        }

        // Full bleed, like the rule between the bundles and the mail. This one
        // separates the bar from content that is *moving*, which is the single
        // place a hairline here is load-bearing rather than decoration.
        PlMailDivider()
    }
}

/** The tinted disc a category glyph sits on. Smaller than a bundle's, which matches an avatar. */
private val GLYPH_WELL = 24.dp

private val GLYPH = 14.dp

/** How many category glyphs the bar draws before it stops. See the note at the call site. */
private const val MAX_GLYPHS = 3
