package de.plmail.core.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import de.plmail.core.data.ApplicationScope
import de.plmail.core.data.InlineReplies
import de.plmail.core.data.InlineReplyResult
import de.plmail.core.data.NewMessage
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Replying from the shade, without the app ever coming up.
 *
 * ## Why this is not the Reply that was here before
 *
 * The old Reply action opened `MainActivity` on the composer. That is a defensible thing to offer
 * and it is not what an inline reply is for: unlocking a phone, waiting for a mail app to draw
 * itself and finding the cursor is four interactions to send the word "yes". `RemoteInput` puts a
 * text field in the notification and hands what was typed to a broadcast — the phone can stay in
 * the user's pocket on the way to it, and on Wear and in Auto the platform can offer smart replies
 * and dictation over the same action.
 *
 * ## The receiver is not exported, and with `RemoteInput` that matters more than usual
 *
 * `PendingIntent`s carrying a `RemoteInput` **must be mutable** — filling the typed text into the
 * intent is exactly the mutation the system performs, and an immutable one arrives with no results
 * attached and silently sends nothing. So the usual reassurance ("it is immutable, nobody can
 * change it") is not available here, and the protection has to come from the other two properties
 * instead: the intent names its component explicitly, so it cannot be re-aimed at anything else,
 * and [InlineReplyReceiver] is `exported="false"`, so nothing but the system holding this app's own
 * `PendingIntent` can reach it. An exported receiver here would be an open API for sending mail as
 * the user, which is about the worst thing an app on someone's phone can offer.
 *
 * ## What the user is told
 *
 * Never nothing. The states are: the row becomes the conversation *with the reply in it* and the
 * word "Sending"; on success it goes away, because the mail is answered and in Sent; on failure it
 * is replaced by a row that still holds the text, says why, and offers "try again" when trying
 * again could work. A reply that vanished silently would be the worst outcome available to this
 * feature, and it is the one that costs nothing to reach if the failure path is left implicit.
 */
@AndroidEntryPoint
class InlineReplyReceiver : BroadcastReceiver() {

    @Inject lateinit var replies: InlineReplies

    @Inject lateinit var notifications: ReplyNotifications

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.repliedTo() ?: return
        val typed = intent.replyText()

        // Nothing typed. The shade's own send button is disabled on an empty
        // field, but whitespace gets past it and a speech recogniser on Wear or
        // in Auto regularly hands over an empty string. Doing nothing at all is
        // not an option: the system has already swapped the row for a spinner
        // and waits for this app to post something, so an early `return` here
        // leaves a notification spinning until the user reboots.
        if (typed.isNullOrBlank()) {
            notifications.restore(message)
            return
        }

        notifications.sending(message, typed)

        // goAsync *and* the application scope, rather than the one or the other,
        // and the pair is deliberate.
        //
        // The scope alone is what NotificationActionReceiver uses and is right
        // for archive: the work outlives the broadcast rather than racing a
        // deadline. But the instant onReceive returns, this process drops to
        // "cached" and is the first thing the system kills under memory
        // pressure -- which for an archive means a retry next sync and for a
        // send means a message the user watched being sent and that never went.
        // goAsync holds the process at broadcast priority while the round trip
        // happens.
        //
        // goAsync alone is not enough either: the system allows a foreground
        // broadcast about ten seconds, and a save-then-submit against a home NAS
        // over a mobile uplink does not reliably fit in ten seconds. So the
        // result is released after BROADCAST_GRACE_MS whatever has happened, and
        // the send carries on in the scope -- back at cached priority, but by
        // then the draft is already on the server, which is the whole reason
        // SendQueue saves before it submits.
        val pending = goAsync()
        val released = AtomicBoolean(false)
        val release = { if (released.compareAndSet(false, true)) pending.finish() else Unit }

        scope.launch {
            try {
                when (val result = send(message, typed)) {
                    InlineReplyResult.Sent -> notifications.sent(message)
                    // Not reachable -- the text was checked above -- but the
                    // exhaustive `when` is what keeps it that way if the rule
                    // for "empty" ever moves into :core:data alone.
                    InlineReplyResult.NothingTyped -> notifications.restore(message)
                    is InlineReplyResult.NotSent ->
                        notifications.failed(message, typed, result.reason)
                }
            } finally {
                release()
            }
        }

        scope.launch {
            delay(BROADCAST_GRACE_MS)
            release()
        }
    }

    /**
     * The send, with anything it did not already classify turned into an answer.
     *
     * [InlineReplies] converts every network and server failure into a result, so what reaches here
     * is the cache failing to answer at all — a database this build cannot open, most plausibly.
     * That is not a reason to retry, so it reads as "cannot be answered from here", and it is
     * logged because it is the one branch with no other trace.
     */
    private suspend fun send(message: NewMessage, typed: String): InlineReplyResult =
        try {
            replies.send(message.accountKey, message.emailId, typed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Inline reply could not be built", failure)

            InlineReplyResult.NotSent(InlineReplyResult.Reason.UNANSWERABLE)
        }

    private companion object {
        const val TAG = "plMail.Notify"

        /**
         * How long the broadcast is held open.
         *
         * Under the platform's ten-second ceiling for a foreground broadcast, with enough margin
         * that a slow release does not itself become the thing that trips it.
         */
        const val BROADCAST_GRACE_MS = 8_000L
    }
}

/**
 * The typed reply, from wherever this intent is carrying it.
 *
 * Two places, because there are two ways in. A fresh reply arrives as a `RemoteInput` result the
 * system filled in; a **retry** arrives as a plain extra, because by then the text is one the user
 * already typed and re-opening the keyboard to make them type it again is not a retry.
 */
internal fun Intent.replyText(): String? =
    RemoteInput.getResultsFromIntent(this)?.getCharSequence(KEY_REPLY_TEXT)?.toString()
        ?: getStringExtra(EXTRA_REPLY_TEXT)

/** Builds the intents [InlineReplyReceiver] answers. */
internal object InlineReplyIntents {

    /**
     * The action that puts a text field in the notification.
     *
     * `FLAG_MUTABLE` is not optional — see the note on [InlineReplyReceiver]. `FLAG_UPDATE_CURRENT`
     * so that re-posting a conversation with newer mail in it refreshes the extras rather than
     * replying to whichever message the shade saw first.
     */
    fun reply(context: Context, message: NewMessage): PendingIntent {
        val intent =
            Intent(context, InlineReplyReceiver::class.java).apply {
                action = ACTION_REPLY
                putMessage(message)
            }

        return PendingIntent.getBroadcast(
            context,
            // Per conversation. Request codes are how the system tells two
            // PendingIntents apart -- extras are not part of the comparison --
            // so a shared code would have the reply typed under one
            // conversation sent to another.
            "reply#${notificationId(message)}".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /**
     * Sending the same text again, one tap, no keyboard.
     *
     * Immutable, unlike [reply]: there is no `RemoteInput` on this one, so there is nothing for the
     * system to fill in and no reason to leave it open. The text rides in an extra, which is where
     * it survives the process being killed between the failure and the retry — the failure
     * notification and its `PendingIntent` are both held by the system, so what the user wrote
     * outlives this app's memory.
     */
    fun retry(context: Context, message: NewMessage, text: String): PendingIntent {
        val intent =
            Intent(context, InlineReplyReceiver::class.java).apply {
                action = ACTION_REPLY
                putMessage(message)
                putExtra(EXTRA_REPLY_TEXT, text)
            }

        return PendingIntent.getBroadcast(
            context,
            "retry#${replyFailureId(message)}".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * The Reply action, with the field attached.
 *
 * [setAllowGeneratedReplies] and [NotificationCompat.Action.SEMANTIC_ACTION_REPLY] are what make
 * this work as a reply on the surfaces that are not a phone screen: Wear offers its own suggested
 * answers against the action's `RemoteInput`, and Auto reads the message aloud and takes dictation
 * for it, but only for an action that says what it is. Without the semantic action a car reads out
 * "Reply" as an unnamed button and does nothing useful with it.
 */
internal fun inlineReplyAction(
    context: Context,
    message: NewMessage,
): NotificationCompat.Action =
    NotificationCompat.Action.Builder(
            R.drawable.ic_notification_reply,
            context.getString(R.string.action_reply),
            InlineReplyIntents.reply(context, message),
        )
        .addRemoteInput(
            RemoteInput.Builder(KEY_REPLY_TEXT)
                .setLabel(context.getString(R.string.reply_hint))
                .build()
        )
        .setAllowGeneratedReplies(true)
        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
        // The action does nothing the user has to look at, so on Wear it should
        // not raise a second screen asking them to confirm on the phone.
        .setShowsUserInterface(false)
        .build()

/**
 * What the shade shows while a reply is on its way, and what it shows if it never gets there.
 *
 * Separate from [MailNotifier] because it is not about mail arriving. It is the same channel and
 * the same conversation row, deliberately: a reply's progress belongs where the message it answers
 * is, not in a second notification competing with it.
 */
@Singleton
class ReplyNotifications
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val channels: MailChannels,
    private val destinations: MailDestinations,
) {

    private val manager = NotificationManagerCompat.from(context)

    /**
     * The conversation, unchanged.
     *
     * For a reply with nothing in it. The system has already replaced the row with a spinner, so
     * something has to be posted to take it away, and the honest something is the notification that
     * was there before the user opened the field.
     */
    fun restore(message: NewMessage) {
        val channelId = channels.channelFor(message.accountKey, message.accountName)

        manager.notifySafely(
            notificationId(message),
            conversationNotification(
                context,
                message,
                channelId,
                groupKey(message.accountKey),
                destinations,
            ),
        )
    }

    /**
     * The conversation with the reply in it, and the word "Sending".
     *
     * The reply is added to the `MessagingStyle` as the user's own message, which is what makes the
     * row read as a conversation rather than as a status line — and it is also the receipt: what
     * the user sees on screen is the text that is actually being sent, before it has gone anywhere.
     *
     * The actions come off. Archiving or replying again to a message whose answer is mid-flight
     * would put a second write on the same account's Email state, and the queue would serialise it
     * behind this one for several seconds with nothing to explain the delay.
     *
     * **Deliberately not `setOngoing`.** An ongoing notification cannot be swiped away, and if this
     * process is killed between here and the result there is nothing left to take it down: the user
     * would be left with a permanent, undismissable "Sending" they can do nothing about. Swipeable
     * is the safer failure: the send continues regardless, and its result posts either way.
     */
    fun sending(message: NewMessage, text: String) {
        val channelId = channels.channelFor(message.accountKey, message.accountName)

        val style =
            messagingStyle(context)
                .setConversationTitle(message.subject ?: context.getString(R.string.no_subject))
                .addMessage(
                    message.preview,
                    message.receivedAt,
                    androidx.core.app.Person.Builder().setName(message.sender).build(),
                )
                // A null person is the local user, which is what draws this as
                // the reply rather than as another incoming message.
                .addMessage(text, System.currentTimeMillis(), null as androidx.core.app.Person?)

        manager.notifySafely(
            notificationId(message),
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification_mail)
                .setStyle(style)
                .setContentTitle(message.sender)
                .setContentText(text)
                .setSubText(context.getString(R.string.reply_sending))
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setGroup(groupKey(message.accountKey))
                .setAutoCancel(false)
                // Silent: the user is holding the phone and has just pressed
                // send. Buzzing at them about their own keystroke is the kind
                // of thing that gets a channel switched off.
                .setOnlyAlertOnce(true)
                .setContentIntent(
                    destinations.openConversation(message.accountKey, message.threadId)
                )
                .build(),
        )
    }

    /**
     * The reply has gone.
     *
     * The row is taken away, which is what Archive and Mark read already do from this shade: the
     * conversation has been dealt with, and a "sent!" notification is one more thing to dismiss for
     * an outcome the user asked for and got. What is left behind is the message in Sent, which is
     * the same place a reply written in the app ends up.
     *
     * Any failure notification for the same message goes too — this is the path a successful retry
     * takes, and leaving "reply not sent" on screen beside a reply that has been sent is worse than
     * having said nothing.
     */
    fun sent(message: NewMessage) {
        manager.cancel(notificationId(message))
        manager.cancel(replyFailureId(message))
        tidySummary(context, manager, message.accountKey)
    }

    /**
     * It did not go, and here is what was written.
     *
     * A separate notification with its own id rather than the conversation row, for one reason:
     * more mail can arrive in that thread, and a new arrival re-posting the conversation would
     * quietly overwrite the only remaining copy of the user's unsent reply. Keyed on the message
     * being answered, so two failed replies to two messages keep two rows.
     *
     * Not auto-cancelling and not in the account's group: this is not new mail, it must not be
     * collapsed under a heading that says it is, and it must not disappear because the user tapped
     * it to read the rest. Swiping it away is the way to discard the reply, and that is a
     * deliberate act.
     *
     * **Try again is offered only for [InlineReplyResult.Reason.OFFLINE].** For a refusal or a
     * message this device cannot read, the same request produces the same answer, and a button that
     * fails identically every time is worse than no button. Those failures get the tap that opens
     * the composer instead, which is somewhere the user can actually change something.
     */
    fun failed(message: NewMessage, text: String, reason: InlineReplyResult.Reason) {
        val channelId = channels.channelFor(message.accountKey, message.accountName)
        val explanation = context.getString(reason.explanation())

        val builder =
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification_reply)
                .setContentTitle(context.getString(R.string.reply_not_sent))
                // The reason collapsed, the reason *and the text* expanded. What
                // must never be missing is the text: it is the only copy, and a
                // notification that says "not sent" without showing what was not
                // sent has lost the message just as thoroughly as saying nothing.
                .setContentText(explanation)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(explanation + "\n\n" + text)
                        .setSummaryText(message.sender)
                )
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(destinations.reply(message.accountKey, message.emailId))
                .addAction(inlineReplyAction(context, message))

        if (reason == InlineReplyResult.Reason.OFFLINE) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                        R.drawable.ic_notification_reply,
                        context.getString(R.string.action_try_again),
                        InlineReplyIntents.retry(context, message, text),
                    )
                    .build()
            )
        }

        manager.cancel(notificationId(message))
        manager.notifySafely(replyFailureId(message), builder.build())
        tidySummary(context, manager, message.accountKey)
    }
}

