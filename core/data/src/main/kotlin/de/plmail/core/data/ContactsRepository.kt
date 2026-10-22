package de.plmail.core.data

import android.util.LruCache
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.contacts.ContactDraft
import de.plmail.jmap.contacts.ContactObject
import de.plmail.jmap.contacts.ContactsPhotoClient
import de.plmail.jmap.contacts.DuplicatePair
import de.plmail.jmap.contacts.PhotoChange
import de.plmail.jmap.contacts.ReferenceEmail
import de.plmail.jmap.methods.ContactDuplicates
import de.plmail.jmap.methods.ContactFilter
import de.plmail.jmap.methods.ContactGet
import de.plmail.jmap.methods.ContactMerge
import de.plmail.jmap.methods.ContactMergeUndo
import de.plmail.jmap.methods.ContactQuery
import de.plmail.jmap.methods.ContactReferenceEmails
import de.plmail.jmap.methods.ContactSet
import de.plmail.jmap.methods.ContactSort
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.ContactId
import de.plmail.jmap.protocol.JmapError
import de.plmail.jmap.protocol.RequestBuilder
import de.plmail.jmap.protocol.Session
import javax.inject.Inject
import javax.inject.Singleton

/** A picked photo, before it is uploaded. */
data class NewContactPhoto(
    val name: String,
    val contentType: String,
    val bytes: ByteArray,
)

/**
 * The address book: list, search, create, edit, delete, photos, duplicate detection/merging, and
 * the reference-emails tie-in.
 *
 * A thin layer over the Contact JMAP methods — the address book is small and live-queried, so
 * nothing is cached in Room. [ContactDraft] is the whole contact the editor holds; the repository
 * turns it into the `create`/`update` wire shape.
 */
@Singleton
class ContactsRepository @Inject constructor(private val clients: AccountClients) {

    /**
     * Whether the connected server publishes the contacts capability. False when not connected or
     * unreachable — the drawer entry hides.
     */
    suspend fun isAvailable(): Boolean = runCatching {
        clients.current()?.session()?.capabilities?.containsKey(Capability.CONTACTS) == true
    }
        .getOrDefault(false)

    /** The whole address book, or the matches for [filter] — name-sorted, paged as needed. */
    suspend fun list(filter: ContactFilter? = null): List<ContactObject> {
        val client = client()
        val session = client.session()
        val accountId = contactsAccount(session)

        val contacts = mutableListOf<ContactObject>()
        var position = 0
        while (true) {
            val batch = request(session)
            val query =
                batch.add(ContactQuery(accountId, filter, ContactSort.NAME, position, PAGE_SIZE))
            val get = batch.add(ContactGet.byReference(accountId, query.reference("/ids")))
            val answers = client.send(batch)

            val page = answers.result(query)
            contacts += answers.result(get).ordered(page.ids)
            position += page.ids.size

            // An empty page ends the paging whatever `total` said: trusting a
            // total that never arrives is a loop with no way out.
            if (page.ids.isEmpty()) break
            val total = page.total
            if (total != null && position >= total) break
        }
        return contacts
    }

    /** One contact, or null when the server no longer has it. */
    suspend fun get(id: ContactId): ContactObject? {
        val client = client()
        val session = client.session()
        val accountId = contactsAccount(session)

        val batch = request(session)
        val handle = batch.add(ContactGet(accountId, listOf(id)))
        return client.send(batch).result(handle).list.firstOrNull()
    }

    /**
     * Creates a contact, uploading [photo] first when one was picked.
     *
     * Upload-then-create: a blob the create never consumes is scratch the server sweeps, while a
     * create without its photo is a contact the user has to open and fix. The failure mode that
     * remains — photo up, create refused — leaves an orphaned blob, which is the cheaper wrong
     * answer.
     */
    suspend fun create(draft: ContactDraft, photo: NewContactPhoto? = null): ContactId {
        val withPhoto = draft.withUploadedPhoto(photo)
        val client = client()
        val session = client.session()
        val accountId = contactsAccount(session)

        val batch = request(session)
        val handle =
            batch.add(
                ContactSet(
                    accountId = accountId,
                    create = mapOf(ContactSet.CREATION_ID to withPhoto.toJson()),
                )
            )
        val result = client.send(batch).result(handle)

        result.firstFailure()?.let { throw JmapError.RequestRejected(it.type, 0, it.description) }
        return result.created[ContactSet.CREATION_ID]?.id
            ?: throw JmapError.MalformedResponse(
                "The server created no contact and gave no reason."
            )
    }

    /**
     * Replaces a contact with [draft]. Emails/phones/addresses are replace-all on the wire, which
     * is what the editor wants: it always holds the whole contact.
     */
    suspend fun update(
        id: ContactId,
        draft: ContactDraft,
        photo: NewContactPhoto? = null,
    ): ContactId {
        val withPhoto = draft.withUploadedPhoto(photo)
        val client = client()
        val session = client.session()
        val accountId = contactsAccount(session)

        val batch = request(session)
        val handle =
            batch.add(
                ContactSet(
                    accountId = accountId,
                    update = mapOf(id to withPhoto.toJson()),
                )
            )
        val result = client.send(batch).result(handle)

        result.firstFailure()?.let { throw JmapError.RequestRejected(it.type, 0, it.description) }

        // A photo change may reuse the contact's photo URL with new bytes behind
        // it, which a URL-keyed cache cannot see — so a photo mutation clears
        // the cache rather than trying to name the stale entry.
        if (withPhoto.photo != PhotoChange.Unchanged) photoCache.evictAll()
        return id
    }

