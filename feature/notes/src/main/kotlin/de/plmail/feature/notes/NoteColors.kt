package de.plmail.feature.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** The server's colour names, in palette order. `null` is the default card. */
internal val NoteColorNames =
    listOf("red", "orange", "yellow", "green", "teal", "blue", "purple", "pink", "brown", "gray")

private val Light =
    mapOf(
        "red" to 0xFFF28B82,
        "orange" to 0xFFFBBC04,
        "yellow" to 0xFFFFF475,
        "green" to 0xFFCCFF90,
        "teal" to 0xFFA7FFEB,
        "blue" to 0xFFCBF0F8,
        "purple" to 0xFFD7AEFB,
        "pink" to 0xFFFDCFE8,
        "brown" to 0xFFE6C9A8,
        "gray" to 0xFFE8EAED,
    )

private val Dark =
    mapOf(
        "red" to 0xFF5C2B29,
        "orange" to 0xFF614A19,
        "yellow" to 0xFF635D19,
        "green" to 0xFF345920,
        "teal" to 0xFF16504B,
        "blue" to 0xFF2D555E,
        "purple" to 0xFF42275E,
        "pink" to 0xFF5B2245,
        "brown" to 0xFF442F19,
        "gray" to 0xFF3C3F43,
    )

/** The paint for a note's colour name, in light or dark. An unknown name is the default card. */
@Composable
internal fun noteContainer(name: String?): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val argb = name?.let { (if (dark) Dark else Light)[it] }
    return argb?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceContainer
}

/** Text that reads on [noteContainer]: the palette is pastel in light and deep in dark. */
@Composable
internal fun noteContent(name: String?): Color {
    if (name == null || name !in Light) return MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) Color(0xFFE8EAED) else Color(0xFF202124)
}

/**
 * A note colour as ARGB, for surfaces that are not Compose themes (the home-screen widget). Null
 * for no colour or an unknown name.
 */
fun noteColorArgb(name: String?, dark: Boolean): Long? = name?.let {
    (if (dark) Dark else Light)[it]
}
