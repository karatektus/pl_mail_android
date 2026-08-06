package de.plmail.jmap.methods

import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.IdentityId
import de.plmail.jmap.protocol.JmapMethod
import de.plmail.jmap.protocol.MailboxId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `EmailSubmission/set` — actually sending, now or at a time the client picks.
 *
 * Sending is queued on the same message bus the web composer uses, and that pipeline performs the
 * whole draft-to-sent transition itself: it adds Sent, removes Drafts, clears the draft flag, sets
 * the sent timestamp and re-points the mailbox. A client that omits `onSuccessUpdateEmail` still
 * ends up correct — the argument is there for clients that want the transition expressed
 * explicitly.
 *
 * Three things worth knowing:
 *
 * A submission **has no table of its own — its id is the Email id**, because plMail sends each
 * draft at most once and the mapping stays one-to-one.
 *
 * **The web UI's undo-send grace period is not applied for you.** A submission with no hold on it
 * asks to send now. A client that wants a window says so, with [SendHold] — see below.
 *
 * **A hold is a real server-side delay, and it is the only kind that survives the app being
 * killed.** `envelope.mailFrom.parameters` carries RFC 4865's `HOLDFOR` (seconds) or `HOLDUNTIL` (a
 * UTC date); the account's `maxDelayedSend` is the ceiling and `submissionExtensions` says whether
 * the parameters are honoured at all, both read from the session rather than assumed. The
 * response's `sendAt` is the real release time. Until it arrives the submission can be declined
 * with [cancel]; after it, the same call is refused with `cannotUnsend`.
 */
class EmailSubmissionSet(
    private val accountId: AccountId,
    private val create: Map<String, Submission> = emptyMap(),
    private val update: Map<String, JsonObject> = emptyMap(),
    private val onSuccessUpdateEmail: Map<String, EmailPatch> = emptyMap(),
) : JmapMethod<EmailSubmissionSetResult> {

    override val name = "EmailSubmission/set"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)

        if (create.isNotEmpty()) {
            put(
                "create",
                buildJsonObject {
                    create.forEach { (id, submission) -> put(id, submission.toJson()) }
                },
            )
        }

        if (update.isNotEmpty()) {
            put("update", buildJsonObject { update.forEach { (id, patch) -> put(id, patch) } })
        }

        if (onSuccessUpdateEmail.isNotEmpty()) {
            put(
                "onSuccessUpdateEmail",
                buildJsonObject {
                    // The '#' names the creation id of a submission in this
                    // same call, not an existing object.
                    onSuccessUpdateEmail.forEach { (id, patch) -> put("#$id", patch.toJson()) }
                },
            )
        }
    }

    override fun decode(json: Json, arguments: JsonObject): EmailSubmissionSetResult =
        json.decodeFromJsonElement(EmailSubmissionSetResult.serializer(), arguments)

    companion object {
        /**
         * Sends an existing draft, moving it from Drafts to Sent.
         *
         * The mailbox move is spelled out when both bindings are known, so the intent is visible at
         * the call site rather than being an invisible side effect of a pipeline.
         *
         * **Both or neither, never one.** A patch that only removes Drafts leaves the message in no
         * mailbox at all, which the server rejects with "An Email must belong to at least one
         * Mailbox" — and it rejects it *after* the submission has already been queued, from inside
         * `onSuccessUpdateEmail`, so the whole request comes back an error describing a mailbox
         * while the mail is on its way. An account that has never sent anything has no Sent binding
         * yet, which makes that the ordinary case rather than an exotic one. Omitting the patch
         * entirely is safe: plMail's send pipeline performs the same transition itself.
         *
         * [hold] delays the release. Null sends now.
         */
        fun send(
            accountId: AccountId,
            emailId: EmailId,
            identityId: IdentityId,
            drafts: MailboxId?,
            sent: MailboxId?,
            hold: SendHold? = null,
        ): EmailSubmissionSet {
            val patch =
                if (drafts != null && sent != null) {
                    EmailPatch.build {
                        addMailbox(sent)
                        removeMailbox(drafts)
                    }
                } else {
                    null
                }

            return EmailSubmissionSet(
                accountId = accountId,
                create = mapOf(CREATION_ID to Submission(emailId, identityId, hold)),
                onSuccessUpdateEmail = patch?.let { mapOf(CREATION_ID to it) } ?: emptyMap(),
            )
        }

        /**
         * Sends a draft being created in the same request.
         *
         * `emailId: "#c1"` names an `Email/set` creation id rather than an existing message, which
         * is what lets a compose that has never been saved go out in one round trip. It is *not* a
         * result reference: `{"resultOf": …, "path": "/created/c1/id"}` resolves to a bare string
         * and the server then rejects the argument, with no description saying why.
         */
        fun sendNew(
            accountId: AccountId,
            creationId: String,
            identityId: IdentityId,
            hold: SendHold? = null,
        ): EmailSubmissionSet =
            EmailSubmissionSet(
                accountId = accountId,
                create =
                    mapOf(CREATION_ID to Submission(EmailId("#$creationId"), identityId, hold)),
            )

        /**
         * Declines a submission that has not been released yet.
         *
         * `undoStatus: "canceled"` — the American spelling, which is RFC 8621's — is the only
         * update the server accepts on a submission, and it sets the same flag the web composer's
         * undo button sets: nothing is pulled out of the queue, the envelope still comes due, and
         * the worker declines to send. So this is honest for a *held* submission and a race for an
         * immediate one.
         *
         * Two answers to expect. `cannotUnsend` means the mail has already left and there is no
         * recalling it. Success means the draft is still a draft — and a following
         * `EmailSubmission/get` will answer `notFound` rather than reporting "canceled", because
         * there is no submission row to hold that state.
         */
        fun cancel(accountId: AccountId, submissionId: String): EmailSubmissionSet =
            EmailSubmissionSet(
                accountId = accountId,
                update = mapOf(submissionId to buildJsonObject { put("undoStatus", CANCELED) }),
            )

        const val CREATION_ID = "s1"

        /** RFC 8621 §7's spelling of the value, which is not the British one. */
        const val CANCELED = "canceled"
    }
}

