package de.plmail.core.data

import de.plmail.core.database.PlMailDatabase
import de.plmail.core.datastore.CredentialStore
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.methods.EmailGet
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.RequestBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Fetches a message in full, on demand.
 *
 * List pages deliberately do not carry bodies — `EmailGet.LIST_ROW_PROPERTIES` stops short of them
 * because a body can be 400KB and a page of fifty would be twenty megabytes to draw a list nobody
 * has scrolled yet. So the reader asks for the rest when a conversation is opened, and this is what
 * asks.
 *
 * Bodies land in their own table, so a later list query still never faults one in.
 */
@Singleton
class MessageLoader
@Inject
constructor(
    private val database: PlMailDatabase,
    private val credentials: CredentialStore,
    private val transports: TransportFactory,
    private val mail: MailRepository,
) {

    /**
     * Downloads the bodies for one conversation, skipping anything already cached.
     *
     * Returns silently when there is nothing to do or no connection: the reader shows what it has
     * either way, and a thread whose bodies are already on disk must not cost a round trip every
     * time it is opened.
     */
    suspend fun loadBodies(accountKey: String, threadId: String) {
        val stored = database.emails().inThread(accountKey, threadId)
        val missing = stored.filter { database.emails().body(it.uid) == null }
        if (missing.isEmpty()) return

        val connection = credentials.connection.first() ?: return
        val account = database.accounts().byUid(accountKey) ?: return

        val client =
            JmapClient(
                discoveryUrl = connection.address.discoveryUrl,
                credential = connection.credential,
                transport = transports.create(connection.address, connection.pinnedKey),
            )

        val request = RequestBuilder()
        val get =
            request.add(
                EmailGet(
                    accountId = AccountId(account.accountId),
                    ids = missing.map { EmailId(it.emailId) },
                    properties = EmailGet.READER_PROPERTIES,
                    fetchTextBodyValues = true,
                    // NOTE the capitalisation inside EmailGet: fetchHTMLBodyValues.
                    // The wrong spelling is silently ignored and returns empty
                    // body values with nothing to debug.
                    fetchHtmlBodyValues = true,
                )
            )

        val emails = client.send(request).result(get).list

        // Through the repository, so bodies and attachments are written the same
        // way a sync writes them and the thread summary is recomputed once.
        mail.storeEmails(accountKey, emails, fetchedAt = System.currentTimeMillis())
    }
}
