package de.plmail.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.core.database.ThreadEntity
import de.plmail.core.ui.ThreadRow
import de.plmail.jmap.methods.SearchSnippet

/**
 * A search result: the ordinary row, plus what matched.
 *
 * The row is [ThreadRow] unchanged, so a conversation looks the same wherever it is seen. The
 * snippet goes underneath rather than replacing the preview, because the two answer different
 * questions — the preview says what the conversation is, the snippet says why it came back.
 *
 * Threads are collapsed, so the matching message is often *not* the one the row shows. That is the
 * reason the snippet is worth the vertical space at all: without it a search for a phrase can
 * return a row containing no visible trace of it.
 */
@Composable
fun SearchResultRow(
    thread: ThreadEntity,
    snippet: SearchSnippet?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        ThreadRow(thread = thread, onClick = onClick)

        // Subject first when it matched: it is the shorter, more identifying
        // hit. Only one is shown -- two highlighted lines under every row turns
        // a result list into a wall.
        val highlighted =
            snippet?.subject?.takeIf(Highlight::hasMark)
                ?: snippet?.preview?.takeIf(Highlight::hasMark)

        if (highlighted != null) {
            Row(modifier = Modifier.padding(start = 72.dp, end = 16.dp, bottom = 12.dp)) {
                Text(
                    text =
                        Highlight.render(
                            highlighted,
                            SpanStyle(
                                fontWeight = FontWeight.Bold,
                                // The theme's own emphasis colour rather than a
                                // literal yellow: this has to stay legible in
                                // dark mode, where a highlighter block does not.
                                color = MaterialTheme.colorScheme.primary,
                            ),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
