package de.plmail.jmap.methods

import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.IdentityId
import de.plmail.jmap.protocol.JmapMethod
import de.plmail.jmap.protocol.MailboxId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `EmailSubmission/set` — actually sending.
 *
 * Sending is queued on the same message bus the web composer uses, and that pipeline performs the
 * whole draft-to-sent transition itself: it adds Sent, removes Drafts, clears the draft flag, sets
 * the sent timestamp and re-points the mailbox. A client that omits `onSuccessUpdateEmail` still
 * ends up correct — the argument is there for clients that want the transition expressed
 * explicitly.
 *
 * Two things worth knowing:
 *
 * A submission **has no table of its own — its id is the Email id**, because plMail sends each
 * draft at most once and the mapping stays one-to-one.
 *
 * **The web UI's undo-send grace period is deliberately NOT applied here.** A JMAP client that
 * calls this asked to send *now*. An undo window in the app is a client-side delay before making
 * this call, not something the server offers; `maxDelayedSend` is 0, so there is no scheduled send
 * either.
 */
class EmailSubmissionSet(
    private val accountId: AccountId,
    private val create: Map<String, Submission>,
    private val onSuccessUpdateEmail: Map<String, EmailPatch> = emptyMap(),
) : JmapMethod<EmailSubmissionSetResult> {

    override val name = "EmailSubmission/set"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put(
            "create",
            buildJsonObject { create.forEach { (id, submission) -> put(id, submission.toJson()) } },
        )

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
         * The mailbox move is spelled out even though the server would do it anyway, so the intent
         * is visible at the call site rather than being an invisible side effect of a pipeline.
         */
        fun send(
            accountId: AccountId,
            emailId: EmailId,
            identityId: IdentityId,
            drafts: MailboxId?,
            sent: MailboxId?,
        ): EmailSubmissionSet {
            val patch = EmailPatch.build {
                drafts?.let { removeMailbox(it) }
                sent?.let { addMailbox(it) }
            }

            return EmailSubmissionSet(
                accountId = accountId,
                create = mapOf(CREATION_ID to Submission(emailId, identityId)),
                onSuccessUpdateEmail =
                    if (patch.isEmpty) emptyMap() else mapOf(CREATION_ID to patch),
            )
        }

        private const val CREATION_ID = "s1"
    }
}

data class Submission(val emailId: EmailId, val identityId: IdentityId) {
    fun toJson(): JsonObject = buildJsonObject {
        put("emailId", emailId.value)
        put("identityId", identityId.value)
    }
}

@Serializable
data class EmailSubmissionSetResult(
    val accountId: String = "",
    val oldState: String? = null,
    val newState: String = "",
    val created: Map<String, CreatedSubmission> = emptyMap(),
    val notCreated: Map<String, SetError> = emptyMap(),
) {
    val failure: SetError?
        get() = notCreated.values.firstOrNull()
}

@Serializable
data class CreatedSubmission(
    val id: String = "",
    /**
     * Reported as `"pending"`: the send is genuinely queued and has not happened yet when the call
     * returns. Do not tell the user it was delivered.
     */
    val undoStatus: String = "pending",
    val sendAt: String? = null,
)
