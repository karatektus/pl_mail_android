package de.plmail.core.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.plmail.core.database.ThreadEntity
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.core.designsystem.PlMailThemeChoice
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the row looks like, guarded on every build.
 *
 * Under Robolectric rather than on a device, and that is the point: a row's appearance is worth
 * checking on every commit, and a screenshot suite that needs an emulator is one that runs on none
 * of them. `verifyRoborazziDebug` compares against the checked-in images; `recordRoborazziDebug`
 * re-baselines them after a deliberate change.
 *
 * The cases are the ones with a decision behind them rather than a sweep of every field: unread
 * weight, the "(no subject)" fallback, a conversation with several messages, and the affordances
 * that share the right-hand column.
 *
 * **Every case is captured in both schemes.** A design system's whole promise is that one change
 * reaches every screen, and the way that promise breaks is a colour that was only ever looked at in
 * one of the two — a muted grey that vanishes on a near-black page, an accent that turns into a
 * smear. Rendering both is what makes the light-only mistake fail the build instead of shipping.
 *
 * The scheme is passed to `PlMailTheme` explicitly rather than set through Robolectric's `+night`
 * qualifier: the qualifier decides what `isSystemInDarkTheme()` returns, and this suite is checking
 * the palette rather than the platform's plumbing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// sdk = 36 explicitly. A library module declares no targetSdk -- correctly, since
// it has no say in what the app targets -- so it inherits compileSdk 37, and
// Robolectric has no Android 37 to emulate. 36 is also what :app actually
// targets, so this renders at the level the product runs at rather than one it
// has never been executed on.
@Config(sdk = [36], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi")
class ThreadRowScreenshotTest {

    @get:Rule val compose = createComposeRule()

    @Before
    fun pinTheClockAndZone() {
        // Otherwise the baseline is machine-dependent: the date column renders
        // in the default zone, so the same instant is 09:05 in London and 11:05
        // in Berlin, and the checked-in image only matches wherever it was
        // recorded.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @Test
    fun unread() {
        capture("unread", thread(isUnread = true))
    }

    @Test
    fun read() {
        capture("read", thread(isUnread = false))
    }

    /** A blank line reads as a rendering failure, and mail genuinely arrives without a subject. */
    @Test
    fun withoutASubject() {
        capture("no-subject", thread(subject = null))
    }

    @Test
    fun withAttachmentAndStar() {
        capture("attachment-and-star", thread(hasAttachment = true, isFlagged = true))
    }

    @Test
    fun aConversationWithSeveralMessages() {
        capture(
            "multi-message",
            thread(messageCount = 7, participants = "Ada Lovelace, Charles Babbage, Me"),
        )
    }

    /** Long values in every column at once, which is where a row's ellipsis rules show up. */
    @Test
    fun everythingTooLong() {
        capture(
            "overflow",
            thread(
                participants = "Ada Lovelace, Charles Babbage, Grace Hopper, Alan Turing",
                subject = "Re: Re: Fwd: the quarterly figures and everything that came with them",
                snippet =
                    "Attached is the full breakdown, plus the appendix nobody asked for, " +
                        "and a note about next quarter.",
            ),
        )
    }

    private fun capture(name: String, thread: ThreadEntity) {
        // The scheme is state inside one composition rather than two calls to
        // setContent: the rule allows exactly one per test, and recomposing is
        // in any case closer to what a user switching themes actually does.
        val scheme = mutableStateOf(PlMailThemeChoice.LIGHT)

        compose.setContent { Row(thread, scheme.value) }

        listOf(PlMailThemeChoice.LIGHT, PlMailThemeChoice.DARK).forEach { choice ->
            scheme.value = choice
            compose.waitForIdle()

            compose
                .onRoot()
                .captureRoboImage(
                    "src/test/screenshots/thread-row-$name-${choice.name.lowercase()}.png"
                )
        }
    }

    @Composable
    private fun Row(thread: ThreadEntity, scheme: PlMailThemeChoice) {
        // reduceMotion, because the alternative is asking a Robolectric
        // ContentResolver for a system setting it has no answer for -- and a
        // screenshot has nothing to animate anyway.
        PlMailTheme(theme = scheme, reduceMotion = true) {
            // A fixed width so the baseline does not move with the device
            // qualifiers; the row's own layout is what is under test.
            Surface(
                modifier = Modifier.width(411.dp),
                color = PlMailTheme.colors.surface,
            ) {
                ThreadRow(thread = thread, onClick = {})
            }
        }
    }

    private fun thread(
        participants: String = "Ada Lovelace",
        subject: String? = "The quarterly figures",
        snippet: String = "Attached is the full breakdown.",
        isUnread: Boolean = false,
        isFlagged: Boolean = false,
        hasAttachment: Boolean = false,
        messageCount: Int = 1,
    ): ThreadEntity =
        ThreadEntity(
            uid = "https://nas.local/13#t1",
            accountKey = "https://nas.local/13",
            threadId = "t1",
            // A date in a past year, so the column always takes the
            // with-year branch. A recent timestamp would render as a time
            // today, a weekday tomorrow and a date next week -- a baseline
            // that breaks overnight without anyone touching the code. Which
            // branch applies when is `RowFormattingTest`'s job; this is about
            // layout.
            latestReceivedAt =
                ZonedDateTime.parse("2020-01-15T09:05:00Z").toInstant().toEpochMilli(),
            subject = subject,
            participantsSummary = participants,
            participantsAddress = "ada@example.com",
            snippet = snippet,
            messageCount = messageCount,
            isUnread = isUnread,
            isFlagged = isFlagged,
            hasAttachment = hasAttachment,
        )
}