/**
 * When a submission may leave, expressed the way RFC 4865 expresses it.
 *
 * The two are alternatives and the server refuses both together, so this is a choice rather than
 * two nullable fields. Neither carries a ceiling of its own: `maxDelayedSend` is the server's and
 * lives in the session, and a client that also hardcoded one would refuse holds this server would
 * happily accept the day the instance raised it.
 */
sealed interface SendHold {

    fun toJson(): JsonObject

    /**
     * An absolute release time, as a JMAP `UTCDate`.
     *
     * The right one for "send tomorrow at eight": the user picked a wall-clock moment, and a
     * duration computed at tap time would drift by however long the request took.
     */
    data class Until(val utcDate: String) : SendHold {
        override fun toJson(): JsonObject = buildJsonObject { put(HOLD_UNTIL, utcDate) }
    }

    /**
     * A relative hold, in whole seconds.
     *
     * The right one for an undo window: what matters is the number of seconds the user has, not
     * which second of the clock it ends on, and the server starts counting when it receives the
     * request rather than when the phone sent it.
     */
    data class For(val seconds: Long) : SendHold {
        // A string, as ESMTP parameters are text. plMail accepts the bare number
        // too, but the spec's form is what every other server will want.
        override fun toJson(): JsonObject = buildJsonObject { put(HOLD_FOR, seconds.toString()) }
    }

    companion object {
        const val HOLD_FOR = "HOLDFOR"
        const val HOLD_UNTIL = "HOLDUNTIL"
    }
}

