package de.plmail.core.ui.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.Density
import kotlin.test.fail

/**
 * Every control on a screen is big enough to hit and says what it is.
 *
 * Two rules, checked over the whole semantics tree rather than node by node, because the way these
 * regress is that somebody adds *one* control — and a test that names the controls it checks never
 * covers the new one.
 *
 * **Labels.** A clickable node with neither text nor a content description is a control TalkBack
 * announces as "button" and nothing else. There is no density exception and no size exception; if a
 * thing can be tapped it can be described.
 *
 * **Size.** 48dp square is Material's minimum and this design system's own
 * `PlMailSpacing.touchTarget`, which says "never scaled below this, whatever the density".
 *
 * [rowsMayBe] is the one exception, and it has to be passed in deliberately rather than defaulted,
 * so that using it is a decision somebody made in the test rather than a rule that quietly applies
 * everywhere. It exempts **full-width rows** — a navigation row spanning the drawer, reached with a
 * thumb in a list that is scrolled rather than aimed at, where the neighbouring target is the same
 * kind of thing and a mis-tap is recoverable. It does not exempt buttons, icons or chips: those are
 * the isolated small controls the rule exists for. See `PlMailDensity.sidebarRowHeight`, which
 * argues the deviation at length.
 */
fun SemanticsNodeInteraction.assertEveryControlIsReachable(
    density: Density,
    rowsMayBe: androidx.compose.ui.unit.Dp? = null,
) {
    val minimum = 48f
    val rowMinimum = rowsMayBe?.value
    val problems = mutableListOf<String>()

    fun walk(node: SemanticsNode) {
        if (node.config.getOrNull(SemanticsActions.OnClick) != null) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
            val described = node.config.getOrNull(SemanticsProperties.ContentDescription)
            val label = listOfNotNull(text, described?.joinToString()).joinToString(" + ")

            val width = node.size.width / density.density
            val height = node.size.height / density.density

            if (label.isBlank()) {
                problems += "unlabelled control, ${width.toInt()}x${height.toInt()}dp"
            }

            // A row is full width when it is wide enough that no thumb could
            // miss it horizontally. Four times the minimum is the threshold: a
            // 192dp-wide control is a row, and nothing narrower is.
            val isFullWidthRow = rowMinimum != null && width >= minimum * 4

            val floor = if (isFullWidthRow) rowMinimum else minimum
            if (height < floor || (!isFullWidthRow && width < minimum)) {
                problems +=
                    "${width.toInt()}x${height.toInt()}dp is under ${floor.toInt()}dp :: $label"
            }
        }

        node.children.forEach(::walk)
    }

    walk(fetchSemanticsNode())

    if (problems.isNotEmpty()) {
        fail(
            "Controls that cannot be reached or named:\n" + problems.joinToString("\n") { "  $it" }
        )
    }
}
