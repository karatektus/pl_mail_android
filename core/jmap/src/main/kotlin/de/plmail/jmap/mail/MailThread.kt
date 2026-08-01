package de.plmail.jmap.mail

import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.ThreadId
import kotlinx.serialization.Serializable

/**
 * A conversation.
 *
 * RFC 8621's Thread carries only `id` and `emailIds`. [snoozedUntil] is plMail's extension — snooze
 * is genuinely a thread-level property rather than a flag on any one message, and it is settable
 * through `Thread/set`, which the spec does not define at all (a spec Thread is read-only, being
 * derived from its messages).
 *
 * Threading is currently Message-ID based rather than Gmail-native, so expect occasional divergence
 * from how the Gmail web UI groups the same mail.
 */
@Serializable
data class MailThread(
    val id: ThreadId,
    val emailIds: List<EmailId> = emptyList(),
    /**
     * When a snoozed conversation is due back, or null.
     *
     * The server clears an elapsed snooze, so a value here is always still pending — nothing has to
     * re-check the clock to know that.
     */
    val snoozedUntil: String? = null,
    /**
     * The conversation's inbox category — `primary`, `social`, `promotions`, `updates`, `forums` —
     * or null when it has never been classified.
     *
     * plMail's second Thread extension, and the **resolved** value: the server stores a raw
     * category per message and folds them onto the conversation most-recent-wins. This is the one a
     * tab is drawn from, because a tab holds conversations. [Email.category] is the raw signal it
     * came from and disagrees with this whenever somebody replied to a newsletter.
     *
     * **Null is not Primary.** It means unclassified, and the server's own inbox query puts such a
     * conversation in no tab at all. Folding it into Primary here would put mail on the phone's
     * Primary tab that the web's does not have, which is the failure mode this client is most
     * careful about.
     *
     * A wire string rather than an enum for the same reason [Mailbox.color] is: an unknown value
     * from a newer server must survive the round trip to the cache rather than being erased by an
     * enum written today.
     */
    val category: String? = null,
) {
    val isSnoozed: Boolean
        get() = snoozedUntil != null

    val messageCount: Int
        get() = emailIds.size
}
