package de.plmail.jmap.search

import de.plmail.jmap.mail.EmailFilter
import de.plmail.jmap.mail.Keyword
import de.plmail.jmap.mail.Mailbox
import de.plmail.jmap.mail.MailboxRole
import de.plmail.jmap.protocol.MailboxId
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What a [SearchQuery] means *in one account*.
 *
 * Three outcomes rather than a nullable filter, because "filter nothing" and "this account cannot
 * answer this query" are opposite instructions that a null would merge: a dropped `inMailbox`
 * condition returns the account's entire mailbox as if it were the search result. Exhaustive `when`
 * at every call site is the point.
 */
sealed interface CompiledSearch {

    /** Pass to `Email/query` as its filter. */
    data class Filter(val filter: EmailFilter) : CompiledSearch

    /** The query asked for nothing; query the account unfiltered. */
    data object MatchesEverything : CompiledSearch

    /**
     * `in:` named a role this account has no binding for. Skip the account — do not query it
     * unfiltered.
     */
    data object MatchesNothing : CompiledSearch
}

/**
 * Turns a parsed query into a JMAP filter, per account.
 *
 * Per account because `in:` names a *role*, and a role resolves to a different binding id in every
 * account — there is no cross-account mailbox. An account missing the requested role is skipped
 * rather than errored on: a freshly added account has no Archive binding until one exists, and
 * refusing the whole search over that would be absurd.
 */
object SearchQueryCompiler {

    fun compile(query: SearchQuery, resolveMailbox: (MailboxRole) -> MailboxId?): CompiledSearch {
        val conditions = mutableListOf<EmailFilter>()

        // Empty values are skipped: a bare `from:` sets an empty string, and an
        // empty `from` filter matches every message, which is indistinguishable
        // from not having typed the operator — except that it would also count
        // as "the user narrowed the search".
        query.from?.ifEmpty { null }?.let { conditions += EmailFilter.From(it) }
        query.to?.ifEmpty { null }?.let { conditions += EmailFilter.To(it) }
        query.subject?.ifEmpty { null }?.let { conditions += EmailFilter.Subject(it) }

        if (query.hasAttachment) conditions += EmailFilter.HasAttachment(true)

        // Read state is the `seen_at` column, exposed as the `$seen` keyword.
        // Both flags set is contradictory and returns nothing — see SearchQuery.
        if (query.isUnread) conditions += EmailFilter.NotKeyword(Keyword.SEEN)
        if (query.isRead) conditions += EmailFilter.HasKeyword(Keyword.SEEN)
        if (query.isStarred) conditions += EmailFilter.HasKeyword(Keyword.FLAGGED)

        query.mailbox?.let { role ->
            val mailboxId = resolveMailbox(role) ?: return CompiledSearch.MatchesNothing
            conditions += EmailFilter.InMailbox(mailboxId)
        }

        // `after` is inclusive and `before` is strict, matching the server's
        // `received_at >= :after` / `received_at < :before`. So a day-long
        // window is `after:2024-05-01 before:2024-05-02`.
        query.after?.let { conditions += EmailFilter.After(utc(it)) }
        query.before?.let { conditions += EmailFilter.Before(utc(it)) }

        // Real full-text search — a Postgres tsvector matched with
        // websearch_to_tsquery, so this is stemmed and ranked rather than a
        // substring scan, and quoted runs stay phrases.
        query.freeText.ifEmpty { null }?.let { conditions += EmailFilter.Text(it) }

        return when (conditions.size) {
            0 -> CompiledSearch.MatchesEverything
            // Not wrapped in a one-element And: the server accepts either, and
            // the bare condition is what a reader of the request sees.
            1 -> CompiledSearch.Filter(conditions.first())
            else -> CompiledSearch.Filter(EmailFilter.And(conditions.toList()))
        }
    }

    /**
     * The obvious resolver over one account's mailboxes.
     *
     * First match wins. plMail gives an account at most one binding per system role, but the type
     * does not promise that, and picking arbitrarily beats either failing or silently searching two
     * mailboxes.
     */
    fun resolver(mailboxes: List<Mailbox>): (MailboxRole) -> MailboxId? = { role ->
        mailboxes.firstOrNull { it.knownRole == role }?.id
    }

    /**
     * Written out rather than `ISO_INSTANT`, whose width follows the value's precision: an instant
     * with nanoseconds prints a fractional part, and one on an exact minute may print none of the
     * seconds JMAP's `date-time` requires. Sub-second precision is meaningless for a typed date
     * anyway.
     */
    private val UTC_DATE_TIME =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    private fun utc(instant: Instant): String = UTC_DATE_TIME.format(instant)
}
