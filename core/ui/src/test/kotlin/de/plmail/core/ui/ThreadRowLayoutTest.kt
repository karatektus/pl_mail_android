package de.plmail.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import de.plmail.core.database.ThreadEntity
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.designsystem.PlMailThemeChoice
import java.time.LocalDate
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * That a list of these scrolls at one height.
 *
 * The screenshot suite guards what a row looks like; it cannot answer this, because the question is
 * about rows *next to each other* and every baseline is one row on its own. And the failure is
 * invisible in exactly the way that matters — a labelled conversation one or two pixels taller than
 * an unlabelled one is not something anybody can see on a row, only something that makes a
 * scrolling list feel wrong for a reason nobody can name.
 *
 * It has already happened once: the chips carried a dp of vertical padding of their own, so every
 * labelled row was three pixels taller than its neighbours at 420dpi, under a comment claiming the
 * padding had been chosen so it would not be. That is why this asserts equality across the four
 * cases rather than checking one against a recorded number — a number would have to be re-recorded
 * on every density change, and it is the *difference* that is the defect.
 */
@RunWith(RobolectricTestRunner::class)
// Native graphics, even though nothing is drawn. Robolectric's legacy mode
// measures text through a stubbed Paint -- every glyph the same width, line
// heights that are not the font's -- so a row measured under it is a row whose
// height has nothing to do with the device's. The whole subject here is a
// two-dp difference between two lines of text.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// sdk = 36 for the reason ThreadRowScreenshotTest gives: a library module has no
// targetSdk of its own and would otherwise ask Robolectric for an Android 37
// that does not exist.
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class ThreadRowLayoutTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `a row is the same height however many labels it carries`() {
        compose.setContent {
            PlMailTheme(theme = PlMailThemeChoice.LIGHT, reduceMotion = true) {
                Surface(modifier = Modifier.width(411.dp), color = PlMailTheme.colors.surface) {
                    Column {
                        CASES.forEach { case ->
                            // Tagged on a wrapper rather than on the row: the
                            // row clears its own semantics and replaces them
                            // with one spoken sentence, so a tag applied inside
                            // it is a tag that does not exist to the test.
                            Box(modifier = Modifier.testTag(case.tag)) {
                                ThreadRow(
                                    thread = thread(),
                                    onClick = {},
                                    labels = case.labels,
                                    hiddenLabels = case.hidden,
                                    today = TODAY,
                                )
                            }
                        }
                    }
                }
            }
        }

        val heights = CASES.associate {
            it.tag to compose.onNodeWithTag(it.tag).getUnclippedBoundsInRoot()
        }

        val plain = heights.getValue("none").height

        heights.forEach { (tag, bounds) ->
            assertEquals(
                "row height for $tag",
                plain.value.toDouble(),
                bounds.height.value.toDouble(),
                0.01,
            )
        }
    }

    /**
     * The four cases the chip cluster has to survive, which are the four a mailbox actually
     * contains: unlabelled mail, one label, the cap, and past the cap.
     */
    private data class Case(val tag: String, val labels: List<String>, val hidden: Int)

    private fun thread(): ThreadEntity =
        ThreadEntity(
            uid = "https://nas.local/13#t1",
            accountKey = "https://nas.local/13",
            threadId = "t1",
            latestReceivedAt =
                ZonedDateTime.parse("2020-01-15T09:05:00Z").toInstant().toEpochMilli(),
            subject = "The quarterly figures",
            participantsSummary = "Ada Lovelace",
            participantsAddress = "ada@example.com",
            snippet = "Attached is the full breakdown, and a note about next quarter.",
        )

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 1)

        val CASES =
            listOf(
                Case("none", emptyList(), 0),
                Case("one", listOf("Arbeit"), 0),
                Case("two", listOf("Arbeit", "Wohnung"), 0),
                // A name at the per-chip cap beside a counter, which is the
                // widest the cluster is allowed to get.
                Case("overflow", listOf("Wohnung/Nebenkosten"), 3),
            )
    }
}
