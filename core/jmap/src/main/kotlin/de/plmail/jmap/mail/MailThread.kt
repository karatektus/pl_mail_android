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
) {
    val isSnoozed: Boolean
        get() = snoozedUntil != null

    val messageCount: Int
        get() = emailIds.size
}
