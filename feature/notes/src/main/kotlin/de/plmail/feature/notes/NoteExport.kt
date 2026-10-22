package de.plmail.feature.notes

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import de.plmail.jmap.methods.NoteItem
import java.io.File

/**
 * A note leaving the app: as text through the share sheet, as a .txt the user saves where they
 * like, or as a picture of the card (drawn here, in its colour) through the share sheet — which is
 * also how it gets saved to the gallery or Files.
 */
internal object NoteExport {

    /** Title, a blank line, then the text — or the checklist as `[x]` / `[ ]` lines. */
    fun text(title: String, body: String, items: List<NoteItem>, checklist: Boolean): String =
        buildString {
            if (title.isNotBlank()) append(title.trim()).append("\n\n")
            if (checklist) {
                items
                    .filter { it.text.isNotBlank() }
                    .forEach {
                        append(if (it.done) "[x] " else "[ ] ").append(it.text).append('\n')
                    }
            } else {
                append(notePlainText(body))
            }
        }
            .trimEnd() + "\n"

    fun shareText(context: Context, title: String, text: String, chooserTitle: String) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
        runCatching {
            context.startActivity(
                Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun writeText(context: Context, uri: Uri, text: String) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }
    }

    fun sharePng(
        context: Context,
        title: String,
        body: String,
        items: List<NoteItem>,
        checklist: Boolean,
        color: Color,
        chooserTitle: String,
    ) {
        val bitmap = card(title, body, items, checklist, color)
        val dir = File(context.cacheDir, "notes").apply { mkdirs() }
        val name = title.ifBlank { "note" }.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.blobs", file)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        runCatching {
            context.startActivity(
                Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun card(
        title: String,
        body: String,
        items: List<NoteItem>,
        checklist: Boolean,
        color: Color,
    ): Bitmap {
        val width = 1080
        val pad = 64f
        val inner = (width - pad * 2).toInt()
        val ink = 0xFF202124.toInt()

        val titlePaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = ink
                textSize = 52f
                typeface = Typeface.DEFAULT_BOLD
            }
        val bodyPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = ink
                textSize = 38f
            }
        val text =
            if (checklist) {
                items
                    .filter { it.text.isNotBlank() }
                    .joinToString("\n") {
                        (if (it.done) "☑  " else "☐  ") + it.text
                    }
            } else {
                notePlainText(body)
            }

        val titleLayout =
            title
                .takeIf { it.isNotBlank() }
                ?.let {
                    StaticLayout.Builder.obtain(it, 0, it.length, titlePaint, inner)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .build()
                }
        val bodyLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, bodyPaint, inner)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(10f, 1f)
                .build()

        val gap = if (titleLayout != null) 32f else 0f
        val height =
            (pad * 2 + (titleLayout?.height ?: 0) + gap + bodyLayout.height)
                .toInt()
                .coerceAtLeast(320)

        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val fill =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = if (color == Color.Unspecified) 0xFFFFFFFF.toInt() else color.toArgb()
            }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 40f, 40f, fill)

        canvas.withTranslation(pad, pad) {
            titleLayout?.draw(this)
            withTranslation(0f, (titleLayout?.height ?: 0) + gap) { bodyLayout.draw(this) }
        }
        return bitmap
    }
}