    /** Soft-deletes a contact — it moves to the 90-day trash, it is not gone. */
    suspend fun delete(id: ContactId) {
        val client = client()
        val session = client.session()
        val accountId = contactsAccount(session)

        val batch = request(session)
        val handle = batch.add(ContactSet(accountId = accountId, destroy = listOf(id)))
        val result = client.send(batch).result(handle)

        result.firstFailure()?.let { throw JmapError.RequestRejected(it.type, 0, it.description) }
    }

    /**
     * Every non-deleted message to or from a contact — inbox, sent, drafts, trash, labels, starred
     * — newest first. The REFERENCE EMAILS section on the detail screen.
     */
    suspend fun referenceEmails(id: ContactId, limit: Int = 50): List<ReferenceEmail> {
        val client = client()
        val session = client.session()
        val accountId = contactsAccount(session)

        val batch = request(session)
        val handle = batch.add(ContactReferenceEmails(accountId, id, limit))
        return client.send(batch).result(handle).list
    }

    /** The server's duplicate candidate pairs for the whole address book. */
    suspend fun duplicates(): List<DuplicatePair> {
        val client = client()
        val session = client.session()
        val accountId = contactsAccount(session)

        val batch = request(session)
        val handle = batch.add(ContactDuplicates(accountId))
        return client.send(batch).result(handle).pairs
    }

    /**
     * Folds [mergeIds] into [keepId]. Returns the merge record id — the detail screen offers "Undo"
     * from it without a second round trip.
     */
    suspend fun merge(keepId: ContactId, mergeIds: List<ContactId>): String {
        val client = client()
        val session = client.session()
        val accountId = contactsAccount(session)

        val batch = request(session)
        val handle = batch.add(ContactMerge(accountId, keepId, mergeIds))
        val recordId = client.send(batch).result(handle).mergeRecordId
        // The kept contact may have gained a photo from a merged one behind a
        // reused URL — see update(). Clear rather than name the stale entry.
        photoCache.evictAll()
        return recordId
    }

    /** Restores the contacts a merge folded away. Valid for 90 days after the merge. */
    suspend fun mergeUndo(mergeRecordId: String) {
        val client = client()
        val session = client.session()
        val accountId = contactsAccount(session)

        val batch = request(session)
        val handle = batch.add(ContactMergeUndo(accountId, mergeRecordId))
        client.send(batch).result(handle)
    }

    /**
     * One contact's photo bytes, or null when it has none.
     *
     * Cached in memory by photo URL: a list row scrolls past the same photos repeatedly, and each
     * fetch is an authorized round trip. Cleared on photo mutations (update/merge), because a
     * changed photo may sit behind a reused URL. The URL is never handed to an image loader — the
     * bytes travel the authorized path only.
     */
    suspend fun photoBytes(contact: ContactObject): ByteArray? {
        val url = contact.photoUrl ?: return null
        photoCache.get(url)?.let {
            return it
        }

        val bytes = ContactsPhotoClient(client()).bytes(url) ?: return null
        photoCache.put(url, bytes)
        return bytes
    }

    /** Uploads a picked photo; the returned blob id goes into the draft as [PhotoChange.New]. */
    suspend fun uploadPhoto(photo: NewContactPhoto): String =
        ContactsPhotoClient(client()).upload(photo.name, photo.contentType, photo.bytes)

    // ------------------------------------------------------------------ plumbing

    private val photoCache = LruCache<String, ByteArray>(PHOTO_CACHE_SIZE)

    private suspend fun client(): JmapClient =
        clients.current() ?: throw JmapError.NotAuthenticated("Not connected.")

    private fun contactsAccount(session: Session): AccountId =
        session.primaryContactsAccount
            ?: throw JmapError.NotAuthenticated("This server has no contacts account.")

    private fun request(session: Session): RequestBuilder =
        RequestBuilder(
            using = Capability.USING_CONTACTS,
            maxCallsInRequest = session.core.maxCallsInRequest,
        )

    private suspend fun ContactDraft.withUploadedPhoto(photo: NewContactPhoto?): ContactDraft {
        if (photo == null) return this
        if (photo.bytes.isEmpty())
            throw JmapError.RequestRejected("invalidProperties", 0, "That photo is empty.")
        val blobId = uploadPhoto(photo)
        return copy(photo = PhotoChange.New(blobId))
    }

    companion object {
        /** Address books are small; one page covers nearly all of them. */
        const val PAGE_SIZE = 200

        /** Photo bytes held in memory, keyed by photo URL. ~32 photos at the server's 512px. */
        const val PHOTO_CACHE_SIZE = 32
    }
}
