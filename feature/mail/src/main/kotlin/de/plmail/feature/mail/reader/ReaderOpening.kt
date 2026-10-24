package de.plmail.feature.mail.reader

import de.plmail.core.database.EmailEntity

/**
 * Whether a message starts expanded when its conversation is drawn (#58).
 *
 * Every unread message and the newest, as the web app opens a thread (`_thread_content.html.twig`:
 * `loop.last or message.seenAt is null`). The newest alone left fresh replies folded away above the
 * last one, and folded they were never marked read either, so the conversation stayed bold in the
 * list after it had been opened.
 *
 * [previously] is what the user left it at, and wins: the reader is rebuilt once bodies arrive, and
 * a rebuild that re-applied the rule would fold a message they had just opened.
 */
internal fun startsExpanded(email: EmailEntity, newestUid: String?, previously: Boolean?): Boolean =
    previously ?: (!email.isSeen || email.uid == newestUid)

/**
 * The message the reader starts at, or null for the top (#58): see [ReaderUiState.firstUnread].
 *
 * Decided by the first rebuild with messages — [shownBefore] is empty until then — and carried from
 * [decided] after that, so that reading, which marks messages read, does not move it.
 */
internal fun startAt(
    shownBefore: List<ReaderMessage>,
    rebuilt: List<ReaderMessage>,
    decided: String?,
): String? =
    if (shownBefore.isEmpty()) rebuilt.firstOrNull { !it.email.isSeen }?.email?.uid else decided
