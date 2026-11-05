package de.plmail.core.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import de.plmail.core.data.NewMessage
import de.plmail.core.data.ShadeAction
import de.plmail.core.data.ShadeActions
import de.plmail.crypto.EncryptedMessages

/**
 * One conversation, as it sits in the shade.
 *
 * `MessagingStyle` rather than `BigTextStyle`, and not for decoration: it is the style Android
 * Auto, Wear and the conversation section of the shade all understand, so it is what makes the
 * sender's name the heading rather than the app's. A mail client that announces itself instead of
 * announcing who wrote is the wrong way round.
 *
 * A top-level function rather than a method on [MailNotifier], because it now has a second caller
 * that is not the sync: an inline reply the user *sent nothing in* has to put this row back exactly
 * as it was — see [ReplyNotifications.restore] — and a second builder that drifted from this one
 * would show a different notification for the same conversation depending on which code path last
 * touched it.
 */
internal fun conversationNotification(
    context: Context,
    message: NewMessage,
    channelId: String,
    group: String,
    destinations: MailDestinations,
    /** The two buttons beside Reply, as chosen in Settings → Notifications (#93). */
    buttons: ShadeActions = ShadeActions(),
): Notification {
    val notificationId = notificationId(message)
    val sender = Person.Builder().setName(message.sender).build()
    val encrypted = context.getString(R.string.encrypted_message)
    // The outer subject of an encrypted message is often only the placeholder
    // its client put there ("..."); the real one is inside, out of reach here.
    val subject =
        if (message.isEncrypted && EncryptedMessages.isPlaceholderSubject(message.subject)) {
            encrypted
        } else {
            message.subject ?: context.getString(R.string.no_subject)
        }

    val style =
        messagingStyle(context)
            .setConversationTitle(subject)
            .addMessage(
                if (message.isEncrypted) encrypted else message.preview,
                message.receivedAt,
                sender,
            )

    val builder =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_mail)
            .setStyle(style)
            .setContentTitle(message.sender)
            .setContentText(if (message.isEncrypted) encrypted else subject)
            .setWhen(message.receivedAt)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setGroup(group)
            .setAutoCancel(true)
            .setContentIntent(destinations.openConversation(message.accountKey, message.threadId))
            // Dismissing one conversation must not leave the account's summary
            // behind with nothing under it, which is what happens if nobody
            // tidies up -- see NotificationActions.
            .setDeleteIntent(
                NotificationActions.dismissed(context, message.accountKey, notificationId)
            )

    buttons.shown.forEach { button ->
        builder.addAction(
            NotificationCompat.Action.Builder(
                    button.icon(),
                    context.getString(button.title()),
                    NotificationActions.button(context, message, notificationId, button),
                )
                .build()
        )
    }

    // Answering from the shade sends in the clear, and an encrypted
    // conversation is the one place that must not happen by accident.
    if (!message.isEncrypted) builder.addAction(inlineReplyAction(context, message))

    return builder.build()
}

/**
 * The style's own "you", which every one of these notifications needs.
 *
 * `MessagingStyle` requires a name for the reader as well as the sender. It is never drawn for an
 * incoming message, but a blank one renders as a stray separator — and it *is* drawn the moment a
 * reply is added to the conversation, which is the whole point of the inline reply.
 */
internal fun messagingStyle(context: Context): NotificationCompat.MessagingStyle =
    NotificationCompat.MessagingStyle(
        Person.Builder().setName(context.getString(R.string.notification_you)).build()
    )

private fun ShadeAction.icon(): Int =
    when (this) {
        ShadeAction.ARCHIVE -> R.drawable.ic_notification_archive
        ShadeAction.MARK_READ -> R.drawable.ic_notification_read
        ShadeAction.TRASH -> R.drawable.ic_notification_trash
        ShadeAction.STAR -> R.drawable.ic_notification_star
        // Never drawn: ShadeActions.shown leaves it out.
        ShadeAction.NONE -> R.drawable.ic_notification_mail
    }

private fun ShadeAction.title(): Int =
    when (this) {
        ShadeAction.ARCHIVE -> R.string.action_archive
        ShadeAction.MARK_READ -> R.string.action_mark_read
        ShadeAction.TRASH -> R.string.action_trash
        ShadeAction.STAR -> R.string.action_star
        ShadeAction.NONE -> R.string.action_archive
    }
