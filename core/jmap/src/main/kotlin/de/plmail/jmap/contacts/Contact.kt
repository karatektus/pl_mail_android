package de.plmail.jmap.contacts

import de.plmail.jmap.protocol.ContactId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One address-book entry, as `Contact/get` returns it.
 *
 * Every property but [id] is optional for the same reason `CalendarEvent`'s are: a `properties`
 * filter answers only what was asked for. A round trip through this type is lossy and must never be
 * written back as a create — the editor builds a [ContactDraft] instead.
 */
@Serializable
data class ContactObject(
    val id: ContactId,
    val displayName: String = "",
    val givenName: String? = null,
    val familyName: String? = null,
    val nickname: String? = null,
    val company: String? = null,
    val jobTitle: String? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    /**
     * Absolute, authenticated URL of the contact's photo, or null when there is none.
     *
     * Authenticated: fetch it through [ContactsPhotoClient.bytes], never through an image loader
     * pointed at the raw URL — the credential must not leave the authorized path, and the server
     * answers 401 without it.
     */
    val photoUrl: String? = null,
    val emails: List<ContactEmailEntry> = emptyList(),
    val phones: List<ContactPhoneEntry> = emptyList(),
    val addresses: List<ContactAddressEntry> = emptyList(),
    val updatedAt: String? = null,
) {
    /** The address-book's own primary email, or the first email, for list rows and chips. */
    fun primaryEmail(): ContactEmailEntry? =
        emails.firstOrNull { it.isPrimary } ?: emails.firstOrNull()

    /** What a list row draws when there is no photo: initials, never blank. */
    fun initials(): String {
        val parts = displayName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> "${parts.first().first()}${parts.last().first()}".uppercase()
        }
    }
}

@Serializable
data class ContactEmailEntry(
    val email: String = "",
    val label: String = "other",
    val isPrimary: Boolean = false,
)

@Serializable
data class ContactPhoneEntry(
    val phone: String = "",
    val label: String = "other",
    val isPrimary: Boolean = false,
)

@Serializable
data class ContactAddressEntry(
    val label: String = "other",
    val street: String? = null,
    val city: String? = null,
    val region: String? = null,
    val postcode: String? = null,
    val country: String? = null,
) {
    /** One line for a detail screen, skipping blanks. */
    fun oneLine(): String =
        listOfNotNull(street, city, region, postcode, country)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
}

/** One message tied to a contact, as `Contact/referenceEmails` returns it — newest first. */
@Serializable
data class ReferenceEmail(
    val id: String = "",
    val subject: String? = null,
    val from: ReferenceEmailFrom? = null,
    /** ISO 8601 instant, e.g. `2026-10-16T12:00:00Z`. */
    val date: String? = null,
    /** The mailbox/folder name the message was found in — Inbox, Sent, Drafts, Trash, a label… */
    val mailbox: String = "",
    val isStarred: Boolean = false,
)

@Serializable
data class ReferenceEmailFrom(
    val name: String? = null,
    val email: String = "",
)

/** One duplicate candidate pair, as `Contact/duplicates` returns it. */
@Serializable
data class DuplicatePair(
    /** Exactly two contact ids. */
    val ids: List<ContactId> = emptyList(),
    /** 0–100. 100 = same normalized email, 95 = same normalized phone; see the server docs. */
    val confidence: Int = 0,
    /** Human-readable, e.g. "same email", "Jr./Sr. — check carefully". */
    val reasons: List<String> = emptyList(),
    /**
     * True when the server considers the merge safe to do without review. Shared inboxes (`info@`,
     * `support@`, `noreply@`) are never auto-mergeable, however high the confidence.
     */
    val autoMergeable: Boolean = false,
)

/**
 * A contact as the editor holds it: the whole contact, never a patch.
 *
 * `Contact/set` update is a patch, but the editor always knows the whole contact, so the patch it
 * sends is the whole contact minus the id — with emails/phones/addresses replace-all when present,
 * which they always are from this form. Carrying the whole draft means a caller cannot accidentally
 * send half a contact.
 */
