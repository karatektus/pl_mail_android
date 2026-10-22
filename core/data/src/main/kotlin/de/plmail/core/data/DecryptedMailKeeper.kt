package de.plmail.core.data

import de.plmail.crypto.DecryptionException
import de.plmail.crypto.EncryptedMessages
import de.plmail.crypto.UnlockedAddressKey
import de.plmail.jmap.client.JmapClient
import de.plmail.jmap.methods.DecryptedMailPending
import de.plmail.jmap.methods.DecryptedMailStore
import de.plmail.jmap.methods.EmailGet
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.Capability
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.JmapError
import de.plmail.jmap.protocol.RequestBuilder
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Decrypts, in the background, the encrypted mail of accounts that keep decrypted mail on the
 * server, and hands each message over (#9, phase 3).
 *
 * The person switched this on in the web app, per account: the server then holds a readable copy of
 * their encrypted mail so search, rules, parcels and dates work on it. It cannot make that copy
 * itself — it has no keys — so a device that does hands it the decrypted inner message
 * (`DecryptedMail/pending`, upload, `DecryptedMail/store`), exactly as the browser does
 * (`decrypted_mail_controller.js`). The server parses and sanitises it; nothing decrypted is kept
 * here.
 *
 * Runs after a sync — the periodic one and the one a push triggers — so it works with the screen
 * off, which is why the end-to-end keys need no unlock by default. Bounded per run, and quiet: a
 * message that will not open (a key long gone) is left for the person to see when they open it, and
 * the run stops when a round stores nothing rather than asking the same ids again.
 */
@Singleton
class DecryptedMailKeeper
@Inject
constructor(
    private val clients: AccountClients,
    private val encryption: EncryptionRepository,
) {

    private val running = Mutex()

    /**
     * Works through what is pending, for [only] one account or for every account that keeps
     * decrypted mail. Returns how many messages were handed over. Never throws but on cancellation.
     */
    suspend fun keep(only: AccountId? = null): Int {
        // One run at a time: the periodic sync and a push can arrive together,
        // and two runs would decrypt and upload the same messages twice.
        if (!running.tryLock()) return 0

        return try {
            val client = clients.current() ?: return 0
            val session = client.session()
            val capability = session.keys ?: return 0
            if (!capability.decryptedMailAllowed) return 0

            val accounts =
                session.accountIds.filter {
                    (only == null || it == only) && session.keepsDecryptedMail(it)
                }
            if (accounts.isEmpty()) return 0

            val keys = encryption.unlockedAddressKeys()
            if (keys.isEmpty()) return 0

            accounts.sumOf { account ->
                keepFor(client, account, keys, capability.maxDecryptedMailSize)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (refused: JmapError.NotAuthenticated) {
            encryption.forget()
            0
        } catch (_: KeysLocked) {
            // Protected keys, and nobody here to confirm it is them: paused
            // until the person opens encrypted mail in the app (phase 5).
            0
        } catch (_: Exception) {
            // Unreachable, or the server is mid-restart: next sync.
            0
        } finally {
            running.unlock()
        }
    }

    private suspend fun keepFor(
        client: JmapClient,
        account: AccountId,
        keys: List<UnlockedAddressKey>,
        maxSize: Long,
    ): Int {
        val tried = mutableSetOf<String>()
        var kept = 0

        repeat(MAX_ROUNDS) {
            val ask = RequestBuilder(using = USING)
            val handle = ask.add(DecryptedMailPending(account, BATCH))
            val pending = client.send(ask).result(handle).ids.filterNot { it in tried }

            if (pending.isEmpty()) return kept
            tried += pending

            val blobs = blobIds(client, account, pending)
            val uploaded = mutableMapOf<String, String>()

            pending.forEach { emailId ->
                val source = blobs[emailId] ?: return@forEach
                val mime = decrypt(client, account, source, keys) ?: return@forEach
                if (maxSize > 0 && mime.size > maxSize) return@forEach

                runCatching { client.upload(mime, MIME_TYPE, account).blobId }
                    .getOrNull()
                    ?.let { uploaded[emailId] = it }
            }

            if (uploaded.isEmpty()) return kept

            val request = RequestBuilder(using = USING)
            val store = request.add(DecryptedMailStore(account, uploaded))
            kept += client.send(request).result(store).stored.size
        }

        return kept
    }

    /** The source blob of each message: the message as it arrived, ciphertext. */
    private suspend fun blobIds(
        client: JmapClient,
        account: AccountId,
        ids: List<String>,
    ): Map<String, String> {
        val request = RequestBuilder()
        val get =
            request.add(
                EmailGet(
                    accountId = account,
                    ids = ids.map(::EmailId),
                    properties = listOf("id", "blobId"),
                )
            )

        return client
            .send(request)
            .result(get)
            .list
            .mapNotNull { email ->
                email.blobId?.value?.let { email.id.value to it }
            }
            .toMap()
    }

    private suspend fun decrypt(
        client: JmapClient,
        account: AccountId,
        blobId: String,
        keys: List<UnlockedAddressKey>,
    ): ByteArray? =
        try {
            val raw = ByteArrayOutputStream()
            client.download(account, blobId, name = SOURCE_NAME, type = SOURCE_TYPE, sink = raw)

            withContext(Dispatchers.Default) {
                // No signature checking here: the server is being handed the
                // message, not judging it, and the reader checks when it opens.
                EncryptedMessages.open(raw.toByteArray(), keys).mime.takeIf { it.isNotEmpty() }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: DecryptionException) {
            null
        } catch (_: Exception) {
            null
        }

    private companion object {
        /** How many pending ids one round asks for; one store call takes up to fifty. */
        const val BATCH = 25

        /** Rounds per run: up to a hundred messages, then the next sync carries on. */
        const val MAX_ROUNDS = 4

        const val MIME_TYPE = "message/rfc822"
        const val SOURCE_NAME = "message.eml"
        const val SOURCE_TYPE = "message/rfc822"

        val USING = listOf(Capability.CORE, Capability.KEYS)
    }
}
