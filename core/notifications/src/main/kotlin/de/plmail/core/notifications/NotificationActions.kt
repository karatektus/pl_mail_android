package de.plmail.core.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import de.plmail.core.data.ActionTarget
import de.plmail.core.data.ApplicationScope
import de.plmail.core.data.MailAction
import de.plmail.core.data.MailActions
import de.plmail.core.data.NewMessage
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Archive and mark-read, straight from the shade.
 *
 * A broadcast rather than an activity, because the point of these is that the phone stays in the
 * user's pocket: an action that unlocks the device and opens the app to archive one message is one
 * nobody uses twice. Reply used to be the exception, on the grounds that it needs a keyboard;
 * `RemoteInput` puts the keyboard in the shade, so it is a broadcast too now — see
 * [InlineReplyReceiver], which is separate from this one because a send is not fire-and-forget and
 * cannot dismiss the notification before it knows how it went.
 *
 * The receiver is **not exported**. Nothing outside this app has any business archiving somebody's
 * mail, and a `PendingIntent` handed to the system carries the right to fire it without the target
 * needing to be public.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var actions: MailActions

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val accountKey = intent.getStringExtra(EXTRA_ACCOUNT) ?: return
        val threadId = intent.getStringExtra(EXTRA_THREAD)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION, 0)

        // Dismissed first and unconditionally. The mail leaving the shade is the
        // feedback for the tap, and making it wait for a NAS on the end of a
        // domestic uplink means tapping Archive appears to do nothing for
        // several seconds -- long enough to tap it again.
        val manager = NotificationManagerCompat.from(context)
        if (notificationId != 0) manager.cancel(notificationId)
        tidySummary(context, manager, accountKey)

        val action =
            when (intent.action) {
                ACTION_ARCHIVE -> MailAction.Archive
                ACTION_MARK_READ -> MailAction.MarkRead(seen = true)
                // A plain dismissal. The summary tidy-up above was the whole
                // point of hearing about it.
                else -> return
            }

        if (threadId == null) return

        // The application scope rather than `goAsync`: a broadcast receiver has
        // roughly ten seconds, and the right answer to a slow server is to let
        // the work outlive the broadcast rather than to race a deadline that
        // kills the process when it is missed.
        scope.launch {
            runCatching { actions.apply(action, listOf(ActionTarget(accountKey, threadId))) }
                // Logged rather than surfaced, and that is a real gap rather
                // than a decision to be comfortable with: there is no snackbar
                // to show from a notification action, so a rejection is
                // currently invisible. The next sync corrects the list, which
                // means the mail comes back rather than being lost.
                .onFailure { Log.w(TAG, "Notification action failed", it) }
        }
    }

    private companion object {
        const val TAG = "plMail.Notify"
    }
}

/**
 * Cancels the account's summary once nothing is left under it.
 *
 * Android removes an auto-cancelled summary when its last child goes *only* in some versions and
 * only for some paths, and the failure is the one everybody has seen: an empty "3 new messages"
 * heading sitting in the shade with nothing beneath it, which cannot be dismissed by swiping a
 * child because there are none. Counting what is left is cheap and removes the whole class of it.
 *
 * Shared with the inline reply, which takes a child away for the same reason archive does: the
 * conversation has been dealt with.
 */
internal fun tidySummary(
    context: Context,
    manager: NotificationManagerCompat,
    accountKey: String,
) {
    val group = groupKey(accountKey)

    val remaining = runCatching {
        manager.activeNotifications.count {
            NotificationCompat.getGroup(it.notification) == group &&
                !NotificationCompat.isGroupSummary(it.notification)
        }
    }
        // One rather than zero: a platform that will not answer must not be
        // read as "nothing is left", which would cancel a summary that still
        // has children under it.
        .getOrDefault(1)

    if (remaining == 0) manager.cancel(summaryId(accountKey))
}

/** Builds the intents [NotificationActionReceiver] answers. */
internal object NotificationActions {

    fun archive(context: Context, message: NewMessage, notificationId: Int): PendingIntent =
        pending(context, ACTION_ARCHIVE, message.accountKey, message.threadId, notificationId)

    fun markRead(context: Context, message: NewMessage, notificationId: Int): PendingIntent =
        pending(context, ACTION_MARK_READ, message.accountKey, message.threadId, notificationId)

    /** Fired by the system when the user swipes the notification away. */
    fun dismissed(context: Context, accountKey: String, notificationId: Int): PendingIntent =
        pending(context, ACTION_DISMISSED, accountKey, threadId = null, notificationId)

    private fun pending(
        context: Context,
        action: String,
        accountKey: String,
        threadId: String?,
        notificationId: Int,
    ): PendingIntent {
        val intent =
            Intent(context, NotificationActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_ACCOUNT, accountKey)
                putExtra(EXTRA_THREAD, threadId)
                putExtra(EXTRA_NOTIFICATION, notificationId)
            }

        return PendingIntent.getBroadcast(
            context,
            // Unique per action *and* per notification. Request codes are how
            // the system tells two PendingIntents apart -- the extras are not
            // part of the comparison -- so a shared code means the second
            // notification's Archive silently archives the first one's
            // conversation. This is the single most common way notification
            // actions go wrong and it is invisible until there are two of them.
            (action + notificationId).hashCode(),
            intent,
            // IMMUTABLE because nothing outside this app should be able to fill
            // in which conversation gets archived, and required since API 31 in
            // any case.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

private const val ACTION_ARCHIVE = "de.plmail.notifications.ARCHIVE"
private const val ACTION_MARK_READ = "de.plmail.notifications.MARK_READ"
private const val ACTION_DISMISSED = "de.plmail.notifications.DISMISSED"

private const val EXTRA_ACCOUNT = "account"
private const val EXTRA_THREAD = "thread"
private const val EXTRA_NOTIFICATION = "notification"
