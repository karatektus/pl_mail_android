package de.plmail.core.data

import de.plmail.jmap.mail.DraftComposer
import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.Signatures
import de.plmail.jmap.protocol.JmapError
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * The half of [ComposeRepository] a reply typed in the notification shade needs.
 *
 * A two-method seam for the reason every other one in this module is one — [DraftSender],
 * [KnownLabels], [SubmissionDirectory]: [ComposeRepository] reaches Room, OkHttp *and* the content
 * resolver, and a class whose whole job is "turn four strings into a draft" holding all of that is
 * a class nothing can exercise without standing up a server. The seam is also what lets the rules
 * below — which identity answers, what happens when there is nobody to answer — be pinned by tests
 * that run in milliseconds on every build, which matters because every one of them fails silently
 * and in someone else's mailbox.
 */
interface ReplySource {

    /**
     * Every address the user can send as, resolved once.
     *
     * Deliberately not named `identities`: [ComposeRepository] already publishes that as a `Flow`,
     * and Kotlin will not let one name carry two return types. The value here is read from the
     * cache, so it is available with no network at all — which is what makes "there is nobody to
     * send as" a real answer rather than a symptom of being offline.
     */
    suspend fun sendingIdentities(): List<SendIdentity>

    /** The message being answered, with the headers a reply threads against. */
    suspend fun original(accountKey: String, emailId: String): Email?
}

/**
 * What became of a reply typed in the shade.
 *
 * A result rather than an exception, and rather than `Boolean`. The caller has to put something in
 * front of the user for each of these and they are not the same thing: [NothingTyped] wants the
 * notification put back exactly as it was, [Sent] wants it taken away, and [NotSent] wants the
 * typed text kept somewhere the user can still reach it. Collapsing them would produce the one
 * outcome this whole path must never have — a reply that disappears and is never sent.
 */
sealed interface InlineReplyResult {

    /** Written, saved to Drafts, submitted. It is in Sent like any other message. */
    data object Sent : InlineReplyResult

    /**
     * The user sent an empty box, so nothing happened and nothing is wrong.
     *
     * Reachable in practice: the shade's own send button is disabled on an empty field, but a
     * whitespace-only reply passes it, and Wear and Auto hand over whatever the speech recogniser
     * heard — which is regularly nothing.
     */
    data object NothingTyped : InlineReplyResult

    data class NotSent(val reason: Reason) : InlineReplyResult

    /**
     * Why, in the only three shapes that lead anywhere different.
     *
     * The distinction that earns its keep is [OFFLINE] against the rest: it is the one where doing
     * exactly the same thing again in ten minutes works, so it is the one where offering "try
     * again" is honest rather than a button that will fail identically.
     */
    enum class Reason {
        /** Nothing answered. The reply is worth keeping and worth retrying. */
        OFFLINE,

        /**
         * The reply could not be *built*: no sendable identity, no recipient, or an original this
         * device could not read. Retrying changes none of that; opening the app might.
         */
        UNANSWERABLE,

        /** The server answered, and the answer was no. Retrying will be refused again. */
        REFUSED,
    }
}

/**
 * A reply written without the app ever coming to the foreground.
 *
 * ## It is the same send, not a second one
 *
 * Everything below builds a [ComposeDraft] and hands it to [SendQueue.sendNow], which is the same
 * save-then-submit that the composer's Send button reaches through [SendQueue.enqueue]. That is not
 * tidiness: the ordering [SendQueue] enforces — the draft exists on the server *before* anything
 * asks for it to leave — is the only reason a failed send leaves a message in Drafts rather than
 * nowhere, and a shade reply that submitted straight from memory would be the one path through this
 * app where a failure loses what somebody wrote. Sharing the queue also shares its lock, so a reply
 * tapped out on the lock screen while the composer is mid-send cannot collide with it and be
 * refused with `stateMismatch` — a rejection neither user action would explain.
 *
 * ## Reply, not reply-all
 *
 * [DraftComposer.reply] in [DraftComposer.ReplyMode.REPLY], so `Reply-To` still beats `From` and
 * the user is still struck out of their own recipient list. What is deliberately *dropped* is the
 * one thing that method also returns: the quoted original.
 *
 * ## Why there is no quote
 *
 * A quote is not free of consequence — it is text that goes out over the user's name — and the
 * composer treats it that way: it renders it below the cursor and puts a control on it to take it
 * off. The shade offers neither. Sending a forty-line quotation somebody never saw, on the strength
 * of them having typed "yes please", is sending something on their behalf that they did not read.
 * The person being answered has their own copy in any case, and every threading client files the
 * reply against it using `In-Reply-To`, which *is* carried. The signature is the opposite case and
 * is kept: it is a standing instruction the user gave once, it is short, and a reply arriving
 * without the sign-off every other message from that address has looks like it came from somewhere
 * else.
 *
 * ## What is *not* solved here
 *
 * Offline, this fails. [Outbox] queues mutations — archive, star, a label — and has never queued
 * sends; [SendQueue] reports a send it could not make as [SendState.Failed] and leaves the draft
 * with the caller. So a reply typed on the underground comes back as
 * [InlineReplyResult.Reason .OFFLINE] with the text intact, and it is the caller's job to keep it
 * somewhere the user can act on. Inventing a durable send queue here — for the shade only,
 * alongside a foreground path that has none — is exactly the second send path this class exists to
 * avoid.
 */
