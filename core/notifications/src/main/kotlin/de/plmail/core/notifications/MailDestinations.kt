package de.plmail.core.notifications

import android.app.PendingIntent

/**
 * Where a notification's taps go.
 *
 * An interface because this module cannot name the activity: `:app` owns `MainActivity` and every
 * other module is below it. The alternative — an implicit `plmail://` intent — was rejected on
 * purpose. It would need an intent filter with `BROWSABLE` to be resolvable, and that turns "open
 * this conversation" into something any web page can link to, on an app whose entire content is
 * private mail. A `PendingIntent` built by the module that already knows the component has none of
 * that surface.
 */
interface MailDestinations {

    /** Opens the reader on one conversation. */
    fun openConversation(accountKey: String, threadId: String): PendingIntent

    /** Opens the composer, replying to one message. */
    fun reply(accountKey: String, emailId: String): PendingIntent
}
