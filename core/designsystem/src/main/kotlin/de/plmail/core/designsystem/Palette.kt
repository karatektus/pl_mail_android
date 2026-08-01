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
     * Nord, and the one place this file's warmth rule is deliberately broken.
     *
     * Nord is somebody else's palette, chosen by name — a user who picks it has picked *that* blue
     * grey, and correcting it toward the warm neutrals the rest of this file argues for would
     * produce a theme that is no longer the thing they asked for. `PaletteContrastTest` scopes the
     * warmth assertion to the app's own two schemes for exactly this reason.
     *
     * Surface and the three ink steps are the values plMail's own stylesheet uses
     * (`[data-theme="nord"]` in `assets/styles/app.css`), so the phone and the web agree about what
     * Nord looks like. Everything the web derives through alpha compositing — the raised and sunken
     * surfaces, the hairline, the status colours — is resolved here to a flat colour instead,
     * because this product separates with a surface shift rather than a translucent overlay.
     *
     * Two of Nord's own Aurora colours had to be lightened to clear AA against Polar Night: the red
     * `#BF616A` lands at 3.0:1 and the frost blue `#81A1C1` at 4.6:1. Nord was designed for syntax
     * highlighting, where a keyword's colour is a hint and the text under it is still legible on
     * its own; an error message is not.
     */
    val Nord =
        PlMailColors(
            isDark = true,
            surface = Color(0xFF2E3440),
            raised = Color(0xFF3B4252),
            sunken = Color(0xFF272B35),
            hover = Color(0xFF3F4859),
            line = Color(0xFF434C5E),
            lineStrong = Color(0xFF4C566A),
            ink = Color(0xFFECEFF4),
            inkSoft = Color(0xFFE5E9F0),
            inkMuted = Color(0xFFD8DEE9),
            inkFaint = Color(0xFF8F9EB3),
            accent = Color(0xFF88C0D0),
            accentHover = Color(0xFFA3D3E0),
            accentSoft = Color(0xFF2A3A42),
            onAccent = Color(0xFF22303A),
            fieldSurface = Color(0xFF353C4A),
            fieldLine = Color(0xFF4C566A),
            fieldPlaceholder = Color(0xFF9AA7BB),
            danger = Color(0xFFDF8B93),
            dangerSoft = Color(0xFF3A2528),
            warning = Color(0xFFEBCB8B),
            warningSoft = Color(0xFF3A3324),
            success = Color(0xFFA3BE8C),
            successSoft = Color(0xFF2C3826),
            info = Color(0xFF9DBBD8),
            infoSoft = Color(0xFF26313C),
            inverseSurface = Color(0xFFECEFF4),
            inverseInk = Color(0xFF2E3440),
            inverseAccent = Color(0xFF3F6D86),
        )

    /**
     * Dusk — the violet twilight, matching `[data-theme="dusk"]` on the web.
     *
     * Cool like Nord and for the same reason, but where Nord is a documented palette this one is
     * plMail's own: surface and the ink steps come from the stylesheet, and the rest is derived to
     * sit with them. The accent is the violet the web's swatch advertises, which is bright enough
     * on this surface to be used as text without lightening.
     */
    val Dusk =
        PlMailColors(
            isDark = true,
            surface = Color(0xFF1E1B2E),
            raised = Color(0xFF272341),
            sunken = Color(0xFF171422),
            hover = Color(0xFF2E2947),
            line = Color(0xFF3A3358),
            lineStrong = Color(0xFF4A4270),
            ink = Color(0xFFEDE9FE),
            inkSoft = Color(0xFFDDD6FE),
            inkMuted = Color(0xFFC4B5FD),
            inkFaint = Color(0xFF9385C4),
            accent = Color(0xFFA78BFA),
            accentHover = Color(0xFFC4B5FD),
            accentSoft = Color(0xFF2E2450),
            onAccent = Color(0xFF1D1533),
            fieldSurface = Color(0xFF272341),
            fieldLine = Color(0xFF4A4270),
            fieldPlaceholder = Color(0xFFA495D4),
            danger = Color(0xFFF4899C),
            dangerSoft = Color(0xFF3A1F2B),
            warning = Color(0xFFF5C266),
            warningSoft = Color(0xFF382B1B),
            success = Color(0xFF86D9A5),
            successSoft = Color(0xFF1E3328),
            info = Color(0xFF9CBEF5),
            infoSoft = Color(0xFF22293C),
            inverseSurface = Color(0xFFEDE9FE),
            inverseInk = Color(0xFF1E1B2E),
            inverseAccent = Color(0xFF5B3FA8),
        )

    /**
     * Solar — Solarized Light's cream, and the theme AA argued with hardest.
     *
     * The surface is Solarized's `base3` and the web uses `base01 #586E75` as its ink. That is
     * 4.9:1 here, which passes the floor for body text and fails this file's own 7:1 rule for `ink`
     * — the rule exists because `ink` is what a subject line is set in and a mail list is read at
     * arm's length. So the ink steps are Solarized's slate carried two stops darker, which keeps
     * the hue and buys the headroom.
     *
     * The accent went the same way and further. **Every** Solarized accent fails AA on `base3`:
     * yellow `#B58900` is 3.0:1, orange `#CB4B16` is 4.3:1, blue `#268BD2` is 3.5:1 — the palette
     * was built for a terminal, where the accent sits on `base02` rather than on the page. The
     * ochre here is Solarized yellow darkened until it clears 4.5:1 against both the page and its
     * own tint.
     */
    val Solar =
        PlMailColors(
            isDark = false,
            surface = Color(0xFFFDF6E3),
            raised = Color(0xFFFFFDF4),
            sunken = Color(0xFFEEE8D5),
            hover = Color(0xFFF2EBD6),
            line = Color(0xFFE3DAC0),
            lineStrong = Color(0xFFD3C7A6),
            ink = Color(0xFF33454B),
            inkSoft = Color(0xFF4A5F66),
            inkMuted = Color(0xFF5A6E74),
            inkFaint = Color(0xFF7C8C8C),
            accent = Color(0xFF7E5F00),
            accentHover = Color(0xFF634A00),
            accentSoft = Color(0xFFF7EFD8),
            onAccent = Color(0xFFFFFFFF),
            fieldSurface = Color(0xFFFFFDF4),
            fieldLine = Color(0xFFDCD2B4),
            fieldPlaceholder = Color(0xFF6E7F84),
            danger = Color(0xFFA62A22),
            dangerSoft = Color(0xFFF7E2D8),
            warning = Color(0xFF8A5A00),
            warningSoft = Color(0xFFF5EAC9),
            success = Color(0xFF2C6B3E),
            successSoft = Color(0xFFE3EFDA),
            info = Color(0xFF245C8F),
            infoSoft = Color(0xFFDFEAF2),
            inverseSurface = Color(0xFF33454B),
            inverseInk = Color(0xFFFDF6E3),
            inverseAccent = Color(0xFFD9B85C),
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

    /**
     * The nine label colours, deep enough to be read on a light page.
     *
     * Chosen against the *worst* light background the app has, which is `sunken` — the chip's own
     * fill, and the darkest of the three light surfaces, so a colour that clears 4.5:1 there clears
     * it on the page and on a raised card too. Solar's `sunken` is the real bound: `#EEE8D5` is
     * darker than Light's `#F1EFEA`, so every value here is tuned for a cream page rather than a
     * white one.
     *
     * Deep rather than saturated on purpose. These are drawn as chip text and as a sidebar glyph,
     * never as a fill — see [PlMailLabelChip] for why a filled coloured pill was rejected — so what
     * matters is legibility at 13sp, and a bright Tailwind-500 is neither legible here nor quiet
     * enough to sit on a row whose only accent is the unread dot.
     *
     * Orange and amber are close together and that is honest rather than sloppy: at the depth AA
     * requires on a cream page there is not much room between them, and the web renders the same
     * two tokens with the same problem. A user who wants two obviously different labels has seven
     * other choices.
     */
    val LightLabels =
        mapOf(
            PlMailLabelColor.GRAY to Color(0xFF5F5A53),
            PlMailLabelColor.RED to Color(0xFFA32B24),
            PlMailLabelColor.ORANGE to Color(0xFFA34A15),
            PlMailLabelColor.AMBER to Color(0xFF7D5A05),
            PlMailLabelColor.GREEN to Color(0xFF2C6B3E),
            PlMailLabelColor.TEAL to Color(0xFF11615F),
            PlMailLabelColor.BLUE to Color(0xFF245C8F),
            PlMailLabelColor.VIOLET to Color(0xFF66399B),
            PlMailLabelColor.PINK to Color(0xFF9B2D5F),
        )

    /**
     * The same nine, bright enough to be read on a dark page.
     *
     * Tuned against **Nord's** surface rather than Dark's, because Nord's Polar Night `#2E3440` is
     * the lightest of the three dark pages by a wide margin — a ramp that clears AA on `#171614`
     * fails on Nord, and Nord is a theme somebody picks rather than an edge case. Everything here
     * therefore has more headroom than it strictly needs in Dark and Dusk, which is the right way
     * round: too bright is legible, too dim is not.
     */
    val DarkLabels =
        mapOf(
            PlMailLabelColor.GRAY to Color(0xFFB4AEA6),
            PlMailLabelColor.RED to Color(0xFFF0918A),
            PlMailLabelColor.ORANGE to Color(0xFFEFA771),
            PlMailLabelColor.AMBER to Color(0xFFDFC06B),
            PlMailLabelColor.GREEN to Color(0xFF8FD3A3),
            PlMailLabelColor.TEAL to Color(0xFF7ECCC7),
            PlMailLabelColor.BLUE to Color(0xFF97BEEE),
            PlMailLabelColor.VIOLET to Color(0xFFBFA6F4),
            PlMailLabelColor.PINK to Color(0xFFEE9CC2),
        )
}
