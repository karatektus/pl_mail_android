package de.plmail.core.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import de.plmail.core.data.InlineReplyResult
import de.plmail.core.data.NewMessage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The reply in the shade, asserted against the notification the platform is actually handed.
 *
 * Robolectric rather than a fake builder, because every failure this pins is a property of a real
 * framework object and none of them throws. A `RemoteInput` that was never attached, an immutable
 * `PendingIntent` the system cannot write the typed text into, a receiver the merged manifest
 * exported — all three compile, all three look right in review, and all three are only discovered
 * by someone typing a reply on a phone and watching nothing happen. A test over a hand-rolled
 * notification double would agree with itself about every one of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InlineReplyNotificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private val message =
        NewMessage(
            accountKey = "https://mail.example/a",
            accountName = "Personal",
            emailId = "e-1",
            threadId = "t-1",
            sender = "Anna",
            subject = "Roof repairs",
            preview = "Can you come on Tuesday?",
            receivedAt = 1_700_000_000_000L,
        )

    /** `:app` owns the real one — it is the only module that can name the activity. */
    private object Destinations : MailDestinations {
        override fun openConversation(accountKey: String, threadId: String): PendingIntent =
            broadcast("open#$accountKey#$threadId")

        override fun reply(accountKey: String, emailId: String): PendingIntent =
            broadcast("reply#$accountKey#$emailId")

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

    private fun notifications() = ReplyNotifications(context, MailChannels(context), Destinations)

    private fun conversation(): Notification =
        conversationNotification(
            context,
            message,
            "channel",
            groupKey(message.accountKey),
            Destinations,
        )

    private fun actionsOf(notification: Notification): List<NotificationCompat.Action> =
        (0 until NotificationCompat.getActionCount(notification)).mapNotNull {
            NotificationCompat.getAction(notification, it)
        }

    private fun replyAction(notification: Notification): NotificationCompat.Action? =
        actionsOf(notification).firstOrNull { !it.remoteInputs.isNullOrEmpty() }

    private fun posted(id: Int): Notification? = shadowOf(manager).getNotification(id)

    private fun messagesIn(
        notification: Notification
    ): List<NotificationCompat.MessagingStyle.Message> =
        assertNotNull(
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
                    notification
                )
            )
            .messages

    private fun bigTextOf(notification: Notification): String =
        notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

    // ------------------------------------------------------------------- the action

    @Test
    fun `the reply action carries a text field, so the shade can be typed into`() {
        // Without this the action is an ordinary button and the whole feature is
        // an app launch again -- which is precisely what it looked like before.
        val action = assertNotNull(replyAction(conversation()))

        assertEquals(1, action.remoteInputs?.size)
    }

    @Test
    fun `the reply PendingIntent is mutable, or no text ever arrives`() {
        // The one that would ship. A RemoteInput's PendingIntent has to be
        // MUTABLE because filling in the typed text *is* the mutation the system
        // performs; FLAG_IMMUTABLE is the habit everywhere else in this file's
        // neighbours and here it produces an action that fires with an empty
        // result bundle and silently sends nothing.
        val action = assertNotNull(replyAction(conversation()))

        assertFalse(shadowOf(assertNotNull(action.actionIntent)).isImmutable)
    }

    @Test
    fun `Wear and Auto are told this is a reply`() {
        // setSemanticAction is what lets a car read the message aloud and offer
        // to dictate an answer, and setAllowGeneratedReplies is what lets a
        // watch suggest one. An action without them is an unnamed button on both.
        val action = assertNotNull(replyAction(conversation()))

        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_REPLY, action.semanticAction)
        assertTrue(action.allowGeneratedReplies)
    }

    // ------------------------------------------------------------- what is extracted

    @Test
    fun `the text typed in the shade is what comes out of the intent`() {
        val inputs = assertNotNull(replyAction(conversation())?.remoteInputs)
        val filled = Intent()

        RemoteInput.addResultsToIntent(
            inputs,
            filled,
            Bundle().apply { putCharSequence(inputs.single().resultKey, "Tuesday works") },
        )

        assertEquals("Tuesday works", filled.replyText())
    }

    @Test
    fun `a retry carries the text the user already typed, without asking again`() {
        // The failed reply's text lives in the PendingIntent the system holds, so
        // it survives this process being killed between the failure and the tap.
        val intent =
            shadowOf(InlineReplyIntents.retry(context, message, "Tuesday works")).savedIntent

        assertEquals("Tuesday works", intent.replyText())
        assertEquals("e-1", intent.repliedTo()?.emailId)
    }

    @Test
    fun `an intent carrying no message is not answered`() {
        // Anything that reached the receiver without the extras it needs. There
        // is nothing to reply to and nothing to put on screen.
        assertNull(Intent().repliedTo())
    }

    @Test
    fun `a message with no subject keeps its absence`() {
        // Null and empty are different here: the shade has its own word for a
        // message with no Subject header, and an empty conversation title is not
        // it.
        val intent = shadowOf(InlineReplyIntents.retry(context, message.copy(subject = null), "x"))

        assertNull(intent.savedIntent.repliedTo()?.subject)
    }

    // ------------------------------------------------------------------- the receiver

    @Test
    fun `the receiver is not exported in the merged manifest`() {
        // Asserted against what actually ships rather than against the XML,
        // because the merger is what decides this and a library manifest is only
        // an input to it. Exported, this is a one-line API for any app on the
        // device to send mail as the user -- and the usual mitigation, an
        // immutable PendingIntent, is unavailable here by construction.
        val info =
            context.packageManager.getReceiverInfo(
                ComponentName(context, InlineReplyReceiver::class.java),
                0,
            )

        assertFalse(info.exported)
    }

    // -------------------------------------------------------------------- the states

    @Test
    fun `a reply in flight shows what is being sent`() {
        notifications().sending(message, "Tuesday works")

        val shown = assertNotNull(posted(notificationId(message)))
        val written = messagesIn(shown).last()

        // Read back off the MessagingStyle rather than off the content text,
        // which the style overwrites -- and this is the more truthful place to
        // read it anyway: what the user sees is the conversation with their own
        // answer in it, which is also the receipt that the text about to be sent
        // is the text they typed.
        assertEquals("Tuesday works", written.text)
        // A null person is the local user, which is what draws it as the reply
        // rather than as a second message from Anna.
        assertNull(written.person)
        assertEquals(
            context.getString(R.string.reply_sending),
            shown.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        )
    }

    @Test
    fun `a reply in flight can still be swiped away`() {
        // Deliberately not ongoing. If this process dies between the send
        // starting and its result, an ongoing notification is a permanent,
        // undismissable "Sending" the user can do nothing about.
        notifications().sending(message, "Tuesday works")

        assertEquals(
            0,
            assertNotNull(posted(notificationId(message))).flags and
                Notification.FLAG_ONGOING_EVENT,
        )
    }

    @Test
    fun `a sent reply takes the row away and leaves no failure behind it`() {
        val notifications = notifications()

        notifications.failed(message, "Tuesday works", InlineReplyResult.Reason.OFFLINE)
        notifications.sent(message)

        assertNull(posted(notificationId(message)))
        assertNull(posted(replyFailureId(message)))
    }

    @Test
    fun `a failed reply keeps the text where the user can still see it`() {
        // The single thing that must never be lost. A notification saying "not
        // sent" without showing what was not sent has dropped the message just
        // as thoroughly as saying nothing at all.
        notifications().failed(message, "Tuesday works", InlineReplyResult.Reason.OFFLINE)

        val shown = assertNotNull(posted(replyFailureId(message)))

        assertTrue(bigTextOf(shown).contains("Tuesday works"), bigTextOf(shown))
        assertTrue(
            bigTextOf(shown).contains(context.getString(R.string.reply_failed_offline)),
            bigTextOf(shown),
        )
    }

    @Test
    fun `a failed reply does not sit on the conversation, where new mail would overwrite it`() {
        // Keyed on the message rather than the thread. Further mail arriving in
        // the same conversation re-posts the conversation row, and that would
        // quietly take the only copy of the unsent reply with it.
        notifications().failed(message, "Tuesday works", InlineReplyResult.Reason.OFFLINE)

        assertNull(posted(notificationId(message)))
        assertNotNull(posted(replyFailureId(message)))
    }

    @Test
    fun `a failed reply cannot be dismissed by reading it`() {
        notifications().failed(message, "Tuesday works", InlineReplyResult.Reason.OFFLINE)

        val shown = assertNotNull(posted(replyFailureId(message)))

        assertEquals(0, shown.flags and Notification.FLAG_AUTO_CANCEL)
    }

    @Test
    fun `being offline offers to try again`() {
        notifications().failed(message, "Tuesday works", InlineReplyResult.Reason.OFFLINE)

        val shown = assertNotNull(posted(replyFailureId(message)))
        val retry =
            actionsOf(shown).firstOrNull {
                it.title == context.getString(R.string.action_try_again)
            }

        assertNotNull(retry)
        assertEquals(
            "Tuesday works",
            shadowOf(assertNotNull(retry.actionIntent)).savedIntent.replyText(),
        )
    }

    @Test
    fun `a refusal does not, because the same request gets the same answer`() {
        // A button that fails identically every time is worse than no button.
        notifications().failed(message, "Tuesday works", InlineReplyResult.Reason.REFUSED)

        val shown = assertNotNull(posted(replyFailureId(message)))

        assertNull(
            actionsOf(shown).firstOrNull {
                it.title == context.getString(R.string.action_try_again)
            }
        )
    }

    @Test
    fun `every failure can be rewritten without opening the app`() {
        InlineReplyResult.Reason.entries.forEach { reason ->
            notifications().failed(message, "Tuesday works", reason)

            val shown =
                assertNotNull(posted(replyFailureId(message)), "no notification for $reason")

            assertNotNull(replyAction(shown), "no way to reply again for $reason")
        }
    }

    @Test
    fun `an empty reply puts the conversation back exactly as it was`() {
        // The system has already swapped the row for a spinner and is waiting for
        // this app to post something. Posting nothing leaves it spinning until
        // the phone is rebooted.
        notifications().restore(message)

        val shown = assertNotNull(posted(notificationId(message)))
        val messages = messagesIn(shown)

        // Anna's message and nothing else: no half-sent reply left in the
        // conversation, and the field still there to type into.
        assertEquals("Can you come on Tuesday?", messages.single().text)
        assertEquals("Anna", messages.single().person?.name)
        assertNotNull(replyAction(shown))
    }
}
