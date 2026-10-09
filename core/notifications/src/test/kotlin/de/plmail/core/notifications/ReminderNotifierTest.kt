package de.plmail.core.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import de.plmail.core.data.Reminder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A pushed calendar reminder, asserted against what the platform was actually handed.
 *
 * Robolectric for the reason `InlineReplyNotificationTest` gives. The two things pinned here are
 * the two a reminder gets wrong quietly: its words not reaching the shade, and a second delivery of
 * the same reminder stacking beside the first instead of replacing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderNotifierTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private object Destinations : MailDestinations {
        override fun openConversation(accountKey: String, threadId: String): PendingIntent =
            broadcast("open")

        override fun reply(accountKey: String, emailId: String): PendingIntent = broadcast("reply")

        override fun openCalendar(): PendingIntent = broadcast("calendar")

        private fun broadcast(key: String): PendingIntent {
            val context = ApplicationProvider.getApplicationContext<Context>()

            return PendingIntent.getBroadcast(
                context,
                key.hashCode(),
                Intent(key),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    private val notifier = ReminderNotifier(context, Destinations)

    private val dentist =
        Reminder(
            title = "Dentist — Praxis Dr. Ilg",
            body = "in 15 minutes",
            tag = "412/display-15m/2026-10-16T09:00:00Z",
        )

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    /**
     * Granted for every test but the one about its absence: on API 33 and above nothing is posted
     * without it, and a fresh Robolectric application holds no runtime permission.
     */
    @Before
    fun allowNotifications() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `nothing is posted when the user has not allowed notifications`() = runTest {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifier.onReminder(dentist)

        assertEquals(0, posted().size)
    }

    @Test
    fun `a reminder is posted with its words, on its own channel, opening the calendar`() =
        runTest {
            notifier.onReminder(dentist)

            val notification = posted().single()

            assertEquals(dentist.title, NotificationCompat.getContentTitle(notification).toString())
            assertEquals(dentist.body, NotificationCompat.getContentText(notification).toString())
            assertEquals("reminders", notification.channelId)
            assertEquals(Notification.CATEGORY_REMINDER, notification.category)
            assertEquals(
                "calendar",
                shadowOf(assertNotNull(notification.contentIntent)).savedIntent.action,
            )
            assertNotNull(manager.getNotificationChannel("reminders"))
        }

    /**
     * The server may deliver one reminder twice — a retry, or a second transport — and the tag is
     * what says they are the same one.
     */
    @Test
    fun `the same reminder delivered twice is one notification`() = runTest {
        notifier.onReminder(dentist)
        notifier.onReminder(dentist)

        assertEquals(1, posted().size)
    }

    @Test
    fun `two reminders about two appointments sit side by side`() = runTest {
        notifier.onReminder(dentist)
        notifier.onReminder(
            dentist.copy(title = "Stand-up", tag = "9/display-5m/2026-10-16T09:30:00Z")
        )

        assertEquals(2, posted().size)
    }
}
