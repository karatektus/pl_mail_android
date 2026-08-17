package de.plmail.core.data

import de.plmail.core.database.AccountEntity
import de.plmail.core.database.AttachmentEntity
import de.plmail.core.database.EmailBodyEntity
import de.plmail.core.database.EmailEntity
import de.plmail.core.database.IdentityEntity
import de.plmail.core.database.MailboxEntity
import de.plmail.core.database.StoreKey
import de.plmail.core.database.ThreadEntity
import de.plmail.jmap.mail.Email
import de.plmail.jmap.mail.EmailAddress
import de.plmail.jmap.mail.Identity
import de.plmail.jmap.mail.MailThread
import de.plmail.jmap.mail.Mailbox
import de.plmail.jmap.protocol.Account
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * JMAP objects into cache rows.
 *
 * Everything here is a projection, never a merge: a row is rebuilt from what the server just said
 * rather than patched onto what was there before. That is what keeps the schema's central rule true
 * — every row reconstructible from the server — and it is why none of these functions read the
 * database first.
 */
internal object Wire {
    /**
     * Encoding only, so no `ignoreUnknownKeys`: these strings are written and read by this file
     * alone. If one ever fails to decode, the row is a cache entry and re-syncs.
     */
    val json = Json

    val addresses = ListSerializer(EmailAddress.serializer())
}

/**
 * Parses a JMAP `UTCDate` to epoch milliseconds.
 *
 * Two formats rather than one because `Instant.parse` only accepts a `Z` suffix, and a server
 * behind a reverse proxy that rewrites dates — or simply a different JMAP implementation — can hand
 * back `2026-07-31T18:55:43+02:00`. That parses as a perfectly valid instant and would otherwise be
 * dropped, putting the message at the bottom of the list with no date.
 *
 * Null rather than a throw or an epoch default. A message whose date will not parse must sort last,
 * and substituting `0` would sort it *first* in an ascending list and put a mystery message from
 * 1970 at the top of someone's inbox.
 */
internal fun String?.toEpochMillis(): Long? {
    val text = this?.trim().orEmpty()
    if (text.isEmpty()) return null

    return try {
        Instant.parse(text).toEpochMilli()
    } catch (notInstant: DateTimeParseException) {
        try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (notOffset: DateTimeParseException) {
            null
        }
    }
}

/** The account row, from the Session's account entry. */
fun Account.toEntity(server: String, accountId: String, sortIndex: Int = 0): AccountEntity {
    val uid = StoreKey.account(server, accountId)

    return AccountEntity(
        uid = uid,
        serverId = server,
        accountId = accountId,
        name = name,
        isPersonal = isPersonal,
        isReadOnly = isReadOnly,
        sortIndex = sortIndex,
    )
}

fun Mailbox.toEntity(accountKey: String): MailboxEntity =
    MailboxEntity(
        uid = StoreKey.objectKey(accountKey, id.value),
        accountKey = accountKey,
        mailboxId = id.value,
        labelId = labelId?.value,
        name = name,
        parentId = parentId?.value,
        role = role,
        color = color,
        // Not sortOrder: the server reports 0 for custom labels and for Inbox
        // alike, so sorting on it alone does not reproduce the documented
        // sidebar order. The role's own order is the authority, and unroled
        // labels fall after every system one.
        sortOrder = knownRole?.sidebarOrder ?: UNROLED_SORT_ORDER,
        totalEmails = totalEmails,
        unreadEmails = unreadEmails,
        totalThreads = totalThreads,
        unreadThreads = unreadThreads,
        isSubscribed = isSubscribed,
        mayRename = myRights.mayRename,
        mayDelete = myRights.mayDelete,
    )

fun Identity.toEntity(accountKey: String, sortIndex: Int = 0): IdentityEntity =
    IdentityEntity(
        uid = StoreKey.objectKey(accountKey, id.value),
        accountKey = accountKey,
        identityId = id.value,
        name = name,
        email = email,
        // The HTML form, not `textSignature`. The server derives the text one
        // from this at read time and the composer's body is HTML throughout, so
        // taking the derived value would mean rebuilding markup the server had
        // already flattened -- and losing whatever the user actually wrote.
        htmlSignature = htmlSignature,
        sortIndex = sortIndex,
    )

