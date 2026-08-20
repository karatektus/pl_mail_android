package de.plmail.feature.compose

import de.plmail.jmap.mail.EmailAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The header, folded into one line.
 *
 * The line is the only thing on screen while somebody writes the message, so every question it can
 * be wrong about is a question about whether the mail is going where they think it is: a reply-all
 * that reads like a reply, a count that leaves the Cc list out, a person named twice. Those are all
 * string cases, which is why the formatting is a pure function and this is a plain JVM test rather
 * than a screenshot.
 */
class ComposeSummaryTest {

    @Test
    fun `nobody to name leaves the recipient mark out entirely`() {
        // Not reachable from the screen -- the header only folds once there is a
        // recipient -- but the alternative to handling it is an addressing
        // reading "me › ", which looks like a rendering fault rather than a
        // draft with an empty To line.
        val summary = summary(recipients = emptyList())

        assertEquals("me@example.org", summary.addressing)
    }

    @Test
    fun `one recipient is named`() {
        val summary = summary(recipients = listOf(person("Katrin Vogel", "katrin@example.org")))

        assertEquals("me@example.org › Katrin Vogel", summary.addressing)
        assertEquals("Nebenkosten", summary.subject)
    }

    @Test
    fun `somebody with no display name is named by their address`() {
        // The address rather than nothing: a summary that silently dropped the
        // recipients it had no name for would under-report who the mail reaches.
        val summary = summary(recipients = listOf(person(null, "buchhaltung@example.org")))

        assertEquals("me@example.org › buchhaltung@example.org", summary.addressing)
    }

    @Test
    fun `more people than fit are counted rather than dropped`() {
        val summary =
            summary(
                recipients =
                    listOf(
                        person("Katrin Vogel", "katrin@example.org"),
                        person("Anna Meyer", "anna@example.org"),
                        person("Bob", "bob@example.org"),
                        person(null, "carla@example.org"),
                    )
            )

        assertEquals("me@example.org › Katrin Vogel, Anna Meyer", summary.addressing)
        // Held apart from the names because the row draws it apart from them:
        // inside the addressing it was the first thing an ellipsis ate.
        assertEquals("+2", summary.more)
    }

    @Test
    fun `everybody fitting means there is nothing to count`() {
        val summary = summary(recipients = listOf(person("Katrin Vogel", "katrin@example.org")))

        assertEquals("", summary.more)
    }

    @Test
    fun `a draft with no subject yet says so`() {
        val summary =
            summary(
                recipients = listOf(person("Katrin Vogel", "katrin@example.org")),
                subject = "   ",
            )

        assertEquals("(no subject)", summary.subject)
    }

    // ------------------------------------------------------------ recipients

    @Test
    fun `Cc and Bcc are counted with To`() {
        // The whole point of the overflow number. A summary reading "+0" over a
        // message going to seven people because six of them are on Cc would be
        // the composer misreporting the send.
        val summarised =
            summariseRecipients(
                to = listOf(person("Katrin Vogel", "katrin@example.org")),
                cc = listOf(person("Anna Meyer", "anna@example.org")),
                bcc = listOf(person(null, "carla@example.org"), person(null, "dora@example.org")),
            )

        assertEquals(listOf("Katrin Vogel", "Anna Meyer"), summarised.names)
        assertEquals(2, summarised.more)
    }

    @Test
    fun `somebody on both To and Cc is one person`() {
        val summarised =
            summariseRecipients(
                to = listOf(person("Katrin Vogel", "katrin@example.org")),
                // The same address the server would fold together anyway, spelled
                // the way a paste from another client spells it.
                cc = listOf(person("K. Vogel", "Katrin@Example.org")),
            )

        assertEquals(listOf("Katrin Vogel"), summarised.names)
        assertEquals(0, summarised.more)
    }

    @Test
    fun `To is named before Cc, so the addressees are the ones that survive the cut`() {
        val summarised =
            summariseRecipients(
                to = listOf(person("Katrin Vogel", "katrin@example.org")),
                cc =
                    listOf(
                        person("Anna Meyer", "anna@example.org"),
                        person("Bob", "bob@example.org"),
                    ),
            )

        assertEquals(listOf("Katrin Vogel", "Anna Meyer"), summarised.names)
        assertEquals(1, summarised.more)
    }

    // -- plumbing ------------------------------------------------------------

    private fun summary(
        recipients: List<EmailAddress>,
        subject: String = "Nebenkosten",
    ): ComposeSummary =
        composeSummary(
            from = "me@example.org",
            recipients = summariseRecipients(to = recipients),
            subject = subject,
            noSubject = "(no subject)",
        )

    /**
     * The sender is omitted where naming it would say nothing, and then the mark goes with it.
     *
     * `ComposeUiState.summaryNamesSender` decides *whether* — one account with one alias can only
     * ever send from itself — and this is the other half: a summary that still drew the `›` would
     * open with a mark pointing at nothing.
     */
    @Test
    fun `an unnamed sender takes its mark with it`() {
        val summary =
            composeSummary(
                from = "",
                recipients = summariseRecipients(to = listOf(person("Katrin Vogel", "k@x.de"))),
                subject = "Nebenkosten",
                noSubject = "(no subject)",
            )

        assertEquals("Katrin Vogel", summary.addressing)
        assertEquals("Nebenkosten", summary.subject)
    }

    @Test
    fun `neither half is a blank line rather than a stray mark`() {
        val summary =
            composeSummary(
                from = "",
                recipients = summariseRecipients(to = emptyList()),
                subject = "",
                noSubject = "(no subject)",
            )

        assertEquals("", summary.addressing)
        assertEquals("(no subject)", summary.subject)
    }

    private fun person(name: String?, email: String) = EmailAddress(name = name, email = email)
}
