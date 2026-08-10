package de.plmail.feature.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.CalendarViewDay
import androidx.compose.material.icons.outlined.CalendarViewMonth
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.plmail.core.designsystem.PlMailTheme

/**
 * Agenda · Tag · Woche · Monat, as one icon in the app bar.
 *
 * **This replaces a segmented control that had a whole row to itself, and the row is the reason.**
 * That control was written for discoverability — four names on screen, because the defect it came
 * from was somebody hunting for a switcher that did not exist — and it bought that with roughly a
 * touch target of vertical space on every calendar screen, in a product whose whole job on this
 * surface is showing hours in a day. The owner's call is that the calendar needs the room more than
 * the switcher needs the width, so the four names moved behind this.
 *
 * What keeps that from re-opening the original defect is that **the button wears the view it is
 * in**: the bar shows a grid in Month and stacked cards in Agenda, so it is still a control that
 * says something about the calendar rather than a third anonymous glyph. That is also what Google's
 * own calendar does with the same slot, which is what most people arriving here will have learned.
 *
 * TalkBack keeps what the segmented row had — each item carries [selected], so the current view is
 * announced as chosen rather than being distinguishable only by a tick somebody cannot see.
 */
@Composable
internal fun ViewMenu(
    chosen: CalendarViewMode,
    onChoose: (CalendarViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Saveable, so the menu is still open after a rotation taken while reading
    // it -- the four items are a decision, and the phone turning is not an
    // answer to it.
    var isOpen by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { isOpen = true }) {
            Icon(
                imageVector = chosen.icon,
                contentDescription = stringResource(R.string.calendar_view_choose),
            )
        }

        DropdownMenu(
            expanded = isOpen,
            onDismissRequest = { isOpen = false },
            containerColor = PlMailTheme.colors.surface,
        ) {
            CalendarViewMode.entries.forEach { view ->
                val isChosen = view == chosen

                DropdownMenuItem(
                    modifier = Modifier.semantics { selected = isChosen },
                    text = {
                        Text(
                            text = stringResource(view.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingIcon = {
                        // The slot is filled either way rather than only for the
                        // chosen one: a leading icon that appears on one item
                        // indents that item alone, and four names that do not
                        // share a left edge read as a rendering fault.
                        Box(
                            modifier = Modifier.size(TICK),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isChosen) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    // Null, because the item's `selected`
                                    // semantics already say this and a
                                    // description here would have TalkBack say
                                    // it twice.
                                    contentDescription = null,
                                    tint = PlMailTheme.colors.accent,
                                )
                            }
                        }
                    },
                    colors =
                        MenuDefaults.itemColors(
                            textColor =
                                if (isChosen) PlMailTheme.colors.accent else PlMailTheme.colors.ink
                        ),
                    onClick = {
                        // Closed here rather than left to the dismiss, so the
                        // menu is gone in the same frame the calendar under it
                        // changes shape.
                        isOpen = false
                        onChoose(view)
                    },
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
            CalendarViewMode.MONTH_AGENDA -> R.string.calendar_view_month_agenda
        }

/**
 * The glyph the app bar wears while this view is open.
 *
 * Deliberately *not* `Icons.Outlined.CalendarMonth`, which this screen already spends on its empty
 * state: an empty month would otherwise draw the same picture twice, once as a control and once as
 * an illustration, and only one of them does anything when tapped.
 *
 * The mixture view wears `EventNote` — a page with ruled lines on it — because the two things it
 * has to be told apart from are the month grid it sits beside in this menu and the agenda at the
 * top of it, and a glyph that is a grid with a list in it does not exist. Ruled lines under a date
 * is the closest either icon set gets to "a month, and what is in it".
 */
private val CalendarViewMode.icon: ImageVector
    get() =
        when (this) {
            CalendarViewMode.AGENDA -> Icons.Outlined.ViewAgenda
            CalendarViewMode.DAY -> Icons.Outlined.CalendarViewDay
            CalendarViewMode.WEEK -> Icons.Outlined.CalendarViewWeek
            CalendarViewMode.MONTH -> Icons.Outlined.CalendarViewMonth
            CalendarViewMode.MONTH_AGENDA -> Icons.AutoMirrored.Outlined.EventNote
        }

/** Material's own leading-icon size; the tick and the gap it holds open have to agree on it. */
private val TICK = 24.dp
