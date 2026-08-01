package de.plmail.feature.mail.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.core.database.AttachmentEntity
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.feature.mail.R
import java.util.Locale

/**
 * What one message carries, under its body.
 *
 * Rows rather than Gmail's thumbnail chips, and the reason is this product's mail rather than
 * taste: a self-hosted mailbox is full of invoices, scans and `.eml` forwards, and a chip that
 * shows a preview of a PDF shows the same grey rectangle for all of them while hiding the filename,
 * which is the one thing that tells them apart. A row can show the whole name and the size.
 *
 * The whole row opens the attachment and the trailing button saves it — the two verbs Gmail has,
 * separated so that "save to my files" is never one mis-tap away from launching a document viewer.
 */
@Composable
internal fun Attachments(
    attachments: List<AttachmentEntity>,
    busy: Set<String>,
    onOpen: (AttachmentEntity) -> Unit,
    onSave: (AttachmentEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth().padding(PlMailTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
    ) {
        attachments.forEach { attachment ->
            AttachmentRow(
                attachment = attachment,
                isBusy = attachment.uid in busy,
                onOpen = { onOpen(attachment) },
                onSave = { onSave(attachment) },
            )
        }
    }
}

@Composable
private fun AttachmentRow(
    attachment: AttachmentEntity,
    isBusy: Boolean,
    onOpen: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = PlMailTheme.colors
    val spacing = PlMailTheme.spacing

    Row(
        modifier =
            Modifier.fillMaxWidth()
                // A hairline and a shifted surface, never a shadow: an
                // attachment is a thing on the page rather than a card floating
                // above it, and this app has no elevation model.
                .clip(RoundedCornerShape(PlMailTheme.radii.control))
                .background(colors.raised)
                .border(
                    width = 1.dp,
                    color = colors.line,
                    shape = RoundedCornerShape(PlMailTheme.radii.control),
                )
                .clickable(onClick = onOpen)
                .heightIn(min = spacing.touchTarget)
                .padding(start = spacing.medium, top = spacing.small, bottom = spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Box(modifier = Modifier.size(ICON_SLOT), contentAlignment = Alignment.Center) {
            if (isBusy) {
                CircularProgressIndicator(
                    color = colors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(SPINNER),
                )
            } else {
                Icon(
                    imageVector = iconFor(attachment.type),
                    contentDescription = null,
                    tint = colors.inkMuted,
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name ?: stringResource(R.string.attachment_unnamed),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.ink,
                // Two lines, and the middle elided. A long filename is
                // `Rechnung_2025-11_Stadtwerke_Muenchen_Kundennummer_881204.pdf`,
                // and cutting the *end* off that removes the extension and the
                // number, which is everything that distinguishes it from the
                // other eleven.
                maxLines = 2,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(
                text = describe(attachment),
                style = MaterialTheme.typography.labelMedium,
                color = colors.inkFaint,
                maxLines = 1,
            )
        }

        IconButton(onClick = onSave) {
            Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription =
                    stringResource(
                        R.string.attachment_save_named,
                        attachment.name ?: stringResource(R.string.attachment_unnamed),
                    ),
                tint = colors.inkSoft,
            )
        }
    }
}

/**
 * "1.4 MB", or "PDF · 1.4 MB" when the filename does not already say what it is.
 *
 * The type is suppressed whenever the name carries an extension, and that is not tidiness. The
 * first version printed the subtype unconditionally and put "PLAIN · 64 kB" under a file called
 * `Rechnung….txt` — a word the user has to translate back into "text file" sitting next to one that
 * already said it. Where there is no extension the type is the only thing there is, so it stays.
 */
internal fun describe(attachment: AttachmentEntity): String {
    val size = formatSize(attachment.size)
    val kind = if (hasExtension(attachment.name)) null else shortType(attachment.type)

    return if (kind == null) size else "$kind · $size"
}

/**
 * Whether the filename already says what kind of file this is.
 *
 * Not `contains('.')`. Real attachments are named `Rechnung_2025.11` and `Scan 12.03.2026`, and
 * reading the tail of either as an extension would suppress the type on exactly the files that had
 * nothing else to say. So: short, and containing at least one letter, which `11` and `2026` do not.
 */
internal fun hasExtension(name: String?): Boolean {
    val tail = name?.substringAfterLast('.', "").orEmpty()

    return tail.length in 1..MAX_EXTENSION &&
        tail.all { it.isLetterOrDigit() } &&
        tail.any { it.isLetter() }
}

internal fun shortType(type: String): String? {
    val subtype = type.substringAfter('/', "").substringBefore(';').trim()

    return when {
        subtype.isBlank() -> null
        // The default type, and this app's own fallback for a part that arrived
        // without one. "OCTET-STREAM" is a label meaning "we do not know", which
        // is worth nothing to the reader and takes the width the size needs.
        subtype == "octet-stream" -> null
        // The `vnd.` and `x-` families are vendor and experimental prefixes;
        // what follows them is usually the readable part, and where it is not
        // the whole string is a URN nobody can read at this size.
        subtype.length > MAX_TYPE -> null
        else -> subtype.removePrefix("vnd.").removePrefix("x-").uppercase(Locale.ROOT)
    }
}

/**
 * Sizes in the units the OS uses, so "1.4 MB" here means the same as "1.4 MB" in the file picker
 * the user saves it into. Powers of ten rather than 1024 for exactly that reason.
 */
internal fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    if (bytes < 1000) return "$bytes B"

    val units = listOf("kB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1000
    var unit = 0

    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }

    // One decimal below ten, none above: "9.4 MB" is useful, "9.4 kB" is
    // useful, "412.7 MB" is three characters of noise.
    return if (value < 10) String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
    else String.format(Locale.getDefault(), "%.0f %s", value, units[unit])
}

/**
 * A glyph per broad family.
 *
 * Four families and a fallback, rather than a lookup table per extension. The icon is there to make
 * the list scannable, not to identify the file — the name does that — and a table of forty mappings
 * is forty chances to show a spreadsheet icon on a photograph.
 */
private fun iconFor(type: String): ImageVector =
    when {
        type.startsWith("image/") -> Icons.Outlined.Image
        type == "application/pdf" -> Icons.Outlined.PictureAsPdf
        type.contains("spreadsheet") || type.contains("csv") || type.contains("excel") ->
            Icons.Outlined.TableChart
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }

private val ICON_SLOT = 24.dp
private val SPINNER = 18.dp

/** Beyond this a subtype is a vendor string rather than a name, and says less than the filename. */
private const val MAX_TYPE = 12

/** `.jpeg`, `.xlsx`, `.tar.gz` — four is the longest anybody actually uses. */
private const val MAX_EXTENSION = 4
