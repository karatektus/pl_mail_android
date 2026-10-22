package de.plmail.widget.notes

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import de.plmail.MainActivity
import de.plmail.feature.notes.NoteSeed

/**
 * The intents behind the notes widget's taps, and reading them back in [MainActivity].
 *
 * Each carries a distinct data URI as well as its extras: PendingIntents that differ only in extras
 * are the same PendingIntent to Android, so without it every card would open the first note. The
 * scheme is not `plmail:`, which [MainActivity] reads as a pairing link.
 */
object NotesWidgetIntents {
    private const val SCHEME = "plmail-notes"
    private const val EXTRA_ACTION = "de.plmail.widget.notes.ACTION"
    private const val EXTRA_ID = "de.plmail.widget.notes.ID"
    private const val OPEN = "open"
    private const val NEW_NOTE = "new"
    private const val NEW_LIST = "list"

    fun open(context: Context, id: String): Intent = build(context, OPEN, id)

    fun newNote(context: Context): Intent = build(context, NEW_NOTE, null)

    fun newList(context: Context): Intent = build(context, NEW_LIST, null)

    private fun build(context: Context, action: String, id: String?): Intent =
        Intent(context, MainActivity::class.java)
            .setData("$SCHEME://$action/${id.orEmpty()}".toUri())
            .putExtra(EXTRA_ACTION, action)
            .putExtra(EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** What a widget tap asks the notes screen for, or null for any other intent. */
    fun read(intent: Intent?): NoteSeed? =
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            OPEN -> intent.getStringExtra(EXTRA_ID)?.let { NoteSeed(openId = it) }
            NEW_NOTE -> NoteSeed()
            NEW_LIST -> NoteSeed(checklist = true)
            else -> null
        }
}
