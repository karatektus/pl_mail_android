package de.plmail.feature.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * Note formatting: a small markdown subset kept in the body as plain text, the same one the web
 * renders, so either side edits what the other wrote — `**bold**`, `*italic*`, `__underline__`, a
 * `# ` line is a heading, a `- ` line is a bullet.
 */
private val INLINE =
    listOf(
        Regex("""\*\*(.+?)\*\*""") to SpanStyle(fontWeight = FontWeight.Bold),
        Regex("""__(.+?)__""") to SpanStyle(textDecoration = TextDecoration.Underline),
        Regex("""(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])""") to
            SpanStyle(fontStyle = FontStyle.Italic),
    )

private val HEADING = SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)

/** The body for a card: marks applied and hidden, bullets drawn as "•". */
internal fun noteAnnotated(body: String): AnnotatedString = buildAnnotatedString {
    body.lines().forEachIndexed { index, raw ->
        if (index > 0) append('\n')
        val heading = raw.startsWith("# ")
        val line =
            when {
                heading -> raw.removePrefix("# ")
                raw.startsWith("- ") -> "• " + raw.removePrefix("- ")
                else -> raw
            }
        val start = length
        appendInline(line)
        if (heading) addStyle(HEADING, start, length)
    }
}

/** [line] with its inline marks applied and the marks themselves left out. */
private fun AnnotatedString.Builder.appendInline(line: String) {
    data class Hit(val range: IntRange, val inner: String, val style: SpanStyle)

    var rest = line
    while (rest.isNotEmpty()) {
        val hit =
            INLINE.mapNotNull { (regex, style) ->
                    regex.find(rest)?.let { Hit(it.range, it.groupValues[1], style) }
                }
                .minByOrNull { it.range.first }
        if (hit == null) {
            append(rest)
            return
        }
        append(rest.substring(0, hit.range.first))
        val start = length
        appendInline(hit.inner)
        addStyle(hit.style, start, length)
        rest = rest.substring(hit.range.last + 1)
    }
}

/**
 * Styles the marks while typing without hiding them, so the cursor never jumps: the text is
 * unchanged character for character, only bold, italic, underline and heading are drawn, and the
 * marks themselves are faded.
 */
internal class NoteMarkupTransformation(private val markColor: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val styled = buildAnnotatedString {
            append(raw)
            var offset = 0
            raw.lines().forEach { line ->
                if (line.startsWith("# ")) {
                    addStyle(HEADING, offset, offset + line.length)
                    addStyle(SpanStyle(color = markColor), offset, offset + 2)
                }
                if (line.startsWith("- "))
                    addStyle(SpanStyle(color = markColor), offset, offset + 2)
                offset += line.length + 1
            }
            INLINE.forEach { (regex, style) ->
                regex.findAll(raw).forEach { match ->
                    val inner = match.groups[1]?.range ?: return@forEach
                    addStyle(style, inner.first, inner.last + 1)
                    addStyle(SpanStyle(color = markColor), match.range.first, inner.first)
                    addStyle(SpanStyle(color = markColor), inner.last + 1, match.range.last + 1)
                }
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

/**
 * A toolbar button: wraps the selection in [mark] (or unwraps it), or for `# ` and `- ` toggles the
 * prefix on every selected line.
 */
internal fun applyMark(value: TextFieldValue, mark: String): TextFieldValue {
    val text = value.text
    val start = minOf(value.selection.start, value.selection.end)
    val end = maxOf(value.selection.start, value.selection.end)

    if (mark == "# " || mark == "- ") {
        val lineStart = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
        val lineEndAt = text.indexOf('\n', end)
        val lineEnd = if (lineEndAt < 0) text.length else lineEndAt
        val lines = text.substring(lineStart, lineEnd).split('\n')
        val all = lines.all { it.startsWith(mark) }
        val changed =
            lines.joinToString("\n") {
                when {
                    all -> it.removePrefix(mark)
                    it.startsWith(mark) -> it
                    else -> mark + it
                }
            }
        val out = text.substring(0, lineStart) + changed + text.substring(lineEnd)
        return TextFieldValue(out, TextRange(lineStart, lineStart + changed.length))
    }

    val selected = text.substring(start, end)
    val wrapped =
        selected.length >= mark.length * 2 && selected.startsWith(mark) && selected.endsWith(mark)
    val replacement =
        if (wrapped) selected.substring(mark.length, selected.length - mark.length)
        else mark + selected + mark
    val out = text.substring(0, start) + replacement + text.substring(end)
    val selection =
        if (!wrapped && selected.isEmpty()) TextRange(start + mark.length)
        else TextRange(start, start + replacement.length)
    return TextFieldValue(out, selection)
}

/** The body as plain text with the formatting marks removed, for surfaces without styling. */
fun notePlainText(body: String): String = noteAnnotated(body).text
