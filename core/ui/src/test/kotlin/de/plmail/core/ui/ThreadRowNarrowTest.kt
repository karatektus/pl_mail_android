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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The row at 320dp, which is where German and the chip budget meet.
 *
 * Every other test and every screenshot baseline in this module is taken at 411dp, where the chip
 * cluster's 160dp cap never bites: the text column is 243dp and the preview still gets 83dp. At
 * 320dp — the narrowest width Android phones are still built at — the same 160dp left the preview
 * about three characters. Seen on the device in German rather than in a baseline:
 *
 *     Substitution, and the paper
 *     On Aug 1,…              [Steuer] [Wohnung]
 *
 * which is exactly the defect that moving the chips *behind* the snippet was supposed to have
 * fixed, arriving again from a direction nobody had looked at. A cap on the chips cannot express
 * it, because the number being capped is not the number that matters — what matters is what is
 * left.
 *
 * The budget is asserted as arithmetic rather than by measuring the rendered preview, and that is
 * forced rather than lazy: the row clears its own semantics and replaces them with one spoken
 * sentence, so nothing inside it can carry a test tag and no test can ask how wide the preview came
 * out. The height check below is what the rendered row can still be held to.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-normal-long-notround-any-320dpi")
class ThreadRowNarrowTest {

    @get:Rule val compose = createComposeRule()

    /**
     * The floor, at the three widths that decide it.
     *
     * Stated in terms of the *line* the chips share with the preview, which is the row's width less
     * the avatar, the gutters and the date column — about 243dp on a 411dp phone and about 188dp on
     * a 320dp one. The third case is a line narrower than the floor itself, where the honest answer
     * is that chips get nothing rather than a sliver.
     */
    @Test
    fun `the chips give way before the preview does`() {
        // Wide enough that the cap is what binds, not the floor. This is the
        // case every existing baseline was recorded under.
        assertEquals(160.dp, chipBudget(320.dp))

        // The line inside a 320dp phone once the avatar, gutters and date column
        // have taken theirs. 160dp of chips here is what left three characters
        // of preview; 84dp is one readable chip and a preview that is still a
        // sentence.
        assertEquals(84.dp, chipBudget(188.dp))

        // Narrower than the floor itself: nothing for chips, everything for the
        // preview. Never negative, which would be a measure constraint Compose
        // throws on rather than a narrow row.
        assertEquals(0.dp, chipBudget(80.dp))
    }

    /**
     * How many chips a row may draw, which is a different question from how wide they may be.
     *
     * Two chips inside an 84dp budget is two chips at forty dp — four characters and an ellipsis
     * each, which this module's own notes call a smudge rather than a label. So a narrow pane drops
     * to one slot, and `ROW_LABEL_LIMIT`'s existing rule does the rest: the counter takes the slot
     * as soon as there is more than one label, so the row says "+2" rather than picking a name.
     */
    @Test
    fun `a narrow pane gets one chip slot and a phone-width one gets two`() {
        assertEquals(1, rowLabelSlots(320.dp))
        assertEquals(1, rowLabelSlots(360.dp))
        assertEquals(2, rowLabelSlots(411.dp))

        // The threshold itself, so moving it is a deliberate act rather than a
        // side effect. It is not the compact/medium breakpoint: that is 600dp,
        // and every phone in portrait is under it including the 411dp one where
        // two chips are perfectly comfortable.
        assertTrue(rowLabelSlots(400.dp) == 2)
    }

    /**
     * And the height rule still holds at this width.
     *
     * The original defect — labelled rows a couple of dp taller than unlabelled ones — was found at
     * 411dp. A narrow screen is where a chip is most likely to push the line into wrapping, so the
     * same assertion is worth making again here rather than assuming it travels.
     */
    @Test
    fun `a narrow row is the same height labelled or not`() {
        compose.setContent {
            PlMailTheme(theme = PlMailThemeChoice.LIGHT, reduceMotion = true) {
                Surface(modifier = Modifier.width(320.dp), color = PlMailTheme.colors.surface) {
                    Column {
                        Box(modifier = Modifier.testTag("bare")) {
                            ThreadRow(thread = thread(), onClick = {}, today = TODAY)
                        }

                        Box(modifier = Modifier.testTag("labelled")) {
                            ThreadRow(
                                thread = thread(),
                                onClick = {},
                                labels = listOf(RowChip("Wohnung/Nebenkosten")),
                                hiddenLabels = 2,
                                today = TODAY,
                            )
                        }
                    }
                }
            }
        }

        val bare = compose.onNodeWithTag("bare").getUnclippedBoundsInRoot().height
        val labelled = compose.onNodeWithTag("labelled").getUnclippedBoundsInRoot().height

        assertEquals("row height at 320dp", bare.value.toDouble(), labelled.value.toDouble(), 0.01)
    }

    private fun thread(): ThreadEntity =
        ThreadEntity(
            uid = "https://nas.local/13#t1",
            accountKey = "https://nas.local/13",
            threadId = "t1",
            latestReceivedAt =
                ZonedDateTime.parse("2020-01-15T09:05:00Z").toInstant().toEpochMilli(),
            subject = "Zweitkonto Terminbestätigung",
            participantsSummary = "Zweiter Absender",
            participantsAddress = "absender@second.test",
            snippet = "Sehr geehrte Mieterinnen und Mieter, anbei die Abrechnung.",
        )

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 8, 1)
    }
}