@Singleton
class InlineReplies
@Inject
constructor(
    private val source: ReplySource,
    private val sendQueue: SendQueue,
) {

    /**
     * Answers [emailId] with [text].
     *
     * Suspends until the server has taken the submission or refused it, because the caller has a
     * notification on screen saying "sending" and has to be told which way it went.
     */
    suspend fun send(accountKey: String, emailId: String, text: String): InlineReplyResult {
        val typed = text.trim()

        if (typed.isEmpty()) return InlineReplyResult.NothingTyped

        // Read before the original, and from the cache, so that "this account
        // can no longer send" is answered without a round trip and is never
        // reported as a network failure. The two have different remedies and
        // only one of them is "try again".
        val identities = source.sendingIdentities()

        if (identities.isEmpty()) return unanswerable()

        // Answering from the account the message arrived in, matching what the
        // composer does when Reply is tapped in the app -- replying to a work
        // mail from a private address is a mistake neither surface should make
        // on the user's behalf. The fallback to the first identity is the
        // composer's too, and is reached only for a message in an account that
        // has no sendable address of its own.
        val identity = identities.firstOrNull { it.accountKey == accountKey } ?: identities.first()

        val original =
            when (val fetched = fetchOriginal(accountKey, emailId)) {
                is Fetched.Offline -> return offline()
                is Fetched.Missing -> return unanswerable()
                is Fetched.Found -> fetched.email
            }

        val composed =
            DraftComposer.reply(
                original = original,
                mode = DraftComposer.ReplyMode.REPLY,
                self = identities.map { it.email }.toSet(),
                // Empty because `composed.quotedHtml` is thrown away -- see the
                // class note. Passing a localised attribution for a quote
                // nothing reads would mean this module owning a date format and
                // two translations to build a string it discards.
                attribution = "",
            )

        // Every address on the original was one of the user's own, which is what
        // replying to a message you sent yourself looks like. The composer shows
        // an empty To row and waits; there is no row here to wait on, and a
        // submission with no recipients is refused by the server in a way that
        // would read as "the reply failed" rather than "there was nobody to
        // reply to".
        if (composed.to.isEmpty()) return unanswerable()

        val draft =
            ComposeDraft(
                accountKey = identity.accountKey,
                identityId = identity.identityId,
                to = composed.to,
                subject = composed.subject.orEmpty(),
                bodyHtml = Signatures.replaceSignature(typed.asReplyHtml(), identity.htmlSignature),
                inReplyTo = composed.inReplyTo,
                references = composed.references,
            )

        return try {
            sendQueue.sendNow(draft)
            InlineReplyResult.Sent
        } catch (cancelled: CancellationException) {
            // Never reported as a failure. This is the process going away, not
            // the send being refused, and turning it into a "not sent"
            // notification would accuse the server of something the system did.
            throw cancelled
        } catch (offline: IOException) {
            offline()
        } catch (unreachable: JmapError.Unreachable) {
            offline()
        } catch (refused: Exception) {
            InlineReplyResult.NotSent(InlineReplyResult.Reason.REFUSED)
        }
    }

    /**
     * The original, with "nothing answered" told apart from "there is nothing there".
     *
     * A three-way answer rather than a nullable one, because [ComposeRepository.original] returns
     * null for an account this device has forgotten and *throws* when the server cannot be reached
     * — and those are the two failures whose remedies differ most.
     */
    private suspend fun fetchOriginal(accountKey: String, emailId: String): Fetched =
        try {
            source.original(accountKey, emailId)?.let(Fetched::Found) ?: Fetched.Missing
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (offline: IOException) {
            Fetched.Offline
        } catch (unreachable: JmapError.Unreachable) {
            Fetched.Offline
        } catch (refused: Exception) {
            // A server that answered something this client could not use. Not
            // retryable, so it is grouped with "missing" rather than with
            // "offline" -- the difference the caller acts on is whether trying
            // again is worth offering.
            Fetched.Missing
        }

    private sealed interface Fetched {
        data class Found(val email: Email) : Fetched

        data object Missing : Fetched

        data object Offline : Fetched
    }

    private fun offline() = InlineReplyResult.NotSent(InlineReplyResult.Reason.OFFLINE)

    private fun unanswerable() = InlineReplyResult.NotSent(InlineReplyResult.Reason.UNANSWERABLE)
}

/**
 * What was typed, as the HTML body plMail stores.
 *
 * Escaped rather than inserted. A reply reading `a < b` is ordinary English and would otherwise
 * open a tag that swallows the rest of the message, and a reply containing markup a phone keyboard
 * pasted in would be sent as markup. The server sanitises what it stores, but this string is put
 * into a draft and rendered by the composer and the reader long before it has been near a server —
 * and the escaping is what makes "the user's text goes out as the user's text" true rather than
 * mostly true.
 *
 * Line breaks become `<br>` inside a single paragraph, which is the same treatment [DraftComposer]
 * gives a plain-text body it has to quote. `\r\n` first, so a reply dictated or pasted from a
 * desktop clipboard does not gain a blank line per line.
 */
internal fun String.asReplyHtml(): String =
    "<p>" + replace("\r\n", "\n").escapeHtmlText().replace("\n", "<br>") + "</p>"

/**
 * The five characters that change how markup parses.
 *
 * A copy of the escaper in `:core:jmap` rather than a use of it: that one is `internal` to the
 * protocol module, and widening a module's API so a second module can escape five characters buys a
 * coupling worth more than the six lines it saves.
 */
private fun String.escapeHtmlText(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
