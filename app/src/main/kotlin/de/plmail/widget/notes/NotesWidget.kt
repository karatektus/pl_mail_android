package de.plmail.widget.notes

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.plmail.R
import de.plmail.core.data.NotesRepository
import de.plmail.feature.notes.noteColorArgb
import de.plmail.feature.notes.notePlainText
import de.plmail.jmap.methods.NoteObject
import kotlinx.coroutines.withTimeoutOrNull

/** How a widget update reaches the app's [NotesRepository]; see CalendarWidgetDependencies. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotesWidgetDependencies {
    fun notes(): NotesRepository
}

/**
 * Notes on the home screen, laid out after the owner's sketch: no title bar, just the notes as
 * coloured cards filling the widget — pinned first, then the most recently changed — each with its
 * title, the start of its text or its first checklist items, and a small round `+` in the top right
 * corner, over the first card, for a new note. Tapping a card opens that note; the list scrolls.
 *
 * Notes are not cached on the device, so an update reads them from the server (briefly; the last
 * list stays if the server does not answer) and the app re-renders the widget whenever its notes
 * change.
 */
class NotesWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository =
            EntryPointAccessors.fromApplication(context, NotesWidgetDependencies::class.java)
                .notes()
        withTimeoutOrNull(REFRESH_TIMEOUT_MS) { runCatching { repository.refresh() } }
        val notes = repository.notes.value.filter { !it.trashed && !it.archived }
        val loaded = repository.loaded.value

        provideContent { NotesContent(context, notes, loaded) }
    }

    companion object {
        private const val REFRESH_TIMEOUT_MS = 10_000L

        /** Re-renders every notes widget; called when the app's notes change. */
        suspend fun updateAll(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            manager.getGlanceIds(NotesWidget::class.java).forEach { id ->
                runCatching { NotesWidget().update(context, id) }
            }
        }
    }
}

private object NotesWidgetColors {
    val card = ColorProvider(day = Color(0xFFF6F1E3), night = Color(0xFF1F1F23))
    val onCard = ColorProvider(day = Color(0xFF1F1F1F), night = Color(0xFFE8EAED))
    val muted = ColorProvider(day = Color(0xFF5F6368), night = Color(0xFF9AA0A6))
    val plainNote = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF2D2E31))
    val accent = ColorProvider(day = Color(0xFF0B57D0), night = Color(0xFFA8C7FA))
    val fab = ColorProvider(day = Color(0xFFE6DCC4), night = Color(0xFF2D2E31))
}

@Composable
private fun NotesContent(context: Context, notes: List<NoteObject>, loaded: Boolean) {
    val newNote = actionStartActivity(NotesWidgetIntents.newNote(context))
    Box(
        modifier =
            GlanceModifier.fillMaxSize()
                .appWidgetBackground()
                .background(NotesWidgetColors.card)
                .cornerRadius(20.dp)
                .padding(10.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        if (notes.isEmpty()) {
            Text(
                text =
                    context.getString(
                        if (loaded) R.string.notes_widget_empty else R.string.notes_widget_offline
                    ),
                modifier = GlanceModifier.fillMaxSize().padding(4.dp).clickable(newNote),
                style = TextStyle(color = NotesWidgetColors.muted, fontSize = 13.sp),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(notes.take(MAX_NOTES), itemId = { it.id.hashCode().toLong() }) { note ->
                    NoteCard(context, note)
                }
            }
        }
        // The new-note button: small, in the top right corner, over the first card's corner.
        Box(
            modifier =
                GlanceModifier.size(28.dp)
                    .background(NotesWidgetColors.fab)
                    .cornerRadius(14.dp)
                    .clickable(newNote),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_add),
                contentDescription = context.getString(R.string.notes_widget_new),
                colorFilter = ColorFilter.tint(NotesWidgetColors.accent),
                modifier = GlanceModifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun NoteCard(context: Context, note: NoteObject) {
    val fill =
        noteColorArgb(note.color, dark = false)?.let { light ->
            val dark = noteColorArgb(note.color, dark = true) ?: light
            ColorProvider(day = Color(light), night = Color(dark))
        } ?: NotesWidgetColors.plainNote

    Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Column(
            modifier =
                GlanceModifier.fillMaxWidth()
                    .background(fill)
                    .cornerRadius(16.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clickable(actionStartActivity(NotesWidgetIntents.open(context, note.id)))
        ) {
            if (note.title.isNotBlank()) {
                Text(
                    text = (if (note.pinned) "📌 " else "") + note.title,
                    maxLines = 2,
                    style =
                        TextStyle(
                            color = NotesWidgetColors.onCard,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                )
            }
            if (note.body.isNotBlank()) {
                if (note.title.isNotBlank()) Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = notePlainText(note.body),
                    maxLines = 5,
                    style = TextStyle(color = NotesWidgetColors.onCard, fontSize = 15.sp),
                )
            }
            if (note.items.isNotEmpty()) {
                val open = note.items.filter { !it.done }
                val done = note.items.size - open.size
                open.take(MAX_ITEMS).forEach { item ->
                    Text(
                        text = "☐  " + item.text,
                        maxLines = 1,
                        style = TextStyle(color = NotesWidgetColors.onCard, fontSize = 13.sp),
                    )
                }
                if (open.size > MAX_ITEMS || done > 0) {
                    Text(
                        text =
                            context.resources.getQuantityString(
                                R.plurals.notes_widget_more,
                                (open.size - MAX_ITEMS).coerceAtLeast(0) + done,
                                (open.size - MAX_ITEMS).coerceAtLeast(0) + done,
                            ),
                        style = TextStyle(color = NotesWidgetColors.muted, fontSize = 12.sp),
                    )
                }
            }
            if (note.title.isBlank() && note.body.isBlank() && note.items.isEmpty()) {
                Text(
                    text = context.getString(R.string.notes_widget_untitled),
                    style = TextStyle(color = NotesWidgetColors.muted, fontSize = 13.sp),
                )
            }
        }
    }
}

private const val MAX_NOTES = 30
private const val MAX_ITEMS = 5
