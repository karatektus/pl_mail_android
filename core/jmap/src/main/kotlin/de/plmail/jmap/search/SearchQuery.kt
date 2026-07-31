package de.plmail.jmap.search

import de.plmail.jmap.mail.MailboxRole
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * A search string, parsed.
 *
 * Mirrors `App\Domain\DTO\ParsedSearchQuery` field for field, because the three clients have to
 * agree about what a query means: someone who searches `from:acme is:unread` in the browser and
 * then on their phone is entitled to the same results. [SearchQuery.parse] is a deliberate
 * reimplementation of the server's `Service\Search\SearchQueryParser`, including the parts that
 * look like bugs.
 *
 * Nothing here knows about an account — a query is one thing, and the mailbox ids `in:` eventually
 * needs are per-account. Resolving those is [SearchQueryCompiler]'s job.
 */
data class SearchQuery(
    val from: String? = null,
    val to: String? = null,
    val subject: String? = null,
    val hasAttachment: Boolean = false,
    /**
     * `is:unread` and `is:read` are independent flags, not one tri-state.
     *
     * Setting both is contradictory and matches nothing, on every client. That is reproduced rather
     * than resolved: guessing which one the user "meant" would make the phone disagree with the web
     * UI about the same string.
     */
    val isUnread: Boolean = false,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    /** The role `in:` named, if it named one this client knows. */
    val mailbox: MailboxRole? = null,
    /** Inclusive — see [SearchQueryCompiler]. */
    val after: Instant? = null,
    /** Strict. */
    val before: Instant? = null,
    /**
     * Everything that was not an operator, joined with single spaces.
     *
     * Quote characters survive tokenising deliberately: this string is handed to Postgres's
     * `websearch_to_tsquery`, where a quoted run means a phrase search. Stripping them would
     * quietly turn a phrase into a bag of words.
     */
    val freeText: String = "",
) {

    /** Whether anything was actually asked for. */
    val isEmpty: Boolean
        get() = this == SearchQuery()

    /**
     * Whether the query narrowed by date.
     *
     * Worth knowing for the empty state: mail older than an account's sync window is not in the
     * database at all, so "no results" for a dated query usually means the window rather than the
     * mail.
     */
    val hasDateBound: Boolean
        get() = after != null || before != null

    companion object {

        /**
         * Parses a Gmail-style search string. It cannot fail.
         *
         * The behaviours worth naming, because each is a decision the server made and this has to
         * make the same one:
         * - **An unknown operator falls through as free text, colon and all** — `weird:thing`
         *   searches for the literal `weird:thing`. A search box that rejects what someone typed is
         *   worse than one that searches for it.
         * - **A token splits on its first colon only**, so `subject:a:b` searches subjects for
         *   `a:b`.
         * - **Quote characters are kept inside tokens** and stripped only from an operator's
         *   *value*; see [freeText].
         * - **An unusable value for `has:`, `is:`, `in:`, `after:` or `before:` is dropped
         *   silently**, not turned into free text. `is:bogus` narrows nothing and searches for
         *   nothing.
         * - **The last occurrence of an operator wins**, and for `in:`/`after:`/ `before:` an
         *   unusable value clears an earlier good one, matching the server's unconditional
         *   assignment.
         *
         * @param clock supplies both "now" for `today`/`yesterday` and the zone a bare date is
         *   anchored in. Injected so tests do not depend on the machine's zone.
         */
        fun parse(raw: String, clock: Clock = Clock.systemDefaultZone()): SearchQuery {
            var query = SearchQuery()
            val remainder = mutableListOf<String>()

            for (token in tokenize(raw.trim())) {
                val colon = token.indexOf(':')

                if (colon < 0) {
                    remainder += token
                    continue
                }

                val operator = token.substring(0, colon).trim().lowercase()
                // Both quote flavours, either end: the tokeniser only tracks
                // `"`, so `'` reaches here untouched and would otherwise become
                // part of the search term.
                val value = token.substring(colon + 1).trim('"', '\'')

                query =
                    when (operator) {
                        "from" -> query.copy(from = value)
                        "to" -> query.copy(to = value)
                        "subject" -> query.copy(subject = value)
                        "has" -> query.withHas(value)
                        "is" -> query.withIs(value)
                        "in" -> query.copy(mailbox = MAILBOX_ROLES[value.lowercase()])
                        "after" -> query.copy(after = parseDate(value, clock))
                        "before" -> query.copy(before = parseDate(value, clock))
                        else -> {
                            // The whole original token, colon included.
                            remainder += token
                            query
                        }
                    }
            }

            // One `text` condition rather than one per fragment: full-text
            // search ranks a phrase as a unit, and N separate conditions would
            // be ANDed into "every word, anywhere", losing that ranking.
            return query.copy(freeText = remainder.joinToString(" ").trim())
        }

        /**
         * Splits on spaces, keeping quoted runs together.
         *
         * Only the space character separates, as on the server — a tab stays inside its token. An
         * unterminated quote swallows the rest of the string, which is both what the server does
         * and the more forgiving reading of a half-typed query.
         */
        private fun tokenize(input: String): List<String> {
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var inQuote = false

            for (char in input) {
                when {
                    char == '"' -> {
                        inQuote = !inQuote
                        current.append(char)
                    }
                    char == ' ' && !inQuote -> {
                        if (current.isNotEmpty()) {
                            tokens += current.toString()
                            current.clear()
                        }
                    }
                    else -> current.append(char)
                }
            }

            if (current.isNotEmpty()) tokens += current.toString()

            return tokens
        }

        /**
         * Parses a date the way a person writes one.
         *
         * The server uses PHP's `DateTimeImmutable`, which accepts an enormous range including
         * relative phrases. This covers the formats the docs promise, the German ordering this
         * audience types, and the two relative words people reach for; anything else is dropped
         * exactly as an unparseable date is dropped there.
         *
         * A bare date is anchored at **local midnight**, because a typed date means that day where
         * the person is. The server anchors in *its* zone, which can differ — but that is ambiguous
         * there too, since the same query from the same person already means different things
         * depending on where their server thinks it is. An explicit offset (`2024-01-01T09:00Z`) is
         * taken as written and is the way to be unambiguous.
         */
        private fun parseDate(raw: String, clock: Clock): Instant? {
            val value = raw.trim()

            return when {
                value.isEmpty() -> null
                value.equals("today", ignoreCase = true) -> startOfDay(LocalDate.now(clock), clock)
                value.equals("yesterday", ignoreCase = true) ->
                    startOfDay(LocalDate.now(clock).minusDays(1), clock)
                else ->
                    parseWithOffset(value)
                        ?: parseLocalDateTime(value, clock)
                        ?: parseLocalDate(value, clock)
            }
        }

        private fun startOfDay(date: LocalDate, clock: Clock): Instant =
            date.atStartOfDay(clock.zone).toInstant()

        private fun parseWithOffset(value: String): Instant? =
            try {
                OffsetDateTime.parse(value).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }

        private fun parseLocalDateTime(value: String, clock: Clock): Instant? {
            for (format in DATE_TIME_FORMATS) {
                try {
                    return LocalDateTime.parse(value, format).atZone(clock.zone).toInstant()
                } catch (_: DateTimeParseException) {
                    // Try the next shape.
                }
            }
            return null
        }

        private fun parseLocalDate(value: String, clock: Clock): Instant? {
            for (format in DATE_FORMATS) {
                try {
                    return startOfDay(LocalDate.parse(value, format), clock)
                } catch (_: DateTimeParseException) {
                    // Try the next shape.
                }
            }
            return null
        }

        /**
         * `u` rather than `y`, and single letters throughout.
         *
         * `y` is year-of-era and needs an era to resolve strictly; `u` is the proleptic year and
         * needs nothing. Single-letter fields accept one *or* two digits, so `2024-1-5` parses as
         * readily as `2024-01-05` — the separators make it unambiguous, and rejecting it would be
         * pedantry aimed at someone typing into a search box.
         */
        private val DATE_FORMATS =
            listOf("u-M-d", "u/M/d", "d.M.u").map(DateTimeFormatter::ofPattern)

        private val DATE_TIME_FORMATS =
            listOf("u-M-d H:m:s", "u-M-d H:m", "u-M-d'T'H:m:s", "u-M-d'T'H:m")
                .map(DateTimeFormatter::ofPattern)

        /**
         * What `in:` accepts, and the role each name means.
         *
         * Spelled out rather than derived from [MailboxRole.fromWire] for two reasons. The server's
         * own role map takes `draft` and `spam` as synonyms, so a query written in the browser must
         * keep working here. And [MailboxRole] carries entries — `flagged`, `important`, `all` —
         * that the search syntax does not offer; accepting them would silently make the phone
         * answer a query the web UI ignores.
         */
        private val MAILBOX_ROLES =
            mapOf(
                "inbox" to MailboxRole.INBOX,
                "sent" to MailboxRole.SENT,
                "drafts" to MailboxRole.DRAFTS,
                "draft" to MailboxRole.DRAFTS,
                "trash" to MailboxRole.TRASH,
                "archive" to MailboxRole.ARCHIVE,
                "junk" to MailboxRole.JUNK,
                "spam" to MailboxRole.JUNK,
            )
    }
}

/** Set-only, like the server's: `has:bogus` after `has:attachment` leaves it set. */
private fun SearchQuery.withHas(value: String): SearchQuery =
    when (value.lowercase()) {
        "attachment",
        "attachments" -> copy(hasAttachment = true)
        else -> this
    }

private fun SearchQuery.withIs(value: String): SearchQuery =
    when (value.lowercase()) {
        "unread" -> copy(isUnread = true)
        "read" -> copy(isRead = true)
        "starred" -> copy(isStarred = true)
        else -> this
    }
