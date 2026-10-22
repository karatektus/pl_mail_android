package de.plmail.feature.contacts

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A contact's photo, or an initials avatar when there is none.
 *
 * The bytes come from [ContactsViewModel.photoFor] — the authorized path — never from an image
 * loader pointed at the raw `photoUrl`, which would send no credential.
 */
@Composable
fun ContactPhoto(
    bytes: ByteArray?,
    initials: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val bitmap =
        remember(bytes) {
            if (bytes == null) null
            else
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                    .getOrNull()
        }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Text(
                text = initials,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** A small photo variant for editor previews, with a fixed 96dp default. */
@Composable
fun ContactPhotoPreview(
    bytes: ByteArray?,
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) = ContactPhoto(bytes = bytes, initials = initials, size = size, modifier = modifier)
