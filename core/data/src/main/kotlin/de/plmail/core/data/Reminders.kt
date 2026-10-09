package de.plmail.core.data

/**
 * A calendar reminder the server pushed: the one kind of push that is the news itself.
 *
 * Everything else that arrives says only that a state token moved and leaves the app to ask what
 * changed. A reminder cannot work that way — one that makes you open the app to find out what it is
 * about is not a reminder — so it carries its own words, which is also why it only ever reaches
 * this app sealed, or through a UnifiedPush distributor that was handed ciphertext.
 *
 * - [title] and [body] are the server's wording, already in the user's language.
 * - [tag] names one alert for one occurrence of one event. A redelivery carries the same tag and
 *   must replace the notification rather than add a second.
 * - [url] is the web app's path for the day, kept for whatever opens the calendar at a date later;
 *   nothing follows it today.
 */
data class Reminder(val title: String, val body: String, val tag: String, val url: String? = null)

/**
 * Told when a reminder arrives.
 *
 * Declared here and implemented in `:core:notifications`, the way [NewMailListener] is and for the
 * same reason: this module knows a reminder arrived and nothing about drawing one, and a build
 * without the notifications module still receives pushes — it simply says nothing.
 */
interface ReminderListener {
    suspend fun onReminder(reminder: Reminder)
}
