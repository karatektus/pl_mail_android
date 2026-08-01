package de.plmail.core.notifications

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The channels mail is delivered on: **one per account**.
 *
 * A single "Mail" channel would be simpler and would be wrong for this product. Channels are the
 * only knob Android gives a user for importance, sound and Do Not Disturb, and they are permanent —
 * whatever a channel is created with is what it keeps, because the system deliberately ignores
 * later changes so an app cannot un-silence itself. One credential here reaches several mailboxes,
 * and "buzz for the personal one, stay quiet for the one the CI robot writes to" is a distinction
 * only per-account channels can express. Somebody self-hosting mail for a household is exactly the
 * person who wants it.
 *
 * The id is derived from the account key rather than from its address, because the address is
 * `name` on an account row and the seeded server sends a display name in that column — a channel id
 * has to survive that. It is stable across renames for the same reason: renaming re-labels the
 * channel and keeps the user's sound choice, where a new id would silently reset it.
 */
@Singleton
class MailChannels @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /**
     * Ensures the channel for one account exists, and returns its id.
     *
     * Creating an existing channel is a no-op apart from the name and description, which is what
     * makes this safe to call on every notification rather than once at launch — and calling it on
     * every notification is what makes a renamed account show its new name without a reinstall.
     */
    fun channelFor(accountKey: String, accountName: String): String {
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_ID, context.getString(R.string.channel_group_accounts))
        )

        val channel =
            NotificationChannel(
                    channelId(accountKey),
                    accountName,
                    // DEFAULT rather than HIGH: mail is not a phone call, and an
                    // app that arrives heads-up by default is an app people turn
                    // off entirely. The user can raise it per account, which is
                    // the reason these are per account.
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply {
                    group = GROUP_ID
                    description = context.getString(R.string.channel_description)
                    // The badge is the count of conversations, and the summary
                    // notification carries it, so children showing one each
                    // would triple it.
                    setShowBadge(true)
                }

        manager.createNotificationChannel(channel)

        return channel.id
    }

    /**
     * Whether anything posted here will actually be seen.
     *
     * Worth being able to state plainly rather than leaving somebody to guess, because there are
     * two independent ways for mail notifications to be off — the app-wide permission, and this
     * account's own channel — and the diagnostics screen is the place that has to tell them apart.
     */
    fun isEnabled(accountKey: String): Boolean {
        if (!manager.areNotificationsEnabled()) return false

        val channel = manager.getNotificationChannel(channelId(accountKey)) ?: return true

        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * `mail:` plus a hash, and never the key itself.
     *
     * The account key is a URL, and a channel id ends up in system logs, backup records and the
     * settings UI's own bookkeeping. Putting somebody's private hostname there is a leak that costs
     * nothing to avoid.
     */
    private fun channelId(accountKey: String): String = "mail:${accountKey.hashCode()}"

    private companion object {
        const val GROUP_ID = "accounts"
    }
}
