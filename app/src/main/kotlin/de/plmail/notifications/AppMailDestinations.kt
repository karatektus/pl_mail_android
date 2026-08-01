package de.plmail.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.plmail.MainActivity
import de.plmail.core.notifications.MailDestinations
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a notification opens, expressed as intents at `MainActivity`.
 *
 * This lives in `:app` because it is the only module that can name the activity — every other one
 * is beneath it. It is also where the flags belong, and they matter more than they look.
 */
@Singleton
class AppMailDestinations
@Inject
constructor(@param:ApplicationContext private val context: Context) : MailDestinations {

    override fun openConversation(accountKey: String, threadId: String): PendingIntent =
        activity(ACTION_OPEN_CONVERSATION, accountKey, threadId, "open#$accountKey#$threadId")

    override fun reply(accountKey: String, emailId: String): PendingIntent =
        activity(ACTION_REPLY, accountKey, emailId, "reply#$accountKey#$emailId")

    private fun activity(
        action: String,
        accountKey: String,
        objectId: String,
        requestKey: String,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                this.action = action
                putExtra(EXTRA_ACCOUNT, accountKey)
                putExtra(EXTRA_OBJECT, objectId)
                // The activity is singleTask, so a tap while the app is already
                // open must reach the running instance rather than stack a
                // second copy of the mailbox. CLEAR_TOP with SINGLE_TOP is what
                // routes it into onNewIntent instead.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        return PendingIntent.getActivity(
            context,
            // Distinct per conversation. The system compares request code,
            // component and action -- never the extras -- so a shared code
            // silently makes every notification in the shade open whichever
            // conversation was posted first.
            requestKey.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationDestinationsModule {

    @Binds @Singleton abstract fun destinations(real: AppMailDestinations): MailDestinations
}

/** What a notification asked for, once the activity has read its intent. */
sealed interface NotificationRequest {
    data class OpenConversation(val accountKey: String, val threadId: String) : NotificationRequest

    data class Reply(val accountKey: String, val emailId: String) : NotificationRequest
}

/**
 * The request an intent carries, or null when it is an ordinary launch.
 *
 * Reading it here rather than in the activity keeps the extras' names in one file with the code
 * that writes them — two copies of a string key is exactly how a notification comes to open the
 * inbox instead of the conversation, silently and only in release.
 */
fun Intent.notificationRequest(): NotificationRequest? {
    val account = getStringExtra(EXTRA_ACCOUNT) ?: return null
    val objectId = getStringExtra(EXTRA_OBJECT) ?: return null

    return when (action) {
        ACTION_OPEN_CONVERSATION -> NotificationRequest.OpenConversation(account, objectId)
        ACTION_REPLY -> NotificationRequest.Reply(account, objectId)
        else -> null
    }
}

private const val ACTION_OPEN_CONVERSATION = "de.plmail.OPEN_CONVERSATION"
private const val ACTION_REPLY = "de.plmail.REPLY"
private const val EXTRA_ACCOUNT = "account"
private const val EXTRA_OBJECT = "object"
