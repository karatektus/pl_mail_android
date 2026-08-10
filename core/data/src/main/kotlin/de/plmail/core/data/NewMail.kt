package de.plmail.core.data

/**
 * A message that has just arrived on this device.
 *
 * Carries everything a notification needs and nothing it has to look up afterwards. That is not
 * convenience: a notification is built on a broadcast receiver's thread with about ten seconds to
 * live, and a shape that requires a second database read is a shape that sometimes does not get
 * built.
 *
 * [accountName] is here for the same reason. One credential reaches several mailboxes, so "which of
 * mine is this" is the first question a notification has to answer, and the answer belongs to the
 * account row rather than to the message.
 */
data class NewMessage(
    val accountKey: String,
    val accountName: String,
    val emailId: String,
    val threadId: String,
    /**
     * The sender as a person reads it — a display name where there is one, the address otherwise.
     */
    val sender: String,
    val subject: String?,
    val preview: String,
    val receivedAt: Long,
)

/**
 * Told when mail arrives, so something can put it in front of the user.
 *
 * An interface in this module rather than a direct call into `:core:notifications`, because the
 * dependency has to run the other way: syncing is what this module does and notifying is a
 * presentation decision. Bound through Hilt as a set, so a build without the notifications module —
 * or a test — syncs perfectly well and simply says nothing.
 *
 * **Only [DeltaSync] raises this, and that is the whole definition of "new".** Paging a list also
 * writes messages the cache has never seen, and every one of them is *older* mail being fetched
 * because the user scrolled. Notifying from there would announce a conversation from March at the
 * moment somebody reached the bottom of their inbox.
 */
interface NewMailListener {
    suspend fun onNewMail(messages: List<NewMessage>)
}

/**
 * Which of a fetched page counts as mail that has just arrived.
 *
 * Pure, and split out of [DeltaSync] for one reason: every filter here is the kind that fails
 * silently in the direction of *more* notifications, at three in the morning, on somebody's phone.
 * A test is cheaper than finding out.
 *
 * - **[known] is subtracted first.** New means new to this device, not new to the server. A
 *   re-indexed message, a server that reports every touched row as created, and a cursor that was
 *   discarded and rebuilt all deliver old mail through the same path. This is also what gives the
 *   whole feature its "no retroactive notifications" property: a message the user was not told
 *   about because its label was switched off is in the cache all the same, so switching that label
 *   on later cannot make an old message announce itself. Only mail that arrives *after* the switch
 *   is affected, which is what Gmail does and what somebody changing a setting expects.
 * - **Unread only.** A message already read elsewhere — the web client, another phone — has been
 *   dealt with, and announcing it is announcing the user's own past.
 * - **A switched-on scope.** What used to be a hard "inbox only" rule, and is now the user's own
 *   answer with the inbox's Primary category as its default — see [notifyScopeKeys], which explains
 *   why that default is not simply `category == "primary"`. Sent mail and drafts are still silent,
 *   but by falling under no scope anybody can switch on rather than by a rule that also made
 *   per-label notifications impossible.
 *
 * Exactly one [NewMessage] per message, and the type system is not what guarantees it: `filter`
 * rather than `flatMap` over the matching scopes is, which is why a message carrying three
 * switched-on labels is one notification. [distinctBy] behind it is belt and braces for a server
 * that answers the same id twice in one `Email/get`.
 */
internal fun newArrivals(
    emails: List<de.plmail.jmap.mail.Email>,
    accountKey: String,
    accountName: String,
    inboxMailboxId: String?,
    known: Set<String>,
    prefs: de.plmail.core.datastore.NotificationPrefs,
    /** Mailbox binding id to label collapse key, for this account. */
    bindingKeys: Map<String, String>,
    /** Thread id to the conversation's inbox category, as the server's own token. */
    threadCategories: Map<String, String?>,
): List<NewMessage> =
    emails
        .filter { de.plmail.core.database.StoreKey.objectKey(accountKey, it.id.value) !in known }
        .filter { !it.isSeen }
        .filter { email ->
            notifyScopeKeys(
                    email = email,
                    inboxMailboxId = inboxMailboxId,
                    threadCategory = email.threadId?.value?.let { threadCategories[it] },
                    bindingKeys = bindingKeys,
                )
                .any(prefs::allows)
        }
        .distinctBy { it.id.value }
        .map { it.asNewMessage(accountKey, accountName) }
