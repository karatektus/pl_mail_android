package de.plmail.jmap.mail

import de.plmail.jmap.protocol.MailboxId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A filter the server actually understands.
 *
 * Modelled as a closed hierarchy rather than a free-form map on purpose: **plMail raises
 * `unsupportedFilter` for anything it does not recognise rather than ignoring it**, and a
 * quietly-dropped condition would return far too much mail with no way for the client to tell.
 * Making an unsupported filter unrepresentable moves that failure from runtime to compile time.
 *
 * Note what is deliberately absent: `hasLabel`/`notLabel` exist server-side but take user-scoped
 * label ids rather than mailbox binding ids. They are there for mail rules, which have no reason to
 * know about the JMAP id space. Clients use [InMailbox].
 */
sealed interface EmailFilter {

    fun toJson(): JsonObject

    // --- Conditions ---

    /** Mailbox *binding* id — the same space `Email.mailboxIds` publishes. */
    data class InMailbox(val mailboxId: MailboxId) : EmailFilter {
        override fun toJson() = buildJsonObject { put("inMailbox", mailboxId.value) }
    }

    data class InMailboxOtherThan(val mailboxIds: List<MailboxId>) : EmailFilter {
        init {
            require(mailboxIds.isNotEmpty()) {
                "inMailboxOtherThan needs at least one id; the server rejects an empty array."
            }
        }

        override fun toJson() = buildJsonObject {
            put("inMailboxOtherThan", buildJsonArray { mailboxIds.forEach { add(it.value) } })
        }
    }

    /** Strictly before, as a UTC date-time. */
    data class Before(val utc: String) : EmailFilter {
        override fun toJson() = buildJsonObject { put("before", utc) }
    }

    /** At or after. */
    data class After(val utc: String) : EmailFilter {
        override fun toJson() = buildJsonObject { put("after", utc) }
    }

    data class MinSize(val bytes: Long) : EmailFilter {
        override fun toJson() = buildJsonObject { put("minSize", bytes) }
    }

    data class MaxSize(val bytes: Long) : EmailFilter {
        override fun toJson() = buildJsonObject { put("maxSize", bytes) }
    }

    /** Only the four keywords in [Keyword] round-trip; anything else is rejected. */
    data class HasKeyword(val keyword: Keyword) : EmailFilter {
        override fun toJson() = buildJsonObject { put("hasKeyword", keyword.wire) }
    }

    data class NotKeyword(val keyword: Keyword) : EmailFilter {
        override fun toJson() = buildJsonObject { put("notKeyword", keyword.wire) }
    }

    data class HasAttachment(val value: Boolean) : EmailFilter {
        override fun toJson() = buildJsonObject { put("hasAttachment", value) }
    }

    /**
     * Real full-text search — Postgres `tsvector` with `websearch_to_tsquery`, stemmed and ranked,
     * not a substring scan.
     *
     * It only covers mail inside each account's sync window. Mail older than that is not in the
     * database at all, so "no results" for a dated query is a dishonest answer; say the window is
     * the reason.
     */
    data class Text(val query: String) : EmailFilter {
        override fun toJson() = buildJsonObject { put("text", query) }
    }

    /** Substring, case-insensitive. Covers both address and display name. */
    data class From(val query: String) : EmailFilter {
        override fun toJson() = buildJsonObject { put("from", query) }
    }

    data class To(val query: String) : EmailFilter {
        override fun toJson() = buildJsonObject { put("to", query) }
    }

    data class Cc(val query: String) : EmailFilter {
        override fun toJson() = buildJsonObject { put("cc", query) }
    }

    data class Bcc(val query: String) : EmailFilter {
        override fun toJson() = buildJsonObject { put("bcc", query) }
    }

    data class Subject(val query: String) : EmailFilter {
        override fun toJson() = buildJsonObject { put("subject", query) }
    }

    data class Body(val query: String) : EmailFilter {
        override fun toJson() = buildJsonObject { put("body", query) }
    }

    /** Inline parts have null filenames and never match. */
    data class Filename(val query: String) : EmailFilter {
        override fun toJson() = buildJsonObject { put("filename", query) }
    }

    data class ListId(val query: String) : EmailFilter {
        override fun toJson() = buildJsonObject { put("listId", query) }
    }

    /**
     * The conversation's Gmail-style inbox category — what a tab contains.
     *
     * plMail's extension, and **thread-scoped**: it matches on the conversation's resolved category
     * rather than on each message's own, so a newsletter somebody replied to appears in one tab
     * rather than two. That is also why there is no per-message equivalent here and the server
     * refuses one.
     *
     * It has to be sent rather than applied on the device, and that is the whole reason this
     * condition exists. `Email/query` windows by position and limit: a client that asked for
     * twenty-five and kept the two that were Promotions would draw a nearly empty tab under a list
     * that had already reported its end, with no way to tell that from a genuinely quiet tab.
     *
     * Composes with [InMailbox] under [And], because categories are an inbox idea and the mailbox
     * is a separate question. A thread the server never classified matches nothing — deliberately,
     * and identically to plMail's own web inbox.
     */
    data class ThreadCategory(val category: String) : EmailFilter {
        init {
            require(category.isNotBlank()) {
                "threadCategory needs a category token; the server rejects anything it cannot name."
            }
        }

        override fun toJson() = buildJsonObject { put("threadCategory", category) }
    }

    // --- Operators ---

    data class And(val conditions: List<EmailFilter>) : EmailFilter {
        override fun toJson() = operator("AND", conditions)
    }

    data class Or(val conditions: List<EmailFilter>) : EmailFilter {
        override fun toJson() = operator("OR", conditions)
    }

    /**
     * Note the server implements this as `NOT (a OR b …)`, so a multi-condition NOT excludes
     * anything matching *any* of them, not only messages matching all of them.
     */
    data class Not(val conditions: List<EmailFilter>) : EmailFilter {
        override fun toJson() = operator("NOT", conditions)
    }

    private companion object {
        fun operator(op: String, conditions: List<EmailFilter>): JsonObject = buildJsonObject {
            put("operator", op)
            put("conditions", buildJsonArray { conditions.forEach { add(it.toJson()) } })
        }
    }
}

/**
 * The only four keywords plMail supports.
 *
 * `$seen` and `$flagged` are timestamp columns; `$draft` and `$answered` live in the IMAP flags
 * array. **Do not invent others** for client-side state — filtering on an unknown keyword raises
 * `unsupportedFilter`, and setting one will not round-trip.
 */
enum class Keyword(val wire: String) {
    SEEN("\$seen"),
    FLAGGED("\$flagged"),
    DRAFT("\$draft"),
    ANSWERED("\$answered");

    companion object {
        fun fromWire(value: String): Keyword? = entries.firstOrNull { it.wire == value }
    }
}
