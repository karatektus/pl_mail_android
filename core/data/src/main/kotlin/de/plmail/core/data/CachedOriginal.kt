package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.EmailAddress
import de.plmail.jmap.mail.EmailBodyPart
import de.plmail.jmap.mail.EmailBodyValue
import de.plmail.jmap.mail.Identity
import de.plmail.jmap.protocol.BlobId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.IdentityId
import de.plmail.jmap.protocol.ThreadId
import java.time.Instant

/**
 * The message a reply or forward is built from, out of the cache — or null where the cache cannot
 * stand in for the server.
 *
 * A reply is written to mail that has just been read, and reading it downloaded everything a reply
 * needs. Asking the server again was a round trip between tapping Reply and seeing the composer,
 * for an answer the phone already held.
 *
 * Null, and so the server, in three cases. No body with its headers beside it: what the list caches
 * says nothing about Reply-To. Mail to be decrypted on this device: its text is inside a blob the
 * cache does not hold. And mail on which none of the account's addresses appears: the server's
 * `replyFrom` names the catch-all address it came in for, and the cache cannot.
 */
internal suspend fun PlMailDatabase.cachedOriginal(accountKey: String, emailId: String): Email? {
    val uid = StoreKey.objectKey(accountKey, emailId)
    val row = emails().byUid(uid) ?: return null
    if (row.needsDecryption) return null

    val body = emails().body(uid)?.takeIf { it.hasReplyHeaders } ?: return null

    val from = listOfNotNull(row.fromAddress?.let { EmailAddress(row.fromName, it) })
    val to = row.toJson.toEmailAddresses()
    val cc = body.ccJson.toEmailAddresses()
    val bcc = row.bccJson.toEmailAddresses()

    val sender =
        identities().forAccount(accountKey).let { own ->
            // Who it is answered as, by the rule the server's `replyFrom`
            // follows: the address that sent it, for one's own mail, and
            // otherwise the address it was sent to.
            (from + to + cc + bcc).firstNotNullOfOrNull { address ->
                own.firstOrNull { it.email.equals(address.email?.trim(), ignoreCase = true) }
            }
        } ?: return null

    val parts = buildList {
        body.htmlBody?.let { add(Triple("html", "text/html", it)) }
        body.textBody?.let { add(Triple("text", "text/plain", it)) }
    }

    return Email(
        id = EmailId(emailId),
        threadId = row.threadId?.let(::ThreadId),
        blobId = row.blobId?.let(::BlobId),
        subject = row.subject,
        from = from,
        to = to,
        cc = cc,
        bcc = bcc,
        replyTo = body.replyToJson.toEmailAddresses(),
        receivedAt = row.receivedAt?.let { Instant.ofEpochMilli(it).toString() },
        sentAt = row.sentAt?.let { Instant.ofEpochMilli(it).toString() },
        messageId = body.messageId?.split(" ")?.filter { it.isNotBlank() },
        references = body.references?.split(" ")?.filter { it.isNotBlank() },
        htmlBody =
            parts.filter { it.first == "html" }.map { EmailBodyPart(it.first, type = it.second) },
        textBody =
            parts.filter { it.first == "text" }.map { EmailBodyPart(it.first, type = it.second) },
        bodyValues = parts.associate { (part, _, value) -> part to EmailBodyValue(value) },
        attachments =
            emails().attachments(uid).map {
                EmailBodyPart(
                    partId = it.partId,
                    blobId = BlobId(it.blobId),
                    size = it.size,
                    type = it.type,
                    name = it.name,
                    disposition = if (it.isInline) "inline" else "attachment",
                    cid = it.cid,
                )
            },
        replyFrom =
            Identity(
                id = IdentityId(sender.identityId),
                name = sender.name,
                email = sender.email,
                htmlSignature = sender.htmlSignature,
            ),
    )
}
