package de.plmail.widget

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import de.plmail.core.data.BackgroundRefresh
import de.plmail.core.data.CalendarRepository
import de.plmail.core.data.CalendarWindow
import de.plmail.core.data.NotesRepository
import de.plmail.widget.calendar.CalendarWidgetUpdater
import de.plmail.widget.notes.NotesWidget
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the calendar and the notes — and their home-screen widgets — current without the app being
 * opened: when the server pushes that one of them changed (an edit on the web or another device),
 * and on the periodic background sync as a backstop.
 *
 * The calendar window is the one the widgets draw: a week back (yesterday's events still on the
 * month widget) to two months ahead (the agenda's month and then some).
 */
@Singleton
class WidgetBackgroundRefresh
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val calendar: CalendarRepository,
    private val notes: NotesRepository,
) : BackgroundRefresh {

    override suspend fun refresh(types: Set<String>) {
        if (BackgroundRefresh.CALENDAR in types) {
            val today = LocalDate.now()
            runCatching {
                calendar.refresh(
                    CalendarWindow(today.minusDays(DAYS_BACK), today.plusDays(DAYS_AHEAD))
                )
            }
            runCatching { CalendarWidgetUpdater.updateAll(context) }
        }

        if (BackgroundRefresh.NOTES in types) {
            runCatching { notes.refresh() }
            runCatching { NotesWidget.updateAll(context) }
        }
    }

    private companion object {
        const val DAYS_BACK = 7L
        const val DAYS_AHEAD = 62L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetBackgroundRefreshModule {
    @Binds
    @IntoSet
    @Singleton
    abstract fun widgetRefresh(real: WidgetBackgroundRefresh): BackgroundRefresh
}