/**
 * The same message in the shape a notification is built from.
 *
 * Separate from [toEntity] rather than derived from it, because the two answer different questions.
 * The entity is a cache row and may legitimately hold nulls that get filled in later; this has to
 * be complete the moment it is made, since what it becomes is a line of text in the shade with no
 * second chance to look anything up.
 *
 * The thread id falls back to the message's own, exactly as the feed row does. A message the server
 * has not threaded is a conversation of one, and a notification that cannot name a conversation is
 * one that cannot be tapped.
 */
internal fun Email.asNewMessage(accountKey: String, accountName: String): NewMessage {
    val sender = from.firstOrNull()

    return NewMessage(
        accountKey = accountKey,
        accountName = accountName,
        emailId = id.value,
        threadId = threadId?.value ?: id.value,
        // The display name where the sender set one, the bare address otherwise
        // — never an empty line, because a notification with no sender reads as
        // a bug rather than as mail from someone anonymous.
        sender = sender?.display?.takeIf { it.isNotBlank() } ?: sender?.email.orEmpty(),
        subject = subject?.takeIf { it.isNotBlank() },
        preview = preview,
        receivedAt = receivedAt.toEpochMillis() ?: System.currentTimeMillis(),
    )
}

fun Email.toEntity(accountKey: String): EmailEntity {
    val sender = from.firstOrNull()

    return EmailEntity(
        uid = StoreKey.objectKey(accountKey, id.value),
        accountKey = accountKey,
        emailId = id.value,
        threadId = threadId?.value,
        blobId = blobId?.value,
        receivedAt = receivedAt.toEpochMillis(),
        sentAt = sentAt.toEpochMillis(),
        subject = subject,
        // The header can legitimately carry several; the first is the one a
        // reply's In-Reply-To must name.
        messageId = messageId?.firstOrNull(),
        // Space-separated, as the References header itself is written, so the
        // stored form is the one a composer can put straight back on the wire.
        references = references?.takeIf { it.isNotEmpty() }?.joinToString(" "),
        fromName = sender?.name,
        fromAddress = sender?.email,
        toJson = to.encode(),
        ccJson = cc.encode(),
        bccJson = bcc.encode(),
        preview = preview,
        size = size,
        isSeen = isSeen,
        isFlagged = isFlagged,
        isDraft = isDraft,
        isAnswered = isAnswered,
        hasAttachment = hasAttachment,
        // Only the true entries: JMAP's map form is `{"42": true}`, and a key
        // whose value is false is not a membership.
        mailboxIds = mailboxes.joinToString(",") { it.value },
    )
}

/** Null when nothing was fetched, so an unfetched body is distinguishable from an empty one. */
fun Email.toBodyEntity(accountKey: String, fetchedAt: Long): EmailBodyEntity? {
    val text = textContent
    val html = htmlContent
    if (text == null && html == null) return null

    return EmailBodyEntity(
        uid = StoreKey.objectKey(accountKey, id.value),
        textBody = text,
        htmlBody = html,
        fetchedAt = fetchedAt,
    )
}

fun Email.toAttachmentEntities(accountKey: String): List<AttachmentEntity> {
    val emailUid = StoreKey.objectKey(accountKey, id.value)

    return attachments.mapNotNull { part ->
        // A part with no blobId cannot be downloaded, so a row for it would be
        // a listing entry that fails when tapped.
        val blob = part.blobId ?: return@mapNotNull null

        AttachmentEntity(
            uid = StoreKey.objectKey(emailUid, part.partId ?: blob.value),
            emailUid = emailUid,
            accountKey = accountKey,
            partId = part.partId.orEmpty(),
            blobId = blob.value,
            name = part.name,
            type = part.type,
            size = part.size,
            cid = part.cid,
            isInline = part.isInline,
        )
    }
}

/**
 * The denormalised list row, computed from the messages the cache holds.
 *
 * Takes stored rows rather than wire objects because it runs *after* a page has been written, over
 * everything the thread now has. Summarising the arriving page alone would rewrite a long
 * conversation as a one-message thread from a single participant the moment one reply arrived on
 * its own.
 *
 * RFC 8621's Thread carries only ids, so every field a list row draws is derived here — and
 * deriving it on read for fifty rows at 120fps is exactly what this table exists to avoid.
 *
 * [messages] may be empty: threads arrive over several pages, and a row built from what is known
 * beats no row at all.
 */
