package de.plmail.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.plmail.core.data.Reminder
import de.plmail.core.data.ReminderListener
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows a calendar reminder the server pushed.
 *
 * The server decides when a reminder is due and what it says; this draws it. Nothing is scheduled
 * on the device, which is the point of doing it this way: a reminder set in the browser five
 * minutes ago reaches a phone that has not synced the calendar since yesterday.
 *
 * ## A channel of its own
 *
 * Mail has one channel per account so that each can be silenced or made louder on its own. A
 * reminder belongs to no mail account, and somebody who has muted a noisy mailbox has not asked to
 * miss the dentist. One channel, `HIGH`: mail is not a phone call and its channels say so, but a
 * reminder is the one notification this app sends that is about the next few minutes.
 *
 * ## One notification per reminder, replaced rather than repeated
 *
 * The id is the reminder's [Reminder.tag] — one alert, for one occurrence, of one event. A
 * redelivery lands on the same id and replaces what is there; two reminders about two appointments
 * have two tags and sit side by side.
 */
@Singleton
class ReminderNotifier
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val destinations: MailDestinations,
) : ReminderListener {

    private val manager = NotificationManagerCompat.from(context)

    override suspend fun onReminder(reminder: Reminder) {
        if (!isPermitted()) return

        manager.createNotificationChannel(
            NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_reminders),
                    NotificationManager.IMPORTANCE_HIGH,
                )
                .apply { description = context.getString(R.string.channel_reminders_description) }
        )

        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_mail)
                .setContentTitle(reminder.title)
                .setContentText(reminder.body.ifBlank { null })
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(destinations.openCalendar())
                .setAutoCancel(true)
                .build()

        // Posted with the tag as well as an id derived from it: the id alone
        // is a hash, and two tags that collide would otherwise replace each
        // other. The system keys a notification on the pair.
        manager.notifySafely(reminder.tag, reminder.tag.hashCode(), notification)
    }

    /**
     * Whether this app may post at all — gated on the version for the reason [MailNotifier] gives:
     * below API 33 the permission does not exist and asking for it answers "denied".
     */
    private fun isPermitted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            manager.areNotificationsEnabled()
        }

    private companion object {
        const val CHANNEL_ID = "reminders"
    }
}
