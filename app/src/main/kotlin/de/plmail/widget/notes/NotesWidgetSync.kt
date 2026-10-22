package de.plmail.widget.notes

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.plmail.core.data.NotesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Re-renders the notes widget whenever the app's notes change — a note saved, ticked, pinned or
 * binned — a second after the last change, so typing does not redraw the home screen per key.
 */
@Singleton
class NotesWidgetSync
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val notes: NotesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            // drop(1): the empty list a fresh process starts with is not a change.
            notes.notes.drop(1).collectLatest {
                delay(QUIET_MS)
                runCatching { NotesWidget.updateAll(context) }
            }
        }
    }

    private companion object {
        const val QUIET_MS = 1_000L
    }
}
