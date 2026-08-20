package de.plmail.core.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import de.plmail.core.data.NewMailListener
import de.plmail.core.data.NewMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts arriving mail in the notification shade.
 *
 * **One notification per conversation, plus one summary per account.** Android's own grouping rules
 * make that the only shape that behaves: below four notifications in a group the system shows the
 * children and hides the summary, and at four or more it collapses them behind the summary. An app
 * that posts only a summary therefore shows nothing at all when two messages arrive, and one that
 * posts only children loses the ability to say "and six more" — the two halves are not
 * alternatives.
 *
 * The summary's real jobs are making the group collapse at all and carrying the count that becomes
 * the launcher badge. Its `InboxStyle` is a third one, and an honest note about it: on this
 * platform version the shade builds the collapsed list from the *children* and never draws the
 * summary's own style — checked on the device rather than assumed. It is kept because the surfaces
 * that do use it are the ones nobody tests by hand, and because it costs one list.
 *
 * The count it carries cannot be read back from a posted notification, so it is recomputed from the
 * batch just handed over plus the ids of whatever children are still on screen. That is what
 * [summaryContent] does, and the arithmetic is subtler than it looks — see its own note.
 *
 * Nothing here decides *what* is new. That is [de.plmail.core.data.DeltaSync]'s judgement, and it
 * is deliberately made where the cache is, not here.
 *
 * What one conversation's row looks like is [conversationNotification]'s, and it is a top-level
 * function rather than a method because the inline reply has to be able to rebuild the identical
 * row without going through the sync — see [ReplyNotifications].
 */
@Singleton
class MailNotifier
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val channels: MailChannels,
    private val destinations: MailDestinations,
) : NewMailListener {

    private val manager = NotificationManagerCompat.from(context)

    override suspend fun onNewMail(messages: List<NewMessage>) {
        if (messages.isEmpty()) return

        // Checked rather than assumed, because posting without it throws nothing
        // and shows nothing: NotificationManagerCompat swallows the refusal, so
        // a missing runtime permission looks exactly like mail that never
        // arrived. Since API 33 this is a permission the user can simply say no
        // to, and this product's users have to be able to work out why their
        // phone is quiet.
        if (!isPermitted()) return

        messages.groupBy { it.accountKey }.forEach { (accountKey, forAccount) -> post(forAccount) }
    }

    private fun post(messages: List<NewMessage>) {
        val first = messages.first()
        val channelId = channels.channelFor(first.accountKey, first.accountName)
        val group = groupKey(first.accountKey)

        messages.forEach { message ->
            manager.notifySafely(
                notificationId(message),
                conversationNotification(context, message, channelId, group, destinations),
            )
        }

        manager.notifySafely(
            summaryId(first.accountKey),
            summary(first.accountKey, first.accountName, messages, channelId, group),
        )
    }

    /**
     * The account's summary, listing what is under it.
     *
     * The lines are the arriving batch first and then whatever children are still posted, which is
     * the only way to include mail from an earlier arrival that the user has not yet dealt with.
     * `activeNotifications` needs no permission and is scoped to this app, so reading it back is
     * not a privacy question — it is simply the store this app already has.
     */
    private fun summary(
        accountKey: String,
        accountName: String,
        arriving: List<NewMessage>,
        channelId: String,
        group: String,
    ): Notification {
        val content =
            summaryContent(
                arriving = arriving,
                alreadyShowing = childrenOf(group),
                maxLines = MAX_SUMMARY_LINES,
            )
        val total = content.total

        val style =
            NotificationCompat.InboxStyle()
                .setBigContentTitle(
                    context.resources.getQuantityString(R.plurals.new_messages, total, total)
                )
                .setSummaryText(accountName)

        content.lines.forEach { style.addLine(it) }

        if (content.overflow > 0) {
            style.addLine(
                context.resources.getQuantityString(
                    R.plurals.and_more,
                    content.overflow,
                    content.overflow,
                )
            )
        }

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_mail)
            .setStyle(style)
            .setContentTitle(
                context.resources.getQuantityString(R.plurals.new_messages, total, total)
            )
            .setContentText(accountName)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setGroup(group)
            .setGroupSummary(true)
            .setAutoCancel(true)
            // Deliberately no setNumber. It looked like the right way to feed
            // the launcher badge and it is a number that goes stale: archiving
            // one conversation from the shade cancels its notification and
            // cannot rebuild this one, so the badge would keep claiming three
            // while two rows remained. Left unset, the platform counts the
            // posted children itself and is right by construction.
            .setContentIntent(destinations.openConversation(accountKey, arriving.first().threadId))
            .build()
    }

    /**
     * The ids of this app's own posted children in one group.
     *
     * Returns nothing rather than failing on anything the platform will not answer: this only ever
     * improves a count, and a summary that says "3 new" when there are four is a far smaller
     * problem than a crash inside a broadcast.
     */
    @SuppressLint("MissingPermission")
    private fun childrenOf(group: String): List<Int> = runCatching {
        manager.activeNotifications
            .filter {
                NotificationCompat.getGroup(it.notification) == group &&
                    !NotificationCompat.isGroupSummary(it.notification)
            }
            .map { it.id }
    }
        .getOrDefault(emptyList())

    /**
     * Whether this app may post at all.
     *
     * Version-gated, and the gate is load-bearing rather than tidy. `POST_NOTIFICATIONS` did not
     * exist before API 33, and `checkSelfPermission` answers **denied** for a permission the
     * platform has never heard of — so the obvious one-line check silences every notification on
     * Android 12, which is this app's minimum and has no emulator image here to catch it. Below 33
     * the honest question is the one the user can actually answer: whether they have switched
     * notifications off in settings.
     */
    private fun isPermitted(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            manager.areNotificationsEnabled()
        }

    private companion object {
        /** Enough to read at a glance; more and the shade truncates it anyway. */
        const val MAX_SUMMARY_LINES = 5
    }
}

/**
 * The notification id for one conversation.
 *
 * Keyed on the **thread**, not the message. A reply arriving in a conversation the user has already
 * been told about should replace that notification rather than stack beside it, which is what makes
 * a busy thread one line in the shade instead of nine.
 */
internal fun notificationId(message: NewMessage): Int =
    "${message.accountKey}#${message.threadId}".hashCode()

internal fun summaryId(accountKey: String): Int = "summary#$accountKey".hashCode()

internal fun groupKey(accountKey: String): String = "account#$accountKey"

/**
 * `notify` that cannot throw.
 *
 * The permission is checked before the sync's own posts, but it can be revoked between that check
 * and this call — the user is in Settings while their phone is syncing — and a `SecurityException`
 * inside a broadcast receiver takes the process down. There is nothing useful to do about it beyond
 * not showing the notification.
 */
@SuppressLint("MissingPermission")
internal fun NotificationManagerCompat.notifySafely(id: Int, notification: Notification) {
    runCatching { notify(id, notification) }
}
