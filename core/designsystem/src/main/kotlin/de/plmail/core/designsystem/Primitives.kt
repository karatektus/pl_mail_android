package de.plmail.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A hairline.
 *
 * One dp, in the theme's line colour, and the *only* separator this product uses between rows —
 * along with a surface shift. Material's `HorizontalDivider` defaults to `outlineVariant` at a
 * thickness that reads as a rule; this is a line you notice only when it is missing, which is what
 * a list of forty rows needs.
 */
@Composable
fun PlMailDivider(modifier: Modifier = Modifier, startIndent: Dp = 0.dp) {
    val theme = LocalPlMailTheme.current

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = startIndent)
                .height(theme.spacing.hair)
                .background(theme.colors.line)
    )
}

/**
 * A container for content that is its own object — a card, a banner, a sheet.
 *
 * Flat and boxed differ here and nowhere else: boxed lifts onto [PlMailColors.raised] with the pane
 * radius and a hairline border, flat leaves it on the page. **No elevation, in either.** A drop
 * shadow under a list of mail is decoration that costs a compositing pass and hides the line that
 * was doing the actual separating.
 */
@Composable
fun PlMailPane(
    modifier: Modifier = Modifier,
    tone: PaneTone = PaneTone.RAISED,
    content: @Composable () -> Unit,
) {
    val theme = LocalPlMailTheme.current
    val isBoxed = theme.layout == PlMailLayout.BOXED

    val background =
        when (tone) {
            PaneTone.RAISED -> if (isBoxed) theme.colors.raised else theme.colors.surface
            PaneTone.SUNKEN -> theme.colors.sunken
            PaneTone.ACCENT -> theme.colors.accentSoft
            PaneTone.DANGER -> theme.colors.dangerSoft
            PaneTone.WARNING -> theme.colors.warningSoft
            PaneTone.INFO -> theme.colors.infoSoft
        }

    val shape = RoundedCornerShape(theme.radii.pane)

    Box(
        modifier =
            modifier
                // The alpha knob applies to the pane's *fill*, never to its
                // contents: `Modifier.alpha` would fade the text written on it
                // too, which is a pane that is hard to read rather than a pane
                // you can see through. It is only ever below 1 in the boxed
                // layout, because a translucent pane on a flat page is a
                // translucent thing over the same colour it is drawn on --
                // invisible, and a compositing layer for nothing.
                .background(
                    color =
                        if (isBoxed) background.copy(alpha = theme.surfaces.alpha) else background,
                    shape = shape,
                )
                .then(
                    if (isBoxed || tone != PaneTone.RAISED) {
                        Modifier.border(theme.spacing.hair, theme.colors.line, shape)
                    } else {
                        Modifier
                    }
                )
    ) {
        content()
    }
}

enum class PaneTone {
    RAISED,
    SUNKEN,
    ACCENT,
    DANGER,
    WARNING,
    INFO,
}

/**
 * The letter avatar, coloured from an address.
 *
 * The **address**, never the display name: hashing the name recolours the same person the moment
 * they reconfigure their mail client, and a list where colours move is a list where colour means
 * nothing. `absoluteValue` on the hash rather than a mask, and `Int.MIN_VALUE` handled — its
 * absolute value is itself, which is negative, and a negative index crashes the row rather than
 * mis-colouring it.
 */
@Composable
fun PlMailAvatar(seed: String, label: String, modifier: Modifier = Modifier) {
    val theme = LocalPlMailTheme.current
    val palette = theme.colors.avatars
    val background = palette[avatarIndex(seed, palette.size)]

    Box(
        modifier =
            modifier
                .size(theme.spacing.touchTarget - theme.spacing.small)
                .background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = theme.colors.onAvatar,
            textAlign = TextAlign.Center,
        )
    }
}

/** Which ramp colour an address gets. Extracted so a test can pin the two-negatives case. */
fun avatarIndex(seed: String, size: Int): Int {
    val hash = seed.lowercase().hashCode()

    // Not `abs(hash)`: abs(Int.MIN_VALUE) is Int.MIN_VALUE, still negative, and
    // the modulo of it is negative too -- an IndexOutOfBounds on exactly one
    // address in four billion, which is the kind of crash nobody reproduces.
    return ((hash % size) + size) % size
}

/**
 * A screen with nothing on it, said properly.
 *
 * Always a sentence and never only an icon: "no messages" and "we could not reach your server" look
 * identical as a grey envelope, and this product's users are the ones who have to fix the second.
 */
