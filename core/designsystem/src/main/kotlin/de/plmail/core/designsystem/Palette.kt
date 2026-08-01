package de.plmail.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * The raw colours, and the only file allowed to name one.
 *
 * **Nothing outside this file may write `Color(0x…)`.** Every screen reads a semantic token —
 * `surface`, `ink`, `accent` — and this is where those resolve. That indirection is the entire
 * point: a theme is a different set of values here, and swapping one must not touch a single
 * screen. The moment a call site hardcodes a colour, one theme is quietly wrong and nobody finds
 * out until someone switches to it.
 *
 * The palette is **warm**, not blue-grey. Every neutral below has more red than blue in it, which
 * is what makes an off-white read as paper rather than as an unpainted dialog, and a dark surface
 * read as ink rather than as a screenshot of a terminal. The difference is a few points per channel
 * and it is most of the reason the app does not look like a default.
 *
 * The accent is a single deep green, and it is deliberately rare: an active tab, a primary action,
 * a link. Colour earns attention by being scarce, so nothing here is a large filled area — the
 * `*Soft` variants exist for the few places that need a tinted background and are kept close enough
 * to the surface that they read as a shift rather than as a block.
 *
 * Every foreground/background pair used by the app clears WCAG AA against the surface it sits on,
 * in both schemes; `PaletteContrastTest` is what keeps that true rather than a claim in a comment.
 */
internal object Palette {

    val Light =
        PlMailColors(
            isDark = false,
            // Warm off-white. Not #FFF: a page-wide pure white next to white
            // cards has nothing to separate them, which is why the raised
            // surface below is the brighter of the two.
            surface = Color(0xFFFAF9F7),
            raised = Color(0xFFFFFFFF),
            sunken = Color(0xFFF1EFEA),
            hover = Color(0xFFF2EFEA),
            line = Color(0xFFE5E1D9),
            lineStrong = Color(0xFFD4CFC5),
            ink = Color(0xFF1B1917),
            inkSoft = Color(0xFF413D38),
            inkMuted = Color(0xFF6F6A63),
            inkFaint = Color(0xFF928C83),
            accent = Color(0xFF186A4A),
            accentHover = Color(0xFF115239),
            accentSoft = Color(0xFFE3EFE7),
            onAccent = Color(0xFFFFFFFF),
            fieldSurface = Color(0xFFFFFFFF),
            fieldLine = Color(0xFFD9D4CB),
            fieldPlaceholder = Color(0xFF7B756D),
            danger = Color(0xFFA62A22),
            dangerSoft = Color(0xFFF7E4E2),
            warning = Color(0xFF8A5A00),
            warningSoft = Color(0xFFF7EEDC),
            success = Color(0xFF2C6B3E),
            successSoft = Color(0xFFE4EFE7),
            info = Color(0xFF245C8F),
            infoSoft = Color(0xFFE2ECF7),
            inverseSurface = Color(0xFF2B2825),
            inverseInk = Color(0xFFF4F2EE),
            inverseAccent = Color(0xFF7FD3A8),
        )

    val Dark =
        PlMailColors(
            isDark = true,
            // A warm near-black rather than an inverted blue-grey. #171614 has
            // more red than blue in it for the same reason the light surface
            // does; a cold dark theme reads as an IDE.
            surface = Color(0xFF171614),
            raised = Color(0xFF201E1B),
            sunken = Color(0xFF111110),
            hover = Color(0xFF28251F),
            line = Color(0xFF332F2A),
            lineStrong = Color(0xFF474138),
            ink = Color(0xFFF4F2EE),
            inkSoft = Color(0xFFD5D1CA),
            inkMuted = Color(0xFFA19C94),
            inkFaint = Color(0xFF7A756D),
            accent = Color(0xFF63C495),
            accentHover = Color(0xFF87D6AF),
            accentSoft = Color(0xFF1B2A21),
            onAccent = Color(0xFF0C1A12),
            fieldSurface = Color(0xFF201E1B),
            fieldLine = Color(0xFF3A3630),
            fieldPlaceholder = Color(0xFF8D887F),
            danger = Color(0xFFF08981),
            dangerSoft = Color(0xFF33201E),
            warning = Color(0xFFDFA850),
            warningSoft = Color(0xFF2E2718),
            success = Color(0xFF7CC48D),
            successSoft = Color(0xFF1C2A1F),
            info = Color(0xFF84B4E8),
            infoSoft = Color(0xFF1B2530),
            inverseSurface = Color(0xFFF4F2EE),
            inverseInk = Color(0xFF1B1917),
            inverseAccent = Color(0xFF186A4A),
        )

    /**
     * The colours a letter avatar cycles through, indexed by a hash of the sender's **address**.
     *
     * Muted on purpose — a list of forty rows with forty saturated circles in it is a chart, not a
     * mailbox. They are palette values rather than tokens because there is no semantic name for
     * "the fourth avatar colour"; what matters is that they are here, where a theme can replace
     * them, rather than in the row that draws them.
     */
    /** White reads on every colour in [LightAvatars]; see PlMailColors.onAvatar. */
    val LightAvatarInk = Color(0xFFFFFFFF)

    /** And near-black on every colour in [DarkAvatars], which are bright by necessity. */
    val DarkAvatarInk = Color(0xFF16150F)

    val LightAvatars =
        listOf(
            Color(0xFF2E5F8A),
            Color(0xFF7A4E8C),
            Color(0xFF9A5B2E),
            Color(0xFF2C6B57),
            Color(0xFF8C4A55),
            Color(0xFF4F5E8F),
            Color(0xFF6B6320),
            Color(0xFF3F6B7A),
        )

    val DarkAvatars =
        listOf(
            Color(0xFF7FAEDA),
            Color(0xFFC49BD4),
            Color(0xFFE0A473),
            Color(0xFF77C0A6),
            Color(0xFFD9959D),
            Color(0xFF9BA7DC),
            Color(0xFFC0B563),
            Color(0xFF8DB9C7),
        )
}
