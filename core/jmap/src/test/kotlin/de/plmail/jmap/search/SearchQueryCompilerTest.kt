package de.plmail.jmap.search

import de.plmail.jmap.mail.EmailFilter
import de.plmail.jmap.mail.Keyword
import de.plmail.jmap.mail.Mailbox
import de.plmail.jmap.mail.MailboxRole
import de.plmail.jmap.protocol.MailboxId
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SearchQueryCompilerTest {

    private val utc = Clock.fixed(Instant.parse("2026-07-31T09:00:00Z"), ZoneOffset.UTC)
    private val berlin =
        Clock.fixed(Instant.parse("2026-07-31T09:00:00Z"), ZoneId.of("Europe/Berlin"))

    private val inbox = MailboxId("11")
    private val everyRole: (MailboxRole) -> MailboxId? = { inbox }
    private val noMailboxes: (MailboxRole) -> MailboxId? = { null }

    @Test
    fun `a query that asked for nothing matches everything`() {
        // Distinct from "no results": the caller queries the account unfiltered.
        assertEquals(CompiledSearch.MatchesEverything, compile(""))
    }

    @Test
    fun `an operator with no value is dropped rather than matching everything`() {
        // An empty `from` filter matches every message; sending it would count
        // as a narrowed search that narrowed nothing.
        assertEquals(CompiledSearch.MatchesEverything, compile("from:"))
    }

    @Test
    fun `a single condition is not wrapped in an And`() {
        assertEquals(EmailFilter.From("alice"), filterOf(compile("from:alice")))
    }

    @Test
    fun `free text fragments become one text condition rather than several`() {
        // One `text` per fragment would be ANDed into "every word, anywhere",
        // throwing away the phrase ranking full-text search exists for.
        assertEquals(
            EmailFilter.Text("quarterly report draft"),
            filterOf(compile("quarterly report draft")),
        )
    }

    @Test
    fun `each operator compiles to the condition the server understands`() {
        assertEquals(EmailFilter.To("bob"), filterOf(compile("to:bob")))
        assertEquals(EmailFilter.Subject("invoice"), filterOf(compile("subject:invoice")))
        assertEquals(EmailFilter.HasAttachment(true), filterOf(compile("has:attachment")))
        assertEquals(EmailFilter.NotKeyword(Keyword.SEEN), filterOf(compile("is:unread")))
        assertEquals(EmailFilter.HasKeyword(Keyword.SEEN), filterOf(compile("is:read")))
        assertEquals(EmailFilter.HasKeyword(Keyword.FLAGGED), filterOf(compile("is:starred")))
    }

    @Test
    fun `in compiles to this account's binding for that role`() {
        val sent = MailboxId("7")
        val resolve: (MailboxRole) -> MailboxId? = { role ->
            if (role == MailboxRole.SENT) sent else null
        }

        assertEquals(EmailFilter.InMailbox(sent), filterOf(compile("in:sent", resolve = resolve)))
    }

    @Test
    fun `an unresolvable in role matches nothing rather than dropping the condition`() {
        // The dangerous case: an account whose role cannot be resolved would
        // otherwise answer `in:trash is:unread` with all its unread mail.
        val result = compile("in:trash is:unread", resolve = noMailboxes)

        assertEquals(CompiledSearch.MatchesNothing, result)
    }

    @Test
    fun `in archive means not in the inbox, not the Archive label`() {
        // Archiving in this product removes the Inbox label and adds nothing:
        // ThreadStatusController::archive does one label mutation,
        // removeLabel(inbox). The Archive binding it touches only re-points an
        // IMAP folder pointer for plain-IMAP messages.
        //
        // Filtering on the Archive binding therefore finds only plain-IMAP mail
        // sitting in an Archive folder and returns nothing at all for Gmail or
        // Outlook, where archived mail carries no location label — which is the
        // case most users are in.
        val mailboxes =
            listOf(
                Mailbox(id = MailboxId("1"), name = "Inbox", role = "inbox"),
                Mailbox(id = MailboxId("9"), name = "Archive", role = "archive"),
            )

        val filter =
            filterOf(compile("in:archive", resolve = SearchQueryCompiler.resolver(mailboxes)))

        assertEquals(EmailFilter.Not(listOf(EmailFilter.InMailbox(MailboxId("1")))), filter)
    }

    @Test
    fun `in archive needs the inbox binding, not the archive one`() {
        // An account with an Archive label but no resolvable Inbox cannot
        // answer the question, and must say so rather than returning
        // everything.
        val archiveOnly =
            SearchQueryCompiler.resolver(
                listOf(Mailbox(id = MailboxId("9"), name = "Archive", role = "archive"))
            )

        assertEquals(CompiledSearch.MatchesNothing, compile("in:archive", resolve = archiveOnly))
    }

    @Test
    fun `every condition is ANDed together, in a stable order`() {
        val filter =
            filterOf(
                compile(
                    "urgent from:alice to:bob subject:report has:attachment is:unread " +
                        "is:starred in:inbox after:2024-01-01 before:2024-02-01",
                    resolve = everyRole,
                )
            )

        assertEquals(
            EmailFilter.And(
                listOf(
                    EmailFilter.From("alice"),
                    EmailFilter.To("bob"),
                    EmailFilter.Subject("report"),
                    EmailFilter.HasAttachment(true),
                    EmailFilter.NotKeyword(Keyword.SEEN),
                    EmailFilter.HasKeyword(Keyword.FLAGGED),
                    EmailFilter.InMailbox(inbox),
                    EmailFilter.After("2024-01-01T00:00:00Z"),
                    EmailFilter.Before("2024-02-01T00:00:00Z"),
                    EmailFilter.Text("urgent"),
                )
            ),
            filter,
        )
    }

    @Test
    fun `a contradictory read state compiles rather than being resolved`() {
        assertEquals(
            EmailFilter.And(
                listOf(
                    EmailFilter.NotKeyword(Keyword.SEEN),
                    EmailFilter.HasKeyword(Keyword.SEEN),
                )
            ),
            filterOf(compile("is:unread is:read")),
        )
    }

    @Test
    fun `dates render as second-precision utc date-times`() {
        // JMAP's date-time production wants seconds and a Z; a bare typed date
        // carries no sub-second precision worth printing.
        assertEquals(
            EmailFilter.After("2024-01-01T00:00:00Z"),
            filterOf(compile("after:2024-01-01")),
        )

        // Same string typed in Berlin means local midnight, an hour earlier.
        assertEquals(
            EmailFilter.After("2023-12-31T23:00:00Z"),
            filterOf(compile("after:2024-01-01", clock = berlin)),
        )
    }

    @Test
    fun `the mailbox resolver finds the binding carrying that role`() {
        val mailboxes =
            listOf(
                Mailbox(id = MailboxId("1"), name = "Work", role = null),
                Mailbox(id = MailboxId("2"), name = "Trash", role = "trash"),
            )
        val resolve = SearchQueryCompiler.resolver(mailboxes)

        assertEquals(MailboxId("2"), resolve(MailboxRole.TRASH))
        assertNull(resolve(MailboxRole.ARCHIVE), "no binding means this account cannot answer it")
    }

    private fun compile(
        raw: String,
        clock: Clock = utc,
        resolve: (MailboxRole) -> MailboxId? = noMailboxes,
    ): CompiledSearch = SearchQueryCompiler.compile(SearchQuery.parse(raw, clock), resolve)

    private fun filterOf(result: CompiledSearch): EmailFilter {
        assertIs<CompiledSearch.Filter>(result)
        return result.filter
    }
}
