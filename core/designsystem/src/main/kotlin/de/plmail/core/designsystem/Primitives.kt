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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
                .background(background, shape)
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