fun MailThread.toEntity(
    accountKey: String,
    messages: List<EmailEntity>,
    /**
     * Every mailbox binding in this account, as binding id to collapse key.
     *
     * Passed in rather than looked up, because this runs once per touched thread inside one
     * transaction and the account's mailbox list is the same for all of them — resolving it per
     * thread would be the read-time join the whole table exists to avoid, moved to write time.
     */
    bindings: Map<String, String> = emptyMap(),
): ThreadEntity {
    val dated = messages.sortedBy { it.receivedAt ?: Long.MIN_VALUE }
    val newest = dated.lastOrNull()

    return ThreadEntity(
        uid = StoreKey.objectKey(accountKey, id.value),
        accountKey = accountKey,
        threadId = id.value,
        latestReceivedAt = newest?.receivedAt ?: 0,
        // The subject of the message that started it, not the newest: a reply
        // prefixed with "Re:" would otherwise rewrite the conversation's title
        // every time someone answered.
        subject = dated.firstOrNull()?.subject ?: newest?.subject,
        participantsSummary = dated.participantsOldestFirst(),
        // The newest sender's address, because that is who the row's avatar is
        // of. The address rather than the display name: hashing the name
        // recolours the same person whenever they reconfigure their client.
        participantsAddress = newest?.fromAddress?.lowercase(),
        snippet = newest?.preview.orEmpty(),
        messageCount = maxOf(messageCount, messages.size),
        // Unread if *any* message is, which is what makes a thread bold again
        // when a new reply lands in an otherwise-read conversation.
        isUnread = messages.any { !it.isSeen },
        isFlagged = messages.any { it.isFlagged },
        hasAttachment = messages.any { it.hasAttachment },
        snoozedUntil = snoozedUntil.toEpochMillis(),
        // Straight off the wire, unresolved and unsubstituted. The server has
        // already folded the conversation's messages most-recent-wins, so
        // recomputing it here from `messages` would be a second implementation
        // of a rule the tabs are queried by -- and the two would disagree the
        // moment a thread arrived across two pages with its newest message in
        // the second.
        category = category,
        // The server's own marker, carried through unchanged. Not derived from
        // `isUnread` beside it and not from the received time: newness is
        // "never displayed AND inside the window", and both halves are facts
        // only the server holds.
        isNew = isNew,
        // The union across the conversation's messages, not the newest one's.
        // A label applied to a single reply is a label the conversation
        // carries -- that is what the sidebar's count says and what browsing the
        // label shows, so a row that disagreed would be the odd one out.
        labelKeys = messages.labelKeysVia(bindings),
    )
}

/**
 * The collapse keys of every label the messages are bound to.
 *
 * Sorted, and that is not tidiness: the value is compared against the previous one on every write,
 * so an order that depended on which message happened to be read first would rewrite the row — and
 * invalidate the list — on syncs that changed nothing.
 */
private fun List<EmailEntity>.labelKeysVia(bindings: Map<String, String>): String =
    asSequence()
        .flatMap { it.mailboxIds.splitIds() }
        .mapNotNull { bindings[it] }
        .distinct()
        .sorted()
        .joinToString(",")

/** Blank-tolerant, because an unbound message stores an empty string rather than a null. */
internal fun String.splitIds(): List<String> = split(",").filter { it.isNotBlank() }

/**
 * Participants oldest first, each named once.
 *
 * Oldest first rather than newest, and it is a real correction rather than a preference: ordering
 * by most recent made every conversation the user had answered appear to be from themselves,
 * because the last message in it was theirs.
 */
private fun List<EmailEntity>.participantsOldestFirst(): String =
    asSequence()
        .map { EmailAddress(it.fromName, it.fromAddress).display }
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(", ")

private fun List<EmailAddress>.encode(): String? = takeIf {
    it.isNotEmpty()
}
    ?.let { Wire.json.encodeToString(Wire.addresses, it) }

/** Decodes what [encode] wrote. Empty on anything unreadable — the row is a cache entry. */
fun String?.toEmailAddresses(): List<EmailAddress> {
    val text = this?.takeIf { it.isNotBlank() } ?: return emptyList()

    return try {
        Wire.json.decodeFromString(Wire.addresses, text)
    } catch (undecodable: kotlinx.serialization.SerializationException) {
        emptyList()
    }
}

/** After every system role, so custom labels sort below them but keep their own order by name. */
private const val UNROLED_SORT_ORDER = 1_000