@Composable
fun PlMailEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val theme = LocalPlMailTheme.current

    Column(
        modifier = modifier.fillMaxSize().padding(theme.spacing.xxLarge),
        verticalArrangement =
            Arrangement.spacedBy(theme.spacing.medium, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = theme.colors.inkFaint,
            modifier = Modifier.size(theme.spacing.xxLarge),
        )

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = theme.colors.ink,
            textAlign = TextAlign.Center,
        )

        body?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.colors.inkMuted,
                textAlign = TextAlign.Center,
            )
        }

        action?.invoke()
    }
}

/**
 * A banner over content — an unreachable account, a rejected credential.
 *
 * Above the list, never instead of it. "Your NAS is rebooting" and "your mail is gone" have to look
 * different, and the mail that is already on the device is still correct.
 */
@Composable
fun PlMailBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: PaneTone = PaneTone.WARNING,
    action: (@Composable () -> Unit)? = null,
) {
    val theme = LocalPlMailTheme.current

    val ink =
        when (tone) {
            PaneTone.DANGER -> theme.colors.danger
            PaneTone.INFO -> theme.colors.info
            PaneTone.ACCENT -> theme.colors.accent
            else -> theme.colors.warning
        }

    PlMailPane(modifier = modifier.fillMaxWidth(), tone = tone) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(theme.spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(theme.spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = ink,
                modifier = Modifier.weight(1f),
            )

            action?.invoke()
        }
    }
}

/**
 * A label, as it appears on a conversation.
 *
 * Small, quiet and **never accented**. The row it sits on has exactly one accent — the unread dot —
 * and that is the whole reason the dot works: a screen of unread mail carries one small repeated
 * mark in a column of its own. A coloured chip beside it would put a second repeated mark on every
 * labelled row and the dot would stop meaning anything.
 *
 * So a chip is a surface shift and a hairline, like every other separated thing in this product,
 * with `inkMuted` text — metadata, which is what a label is next to a subject.
 *
 * **Colour is coming and is not here yet.** `Label::$color` is a real column in plMail and JMAP
 * does not expose it: `Mailbox/get` never returns it, `update` refuses it, and `create` accepts it
 * and silently drops it. That is queued in `docs/SERVER_REQUESTS.md`, and the reason a colour is
 * not invented locally in the meantime is that a hash of the name would give the same label a
 * different colour on the phone than on the web, which is worse than no colour at all — the entire
 * point of colouring a label is that it is the same everywhere. When the server does expose it,
 * this composable takes a colour parameter and the two lines below resolve from it instead; nothing
 * about the shape, size or placement changes.
 */
@Composable
fun PlMailLabelChip(text: String, modifier: Modifier = Modifier) {
    val theme = LocalPlMailTheme.current
    val shape = RoundedCornerShape(theme.radii.control)

    Box(
        modifier =
            modifier
                // Radius from `control`, not `pane`: a chip is a control, and a
                // theme that rounds panes generously must not turn a 16dp chip
                // into a lozenge.
                .clip(shape)
                .background(theme.colors.sunken)
                .border(width = theme.spacing.hair, color = theme.colors.line, shape = shape)
                // Horizontal padding only, and that is a layout constraint
                // rather than taste. A chip shares the snippet's line on a list
                // row, so anything that makes it taller than one line of that
                // text makes every labelled conversation taller than every
                // unlabelled one — and a list that scrolls at two heights looks
                // broken for a reason nobody can name.
                //
                // One dp top and bottom was the previous version, under a
                // comment saying it had been chosen so this would not happen. It
                // did: 19.4dp against the snippet's 18.3dp, which is three pixels
                // at 420dpi — invisible on any one row and visible down a
                // screenful. `ThreadRowLayoutTest` now measures the labelled
                // cases against the unlabelled one, so the next version of this
                // mistake fails a build instead of shipping.
                //
                // The room the padding was there for is already inside the text:
                // the style asks for a 1.3 line and the platform's font padding
                // adds to that, so the border clears the glyphs by about two dp
                // on each side without the box growing.
                .padding(horizontal = theme.spacing.small)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = theme.colors.inkMuted,
            maxLines = 1,
            // The end, not the middle. A label name is read left to right and
            // its first word is nearly always the one that identifies it —
            // unlike a nested *path* in the sidebar, where both ends matter.
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = CHIP_MAX_WIDTH),
        )
    }
}

/**
 * How wide one chip may get.
 *
 * Long label names exist — "Steuer 2025", "Wohnung/Nebenkosten" — and one of them must not take the
 * line the snippet needs. Capped rather than scaled, because the cap is about the *row*, and the
 * row's width is the same whatever the chip says.
 *
 * This is the cap on *one* chip. What a row's whole cluster may take is the row's own business and
 * lives there — a chip drawn somewhere else should not inherit a bound that was reasoned about
 * against a thread row's snippet.
 */
private val CHIP_MAX_WIDTH = 96.dp
