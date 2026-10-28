package de.plmail.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.plmail.core.data.AvailableUpdate
import de.plmail.core.data.UpdateListener
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Says that a newer release is out, once per release.
 *
 * A channel of its own, `LOW`: the mail channels are per account and about mail, and somebody who
 * silenced updates has not silenced their inbox. One fixed id, so a newer release replaces the
 * notice for the one before it rather than stacking beside it.
 *
 * The tap opens the version section of the diagnostics screen, not the installer. Installing is a
 * thirty-megabyte download and a system prompt; it should start from a screen that shows the
 * release notes and a progress bar, not from the shade.
 */
@Singleton
class UpdateNotifier
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val destinations: MailDestinations,
) : UpdateListener {

    private val manager = NotificationManagerCompat.from(context)

    override suspend fun onUpdateAvailable(update: AvailableUpdate) {
        if (!manager.areNotificationsEnabled()) return

        manager.createNotificationChannel(
            NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_updates),
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply { description = context.getString(R.string.channel_updates_description) }
        )

        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_mail)
                .setContentTitle(
                    context.getString(R.string.update_available_title, update.versionName)
                )
                .setContentText(context.getString(R.string.update_available_text))
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setContentIntent(destinations.openUpdate())
                .setAutoCancel(true)
                .build()

        manager.notifySafely(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "updates"
        const val NOTIFICATION_ID = 0x757064
    }
}