data class Submission(
    val emailId: EmailId,
    val identityId: IdentityId,
    val hold: SendHold? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("emailId", emailId.value)
        put("identityId", identityId.value)

        // Only the parameters, and deliberately neither `email` nor `rcptTo`.
        // The server validates both against the message it is about to send —
        // an envelope naming a different sender is `forbiddenFrom` and a
        // different recipient set is `invalidRecipients` — so repeating what it
        // already knows buys nothing and turns a Bcc the client happened to
        // round-trip differently into a refused send.
        hold?.let {
            put(
                "envelope",
                buildJsonObject {
                    put("mailFrom", buildJsonObject { put("parameters", it.toJson()) })
                },
            )
        }
    }
}

/**
 * `EmailSubmission/get` — what a submission turned into.
 *
 * Narrower than it looks, and the shape of the client's scheduling model follows from it: plMail
 * reconstructs a submission from the Message rather than storing one, so **only a send that has
 * completed resolves**. A submission still being held, and one that was cancelled, both answer
 * `notFound` — there is no row to say "pending" or "canceled" with. Which means this call answers
 * exactly one question, "did it go, and when", and the client owns the record of anything still
 * waiting.
 */
class EmailSubmissionGet(
    private val accountId: AccountId,
    private val ids: List<String>,
) : JmapMethod<EmailSubmissionGetResult> {

    override val name = "EmailSubmission/get"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        // Never null: the server refuses a get with no ids outright rather than
        // enumerating every submission in the account.
        put("ids", buildJsonArray { ids.forEach { add(it) } })
    }

    override fun decode(json: Json, arguments: JsonObject): EmailSubmissionGetResult =
        json.decodeFromJsonElement(EmailSubmissionGetResult.serializer(), arguments)
}

@Serializable
data class EmailSubmissionGetResult(
    val accountId: String = "",
    val state: String = "",
    val list: List<SubmissionRecord> = emptyList(),
    val notFound: List<String> = emptyList(),
)

@Serializable
data class SubmissionRecord(
    val id: String = "",
    /**
     * The identity the mail actually left as, matched on the From address.
     *
     * Worth reading rather than assuming: it is the server's answer to "which of the addresses you
     * offered did this go out as", and it is the one place a From picker's choice can be confirmed
     * after the fact.
     */
    val identityId: String? = null,
    val emailId: String = "",
    val sendAt: String? = null,
    /** `"final"` for anything this call can return at all — see the class note. */
    val undoStatus: String = "final",
)

@Serializable
data class EmailSubmissionSetResult(
    val accountId: String = "",
    val oldState: String? = null,
    val newState: String = "",
    val created: Map<String, CreatedSubmission> = emptyMap(),
    val notCreated: Map<String, SetError> = emptyMap(),
    val updated: Map<String, JsonElement?> = emptyMap(),
    val notUpdated: Map<String, SetError> = emptyMap(),
) {
    val failure: SetError?
        get() = notCreated.values.firstOrNull()

    /** The refusal of a cancel, which is a different question from the refusal of a send. */
    val updateFailure: SetError?
        get() = notUpdated.values.firstOrNull()

    val submission: CreatedSubmission?
        get() = created[EmailSubmissionSet.CREATION_ID] ?: created.values.firstOrNull()
}

@Serializable
data class CreatedSubmission(
    val id: String = "",
    /**
     * Reported as `"pending"`: the send is genuinely queued and has not happened yet when the call
     * returns. Do not tell the user it was delivered.
     */
    val undoStatus: String = "pending",
    /**
     * The real release time, and the only trustworthy one.
     *
     * For a held submission this is the server's own arithmetic over its own clock — which is why a
     * client that asked for `HOLDFOR` must display *this* rather than the time it computed locally.
     * A phone whose clock is two minutes fast would otherwise promise a release that has not
     * happened.
     */
    val sendAt: String? = null,
)

/** The type a submission refused because the identity is not one this account may send as. */
const val FORBIDDEN_FROM = "forbiddenFrom"

/** The type a cancel is refused with once the mail has left. */
const val CANNOT_UNSEND = "cannotUnsend"
