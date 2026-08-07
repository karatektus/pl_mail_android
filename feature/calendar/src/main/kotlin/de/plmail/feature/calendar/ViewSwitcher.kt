package de.plmail.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import de.plmail.core.designsystem.PlMailTheme

/**
 * Agenda · Tag · Woche · Monat, as one control that reads as one control.
 *
 * **A segmented control on its own row rather than a menu in the app bar, and that is the whole
 * point of this file.** The report that started this work was somebody tapping the Today icon
 * expecting a view switcher: the icon reads as a calendar page, there was no switcher, and a menu
 * would have answered that by hiding the four views behind a third icon of the same size beside it.
 * Discoverability *is* the requirement here, so the four names are on the screen.
 *
 * **Not Material's `SegmentedButton`**, for the reason `AppearanceScreen`'s own row of choices is
 * not: it takes its shape from the Material shape scale, which in this design system is the *pane*
 * radius, so the boxed layout would round a control the tokens say must never be rounded. Written
 * out against `radii.control`, which is the rule rather than an exception to it.
 *
 * **Its own row rather than inside the `TopAppBar`.** Four segments plus back, Today and New do not
 * fit 320dp — the width the app already tests German at — and the first thing a squeezed row does
 * is ellipsise the labels, which turns the switcher back into four unlabelled marks. On its own row
 * each segment is a quarter of the screen, which fits "Agenda" at the longest density.
 *
 * TalkBack hears it as one radio group: `selectableGroup` on the row, `Role.RadioButton` and a
 * `selected` state on each segment, so the announcement is "Woche, ausgewählt, 3 von 4" rather than
 * four unrelated buttons that happen to sit next to each other.
 */
@Composable
internal fun ViewSwitcher(
    chosen: CalendarViewMode,
    onChoose: (CalendarViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = PlMailTheme.values
    val group = stringResource(R.string.calendar_view_switcher)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { contentDescription = group }
                .selectableGroup()
                .padding(
                    horizontal = theme.spacing.gutter,
                    vertical = theme.spacing.small,
                ),
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.tiny),
    ) {
        CalendarViewMode.entries.forEach { view ->
            val isChosen = view == chosen
            val label = stringResource(view.labelRes)

            Box(
                modifier =
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(theme.radii.control))
                        .selectable(
                            selected = isChosen,
                            role = Role.RadioButton,
                            onClick = { onChoose(view) },
                        )
                        .background(if (isChosen) theme.colors.accentSoft else theme.colors.surface)
                        .border(
                            width = theme.spacing.hair,
                            color = if (isChosen) theme.colors.accent else theme.colors.line,
                            shape = RoundedCornerShape(theme.radii.control),
                        )
                        // A control the user taps, so it clears the touch target
                        // whatever the density scale did to the padding.
                        .heightIn(min = theme.spacing.touchTarget)
                        .padding(horizontal = theme.spacing.tiny),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isChosen) theme.colors.accent else theme.colors.inkSoft,
                    maxLines = 1,
                    // Ellipsised rather than shrunk: a segment whose text is a
                    // point smaller than its neighbours' looks like a rendering
                    // fault, and 320dp/4 fits every one of these four words.
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** What each view is called on screen. German and English both live in `strings.xml`. */
internal val CalendarViewMode.labelRes: Int
    get() =
        when (this) {
            CalendarViewMode.AGENDA -> R.string.calendar_view_agenda
            CalendarViewMode.DAY -> R.string.calendar_view_day
            CalendarViewMode.WEEK -> R.string.calendar_view_week
            CalendarViewMode.MONTH -> R.string.calendar_view_month
        }
