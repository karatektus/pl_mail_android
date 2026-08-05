package de.plmail.jmap.protocol

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * JMAP ids, as distinct types rather than a sea of `String`.
 *
 * These are `value class`es, so they cost nothing at runtime and still make the one mistake this
 * protocol invites impossible to compile: passing an id from one id space where another was meant.
 * plMail has at least four id spaces in play at once — account, mailbox *binding*, user-scoped
 * label, email — and several of them are small integers rendered as strings, so a wrong one usually
 * looks perfectly valid and names some unrelated object.
 *
 * They are also only unique *within* an account. Anything that stores them across accounts must key
 * by account as well; see the store's composite keys.
 */
@JvmInline
@Serializable
value class AccountId(val value: String) {
    override fun toString(): String = value
}

/**
 * A Mailbox id — which in plMail is a per-account *label binding* id, not an IMAP folder and not
 * the user-scoped label id.
 *
 * This is the id space `inMailbox` filters take, that `Email.mailboxIds` publishes, and that
 * `Email/set` accepts. One id space throughout: there is no case where a client needs to translate.
 */
@JvmInline
@Serializable
value class MailboxId(val value: String) {
    override fun toString(): String = value
}

/**
 * The user-scoped label a [MailboxId] materialises, from plMail's `labelId` extension to RFC 8621.
 *
 * Deliberately a separate type from [MailboxId] and accepted as input nowhere. It exists for
 * exactly one job: recognising that the "Invoices" binding in three different accounts is one
 * label, so the sidebar can collapse it into a single row. Matching on name instead breaks the
 * moment the label is renamed in one account.
 */
@JvmInline
@Serializable
value class LabelId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class EmailId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class ThreadId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class IdentityId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class CalendarId(val value: String) {
    override fun toString(): String = value
}

/**
 * An event **series** id, which is what `CalendarEvent/query` returns and `CalendarEvent/get`
 * takes.
 *
 * Not an occurrence. A weekly standup is one id however many times it appears in a month, and the
 * per-occurrence exceptions live inside it as `recurrenceOverrides` keyed by start time. Anything
 * addressing a single occurrence has to carry the series id *and* the recurrence id; there is no id
 * space for one on its own.
 */
@JvmInline
@Serializable
value class CalendarEventId(val value: String) {
    override fun toString(): String = value
}

/**
 * An opaque, namespaced blob reference: `m-<id>` for a message's RFC822 source, `p-<id>` for an
 * attachment part, `u-<id>` for a staged upload.
 *
 * **Never parse it.** The namespacing exists precisely because the underlying tables have
 * independent autoincrement ids, so the prefix is the only thing distinguishing `m-7` from `p-7`,
 * and the spec forbids clients reading meaning into the value regardless.
 */
@JvmInline
@Serializable
value class BlobId(val value: String) {
    override fun toString(): String = value
}

/**
 * A server state token.
 *
 * Compared, never interpreted. The one value worth naming is [INITIAL]: plMail shares a single
 * change-log sequence across every (account, type) pair, so a type's first row can sit at any
 * number and a client must be able to ask from a floor that is always answerable.
 *
 * Note what "answerable" does *not* mean: asking `Email/changes` from [INITIAL] returns *no*
 * changes for mail that already exists, not every message. A fresh client is populated by
 * `Email/query`, never by `/changes`.
 */
@JvmInline
@Serializable
value class StateToken(val value: String) {
    override fun toString(): String = value

    companion object {
        val INITIAL = StateToken("0")
    }
}