data class ContactDraft(
    val displayName: String,
    val givenName: String = "",
    val familyName: String = "",
    val nickname: String = "",
    val company: String = "",
    val jobTitle: String = "",
    val notes: String = "",
    val tags: List<String> = emptyList(),
    val emails: List<ContactEmailEntry> = emptyList(),
    val phones: List<ContactPhoneEntry> = emptyList(),
    val addresses: List<ContactAddressEntry> = emptyList(),
    val photo: PhotoChange = PhotoChange.Unchanged,
) {
    init {
        require(displayName.isNotBlank()) { "A contact needs a display name." }
    }

    companion object {
        fun of(contact: ContactObject): ContactDraft =
            ContactDraft(
                displayName = contact.displayName,
                givenName = contact.givenName.orEmpty(),
                familyName = contact.familyName.orEmpty(),
                nickname = contact.nickname.orEmpty(),
                company = contact.company.orEmpty(),
                jobTitle = contact.jobTitle.orEmpty(),
                notes = contact.notes.orEmpty(),
                tags = contact.tags,
                emails = contact.emails,
                phones = contact.phones,
                addresses = contact.addresses,
            )
    }

    /**
     * The `create`/`update` value object.
     *
     * Blank strings are sent as absent rather than as empty: an empty `company` on the wire would
     * overwrite a filled one with nothing on an update, and on a create it stores noise. The photo
     * is a tri-state — [PhotoChange] — because "unchanged" must send no key at all while "removed"
     * must send an explicit null.
     */
    fun toJson(): JsonObject = buildJsonObject {
        put("displayName", displayName)
        givenName.ifBlankAsNull()?.let { put("givenName", it) }
        familyName.ifBlankAsNull()?.let { put("familyName", it) }
        nickname.ifBlankAsNull()?.let { put("nickname", it) }
        company.ifBlankAsNull()?.let { put("company", it) }
        jobTitle.ifBlankAsNull()?.let { put("jobTitle", it) }
        notes.ifBlankAsNull()?.let { put("notes", it) }
        if (tags.isNotEmpty()) put("tags", buildJsonArray { tags.forEach { add(it) } })
        if (emails.isNotEmpty()) {
            put(
                "emails",
                buildJsonArray {
                    emails.forEach {
                        add(
                            buildJsonObject {
                                put("email", it.email)
                                put("label", it.label)
                                put("isPrimary", it.isPrimary)
                            }
                        )
                    }
                },
            )
        }
        if (phones.isNotEmpty()) {
            put(
                "phones",
                buildJsonArray {
                    phones.forEach {
                        add(
                            buildJsonObject {
                                put("phone", it.phone)
                                put("label", it.label)
                                put("isPrimary", it.isPrimary)
                            }
                        )
                    }
                },
            )
        }
        if (addresses.isNotEmpty()) {
            put(
                "addresses",
                buildJsonArray {
                    addresses.forEach {
                        add(
                            buildJsonObject {
                                put("label", it.label)
                                it.street?.ifBlankAsNull()?.let { v -> put("street", v) }
                                it.city?.ifBlankAsNull()?.let { v -> put("city", v) }
                                it.region?.ifBlankAsNull()?.let { v -> put("region", v) }
                                it.postcode?.ifBlankAsNull()?.let { v -> put("postcode", v) }
                                it.country?.ifBlankAsNull()?.let { v -> put("country", v) }
                            }
                        )
                    }
                },
            )
        }
        when (val p = photo) {
            is PhotoChange.New -> put("photoBlobId", p.blobId)
            is PhotoChange.Removed -> put("photoBlobId", kotlinx.serialization.json.JsonNull)
            is PhotoChange.Unchanged -> Unit
        }
    }

    private fun String.ifBlankAsNull(): String? = takeIf { it.isNotBlank() }
}

/**
 * What happened to the photo in this edit.
 *
 * A tri-state rather than a nullable id because the wire distinguishes three things: no key (leave
 * the photo alone), an id (replace it), and an explicit null (remove it). A single nullable field
 * cannot say the first two apart.
 */
sealed interface PhotoChange {
    data object Unchanged : PhotoChange

    data class New(val blobId: String) : PhotoChange

    data object Removed : PhotoChange
}
