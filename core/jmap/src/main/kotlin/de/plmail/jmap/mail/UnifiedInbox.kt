package de.plmail.jmap.mail

import kotlinx.serialization.Serializable

/**
 * One of the reader's unified inboxes, as `UnifiedInbox/get` lists it (pl_mail#265).
 *
 * [name] is null where the reader gave none: the app says "Inbox", or for [isOther] "Other", in its
 * own language. [icon] is a Font Awesome solid icon name from the capability's `icons`, mapped to
 * the app's own set. [accountIds] is a list although an account is in one inbox today.
 */
@Serializable
data class UnifiedInbox(
    val id: String,
    val name: String? = null,
    val icon: String = "inbox",
    val sortOrder: Int = 0,
    val isOther: Boolean = false,
    val accountIds: List<String> = emptyList(),
)
