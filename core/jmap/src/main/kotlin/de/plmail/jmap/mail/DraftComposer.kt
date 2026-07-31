package de.plmail.jmap.mail

/**
 * Turns a message being answered into the starting point of the answer.
 *
 * Pure, and deliberately in the protocol module rather than in a feature: every rule here is about
 * RFC 5322 headers and how threading is decided from them, not about how a composer looks. It is
 * also the code most worth testing on the JVM in milliseconds, because all three of its interesting
 * failures — a broken thread, a doubled subject prefix, a recipient list that mails the sender
 * their own reply — are silent and only visible in someone else's client.
 *
 * Nothing here reads a string resource. The attribution line and the forwarded-message header
 * labels arrive as parameters, because this module must stay Android-free and the app ships in two
 * languages.
 */
object DraftComposer {

    /**
     * The reply prefixes that must not be stacked.
     *
     * German is a first-class language for this product, so `AW:` (Antwort) and `WG:` (Weitergabe)
     * are in the list beside the English ones — a reply from a German Outlook otherwise becomes
     * `Re: AW: Re: AW: …`, which is how a subject line ends up longer than the message. Matching is
     * case-insensitive and tolerates the `Re[2]:` form Outlook sometimes emits.
     */
    private val REPLY_PREFIX =
        Regex(
            """^\s*(?:(?:re|aw|antw|sv|vs|odp|ref)(?:\[\d+\])?\s*:\s*)+""",
            RegexOption.IGNORE_CASE,
        )

    private val FORWARD_PREFIX =
        Regex("""^\s*(?:(?:fwd?|wg|tr|rv|enc|vb)(?:\[\d+\])?\s*:\s*)+""", RegexOption.IGNORE_CASE)

    /**
     * How many References entries survive.
     *
     * RFC 5322 puts no ceiling on the header, but a hundred-message thread produces one several
     * kilobytes long, and MTAs do reject oversized headers. RFC 5537 §3.4.4 describes the
     * conventional trim, and it is the only safe one: the **first** id is what every threading
     * algorithm uses to find the root, so it is kept, and entries are dropped from the second
     * onwards. Trimming from the end instead would orphan the reply from the conversation it
     * belongs to, which is exactly the bug this whole file exists to avoid.
     */
    private const val MAX_REFERENCES = 20

    /** Recipients, headers and quoted body for a reply or a forward. */
    data class ComposedDraft(
        val to: List<EmailAddress> = emptyList(),
        val cc: List<EmailAddress> = emptyList(),
        val subject: String? = null,
        val inReplyTo: List<String>? = null,
        val references: List<String>? = null,
        /** The original, quoted, ready to be appended below whatever the user types. */
        val quotedHtml: String = "",
    )

    /** Labels for the block a forward puts above the original. */
    data class ForwardLabels(
        val heading: String,
        val from: String,
        val date: String,
        val subject: String,
        val to: String,
        val cc: String,
    )

    enum class ReplyMode {
        REPLY,
        REPLY_ALL,
    }

    /**
     * A reply to [original].
     *
     * [self] is every address this user can send as, lowercased by the caller or not — comparison
     * normalises. Without it a reply-all mails the user their own reply, which every client
     * eventually gets a bug report about.
     *
     * [attribution] is the "On <date>, <name> wrote:" line, already formatted and localised.
     */
    fun reply(
        original: Email,
        mode: ReplyMode,
        self: Set<String>,
        attribution: String,
    ): ComposedDraft {
        // Reply-To wins over From when the sender set one: that is the entire
        // purpose of the header, and mailing lists depend on it.
        val answerTo = original.replyTo.ifEmpty { original.from }
        val exclude = self.map { it.normaliseAddress() }.toSet()

        val to = answerTo.deduplicateAddresses(exclude)

        val cc =
            when (mode) {
                ReplyMode.REPLY -> emptyList()
                // Everyone who was on the original, minus the people already in
                // To and minus this user. The original To goes to Cc rather than
                // To: the person being answered is the one addressed, and the
                // rest were, and stay, copied in.
                ReplyMode.REPLY_ALL ->
                    (original.to + original.cc).deduplicateAddresses(
                        exclude + to.mapNotNull { it.email?.normaliseAddress() }
                    )
            }

        return ComposedDraft(
            to = to,
            cc = cc,
            subject = replySubject(original.subject),
            inReplyTo = original.messageIdHeader()?.let { listOf(it) },
            references = referencesFor(original),
            quotedHtml = quote(attribution, original.bodyForQuoting()),
        )
    }

    /**
     * A forward of [original].
     *
     * No recipients and no reply headers: a forward starts a new conversation, and carrying
     * `In-Reply-To` into it files the forward inside the thread it was taken out of — in the
     * recipient's mailbox, where the sender never sees it.
     */
    fun forward(original: Email, labels: ForwardLabels, sentAt: String): ComposedDraft =
        ComposedDraft(
            subject = forwardSubject(original.subject),
            quotedHtml = forwardBlock(original, labels, sentAt),
        )

    /**
     * `Re: ` exactly once, whatever the original carried.
     *
     * A forwarded message being replied to keeps neither prefix stacked: `Fwd: x` becomes `Re: x`,
     * which is what Gmail and Thunderbird both do and what keeps the two threads distinguishable.
     */
    fun replySubject(subject: String?): String {
        val stripped = subject.orEmpty().stripPrefixes()

        return if (stripped.isEmpty()) "Re:" else "Re: $stripped"
    }