/** Which sentence explains a failure to somebody who is not going to read a log. */
internal fun InlineReplyResult.Reason.explanation(): Int =
    when (this) {
        InlineReplyResult.Reason.OFFLINE -> R.string.reply_failed_offline
        InlineReplyResult.Reason.UNANSWERABLE -> R.string.reply_failed_unanswerable
        InlineReplyResult.Reason.REFUSED -> R.string.reply_failed_refused
    }

/**
 * The id a failed reply sits on.
 *
 * Keyed on the **message**, unlike [notificationId], which is keyed on the conversation. A failed
 * reply belongs to the one message it was an answer to, and further mail in the same thread must
 * not land on top of it.
 */
internal fun replyFailureId(message: NewMessage): Int =
    "reply-failed#${message.accountKey}#${message.emailId}".hashCode()

/**
 * Everything the receiver needs, written into the intent.
 *
 * Carried rather than looked up, for the reason [NewMessage] itself gives: this is read on a
 * broadcast receiver with about ten seconds to live, and a shape that needs a database read first
 * is a shape that sometimes does not get read at all. It also means a retry fired days later, from
 * a `PendingIntent` the system has been holding, still knows what it is answering.
 */
private fun Intent.putMessage(message: NewMessage) {
    putExtra(EXTRA_ACCOUNT, message.accountKey)
    putExtra(EXTRA_ACCOUNT_NAME, message.accountName)
    putExtra(EXTRA_EMAIL, message.emailId)
    putExtra(EXTRA_THREAD, message.threadId)
    putExtra(EXTRA_SENDER, message.sender)
    putExtra(EXTRA_SUBJECT, message.subject)
    putExtra(EXTRA_PREVIEW, message.preview)
    putExtra(EXTRA_RECEIVED_AT, message.receivedAt)
}