    fun forwardSubject(subject: String?): String {
        val stripped = subject.orEmpty().stripPrefixes()

        return if (stripped.isEmpty()) "Fwd:" else "Fwd: $stripped"
    }

    private fun String.stripPrefixes(): String {
        var value = trim()
        var changed = true

        // Repeated, because the two prefixes interleave: "Re: Fwd: Re: x" needs
        // three passes and one regex can only strip runs of its own kind.
        while (changed) {
            val next = value.replaceFirst(REPLY_PREFIX, "").replaceFirst(FORWARD_PREFIX, "").trim()
            changed = next != value
            value = next
        }

        return value
    }

    /**
     * The References header for a reply: the original's own chain, then the original itself.
     *
     * Returning null rather than an empty list matters — a draft with `references: []` and one with
     * no references at all are the same thing to the server, but null is what tells [DraftEmail] to
     * omit the key entirely.
     */
    private fun referencesFor(original: Email): List<String>? {
        val chain = original.references.orEmpty() + listOfNotNull(original.messageIdHeader())
        val unique = chain.filter { it.isNotBlank() }.distinct()

        if (unique.isEmpty()) return null
        if (unique.size <= MAX_REFERENCES) return unique

        return listOf(unique.first()) + unique.takeLast(MAX_REFERENCES - 1)
    }

    /**
     * The Message-ID to thread against.
     *
     * JMAP models `messageId` as a list because the header can technically repeat; the last entry
     * is the one that identifies this message. A **draft has none** — plMail assigns the id when
     * the message is actually sent — so replying to an unsent draft cannot thread, and the caller
     * gets null rather than a fabricated id. Inventing one here would produce a reply that threads
     * against a message that will never exist.
     */
    private fun Email.messageIdHeader(): String? =
        messageId?.lastOrNull { it.isNotBlank() }?.trim()?.trim('<', '>')

    /**
     * The body to quote, preferring HTML.
     *
     * A plain-text body is escaped and wrapped rather than inserted: a message whose text happens
     * to contain `<b>` would otherwise start rendering as markup inside the quote, and a message
     * containing a `<script>` would do worse. The server sanitises what it stores, but the composer
     * renders this string locally before anything has been near the server.
     */
    private fun Email.bodyForQuoting(): String =
        htmlContent ?: textContent?.let { "<p>${it.escapeHtml().replace("\n", "<br>")}</p>" } ?: ""

    private fun quote(attribution: String, body: String): String = buildString {
        append("<p>")
        append(attribution.escapeHtml())
        append("</p>")
        // A real blockquote rather than "> " prefixes: the receiving client
        // collapses it, and plMail's own reader styles it.
        append(
            "<blockquote type=\"cite\" style=\"margin:0 0 0 0.8ex;border-left:1px solid #ccc;padding-left:1ex\">"
        )
        append(body)
        append("</blockquote>")
    }

    private fun forwardBlock(original: Email, labels: ForwardLabels, sentAt: String): String =
        buildString {
            append("<p>---------- ")
            append(labels.heading.escapeHtml())
            append(" ----------</p>")
            append("<p>")
            appendHeader(labels.from, original.from.joinToString(", ") { it.nameAndAddress() })
            appendHeader(labels.date, sentAt)
            appendHeader(labels.subject, original.subject.orEmpty())
            appendHeader(labels.to, original.to.joinToString(", ") { it.nameAndAddress() })

            if (original.cc.isNotEmpty()) {
                appendHeader(labels.cc, original.cc.joinToString(", ") { it.nameAndAddress() })
            }

            append("</p>")
            append(original.bodyForQuoting())
        }

    private fun StringBuilder.appendHeader(label: String, value: String) {
        if (value.isBlank()) return

        append("<strong>")
        append(label.escapeHtml())
        append(":</strong> ")
        append(value.escapeHtml())
        append("<br>")
    }
}

/**
 * De-duplicates on the *address*, keeping the first spelling of the name.
 *
 * Case-insensitively, because `Anna@example.org` and `anna@example.org` are one mailbox and a
 * reply-all that lists both sends two copies. The name is deliberately not part of the key: the
 * same person appears as "Anna" in one header and "Anna Meyer" in another within a single thread.
 */
internal fun List<EmailAddress>.deduplicateAddresses(exclude: Set<String>): List<EmailAddress> {
    val seen = mutableSetOf<String>()

    return filter { address ->
        val key = address.email?.normaliseAddress() ?: return@filter false

        key.isNotEmpty() && key !in exclude && seen.add(key)
    }
}

internal fun String.normaliseAddress(): String = trim().lowercase()

/** The `Name <address>` form, falling back to the bare address. */
internal fun EmailAddress.nameAndAddress(): String {
    val address = email.orEmpty()

    return when {
        name.isNullOrBlank() -> address
        address.isBlank() -> name.orEmpty()
        else -> "$name <$address>"
    }
}

/**
 * Escapes the five characters that change how markup parses.
 *
 * Quotes included: these strings end up inside attribute-adjacent positions often enough that
 * leaving them raw is a habit worth not forming.
 */
internal fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