/** The message this intent is an answer to, or null for one that carries no answerable message. */
internal fun Intent.repliedTo(): NewMessage? =
    NewMessage(
        accountKey = getStringExtra(EXTRA_ACCOUNT) ?: return null,
        accountName = getStringExtra(EXTRA_ACCOUNT_NAME).orEmpty(),
        emailId = getStringExtra(EXTRA_EMAIL) ?: return null,
        threadId = getStringExtra(EXTRA_THREAD) ?: return null,
        sender = getStringExtra(EXTRA_SENDER).orEmpty(),
        // Genuinely absent for a message with no Subject header, and the shade
        // has its own word for that -- so null must survive the round trip
        // rather than becoming an empty conversation title.
        subject = getStringExtra(EXTRA_SUBJECT),
        preview = getStringExtra(EXTRA_PREVIEW).orEmpty(),
        receivedAt = getLongExtra(EXTRA_RECEIVED_AT, 0L),
    )

/**
 * The bundle key the typed text arrives under.
 *
 * Stable, because it is written by the system into an intent this app handed over possibly days
 * earlier: a `PendingIntent` created by an older install can still be fired after an upgrade, and a
 * renamed key would read as an empty reply.
 */
private const val KEY_REPLY_TEXT = "de.plmail.notifications.REPLY_TEXT"

private const val ACTION_REPLY = "de.plmail.notifications.REPLY_INLINE"

private const val EXTRA_REPLY_TEXT = "reply-text"
private const val EXTRA_ACCOUNT = "account"
private const val EXTRA_ACCOUNT_NAME = "account-name"
private const val EXTRA_EMAIL = "email"
private const val EXTRA_THREAD = "thread"
private const val EXTRA_SENDER = "sender"
private const val EXTRA_SUBJECT = "subject"
private const val EXTRA_PREVIEW = "preview"
private const val EXTRA_RECEIVED_AT = "received-at"
