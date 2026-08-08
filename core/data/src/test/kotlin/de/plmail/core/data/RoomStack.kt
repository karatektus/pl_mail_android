package de.plmail.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.plmail.core.database.AccountEntity
import de.plmail.core.database.FeedCursorEntity
import de.plmail.core.database.FeedEntryEntity
import de.plmail.core.database.MailboxEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.database.ThreadEntity
import de.plmail.core.datastore.AccountPrefsStore
import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.OutboxStore
import de.plmail.core.datastore.ScheduledSendStore
import de.plmail.core.datastore.SealedSecret
import de.plmail.core.datastore.SecretCipher
import de.plmail.core.datastore.ServerConnection
import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.JmapTransport
import de.plmail.jmap.client.KeyFingerprint
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.client.ServerAddress
import de.plmail.jmap.client.StreamingTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The database the feed tests are actually about, on the JVM.
 *
 * `FeedProjection`, `FeedMediator` and `DeltaSync` are each defined entirely by what they leave in
 * `feed_entries`, `feed_cursors` and `accounts` — so a fake DAO would assert only that the test's
 * own idea of those tables agrees with itself, which is the shape of test that let every bug this
 * file's suite pins ship in the first place. Room's in-memory builder runs fine under Robolectric,
 * which is what keeps these in the suite that runs on every build.
 *
 * `allowMainThreadQueries` because there is no main thread to protect here and the alternative is
 * every test scheduling its own executor.
 */
internal fun inMemoryDatabase(): PlMailDatabase =
    Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PlMailDatabase::class.java,
        )
        .allowMainThreadQueries()
        .build()

/** The one server every fixture below is on. */
internal const val TEST_SERVER = "https://nas.local"

/** The JMAP account id the canned session publishes, and the key it collapses to. */
internal const val TEST_ACCOUNT_ID = "13"

internal val testAccountKey: String = StoreKey.account(TEST_SERVER, TEST_ACCOUNT_ID)

/**
 * The Inbox binding id, and therefore the collapse key a thread carries when it is in the inbox.
 */
internal const val INBOX_MAILBOX_ID = "1"

internal val inboxLabelKey: String = StoreKey.objectKey(testAccountKey, INBOX_MAILBOX_ID)

/**
 * An account row with a delta cursor already set.
 *
 * Set rather than null by default because a null one is a *case* — it is what
 * `FeedMediator.initialize` re-pages on and what `MailRepository.recordEmailState` is allowed to
 * fill — so leaving it null everywhere would make those tests pass for the wrong reason.
 */
internal suspend fun PlMailDatabase.seedAccount(
    accountKey: String = testAccountKey,
    emailState: String? = "s5",
) {
    accounts()
        .upsert(
            listOf(
                AccountEntity(
                    uid = accountKey,
                    serverId = TEST_SERVER,
                    accountId = TEST_ACCOUNT_ID,
                    name = "someone@example.com",
                    emailState = emailState,
                )
            )
        )
}

/** The Inbox binding, which is what `byRole` resolves and what `labelKeys` is written from. */
internal suspend fun PlMailDatabase.seedInbox(accountKey: String = testAccountKey) {
    mailboxes()
        .upsert(
            listOf(
                MailboxEntity(
                    uid = StoreKey.objectKey(accountKey, INBOX_MAILBOX_ID),
                    accountKey = accountKey,
                    mailboxId = INBOX_MAILBOX_ID,
                    name = "Inbox",
                    role = "inbox",
                )
            )
        )
}

/**
 * A conversation as `storeEmails` would have left it.
 *
 * [labelKeys] is the collapse-key form the projection reads, so a thread "in the inbox" is one
 * carrying [inboxLabelKey] — the same string `MailRepository` derives from the message's
 * `mailboxIds`.
 */
internal suspend fun PlMailDatabase.seedThread(
    threadId: String,
    accountKey: String = testAccountKey,
    labelKeys: String = inboxLabelKey,
    category: String? = null,
    receivedAt: Long = 5_000,
) {
    threads()
        .upsert(
            listOf(
                ThreadEntity(
                    uid = StoreKey.objectKey(accountKey, threadId),
                    accountKey = accountKey,
                    threadId = threadId,
                    latestReceivedAt = receivedAt,
                    labelKeys = labelKeys,
                    category = category,
                )
            )
        )
}

/**
 * Marks a list as one this account has been paged into.
 *
 * The cursor row *is* the liveness signal — see `FeedDao.feedsPagedBy` — so a feed without one is
 * not a feed with an empty window, it is a list nobody has opened.
 */
internal suspend fun PlMailDatabase.seedCursor(
    feedId: String,
    accountKey: String = testAccountKey,
    lastSortDate: Long? = null,
    isExhausted: Boolean = false,
) {
    feed()
        .upsertCursor(
            FeedCursorEntity(
                uid = "$feedId#$accountKey",
                feedId = feedId,
                accountKey = accountKey,
                lastSortDate = lastSortDate,
                isExhausted = isExhausted,
            )
        )
}

/** A row in a list, keyed exactly as [FeedMediator] and [FeedProjection] both write it. */
internal suspend fun PlMailDatabase.seedEntry(
    feedId: String,
    threadId: String,
    accountKey: String = testAccountKey,
    sortDate: Long = 5_000,
) {
    feed()
        .upsertEntries(
            listOf(
                FeedEntryEntity(
                    uid = "$feedId#${StoreKey.objectKey(accountKey, threadId)}",
                    feedId = feedId,
                    sortDate = sortDate,
                    accountKey = accountKey,
                    threadId = threadId,
                    emailId = threadId,
                )
            )
        )
}

/**
 * Every row id one list holds.
 *
 * Read as raw SQL rather than through a DAO because the *key* is what several of these tests are
 * about: a row keyed any other way than `"$feedId#${StoreKey.objectKey(accountKey, threadId)}"` is
 * a second copy of the conversation that nothing can ever delete, and a DAO that returned entities
 * by feed would hide exactly that.
 */
internal fun PlMailDatabase.entryIds(feedId: String): List<String> =
    openHelper.writableDatabase
        .query("SELECT uid FROM feed_entries WHERE feedId = ? ORDER BY uid", arrayOf<Any?>(feedId))
        .use { row -> buildList { while (row.moveToNext()) add(row.getString(0)) } }

/** A `DataStore` that is a variable. Nothing here is testing DataStore's file handling. */
internal class InMemoryPreferences : DataStore<Preferences> {
    private val current = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences>
        get() = current

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
        current.value = transform(current.value)

        return current.value
    }
}

/**
 * A [SecretCipher] that seals nothing.
 *
 * The real one is the Android Keystore, which has no JVM provider and is not what any of this is
 * about — the interface exists precisely so the code around it can be exercised off-device.
 */
internal object PlainCipher : SecretCipher {
    override fun seal(plaintext: String): SealedSecret = SealedSecret(plaintext)

    override fun open(sealed: SealedSecret): String? = sealed.encoded
}

/** An outbox with nothing queued, for the paths that only consult it. */
internal fun emptyOutbox(): Outbox = Outbox(OutboxStore(InMemoryPreferences())) { emptyMap() }

/**
 * A real [DeltaSync], over a real database, answering out of a canned transport.
 *
 * Assembled here rather than in each test because the collaborators are the point: the sync only
 * becomes visible through [FeedProjection], and the projection only writes lists the paging cursors
 * say are live, so a sync test built on doubles for either would pass against the exact code that
 * shipped the bug. Everything below the client — credentials, the prefs the notification gate reads
 * — is a fake, because none of it is what any of these tests are about.
 */
internal suspend fun syncStack(
    database: PlMailDatabase,
    transport: JmapTransport,
    outbox: Outbox = emptyOutbox(),
): DeltaSync {
    val preferences = InMemoryPreferences()
    val credentials = CredentialStore(preferences, PlainCipher)

    credentials.save(
        ServerConnection(
            address = (ServerAddress.parse(TEST_SERVER) as ParsedAddress.Valid).address,
            credential = Credential.AppPassword("plmail_" + "a".repeat(64)),
            username = "someone@example.com",
        )
    )

    val clients = AccountClients(credentials, fixedTransports(transport))

    return DeltaSync(
        database = database,
        clients = clients,
        mail = MailRepository(database),
        accounts =
            AccountsRepository(database, AccountPrefsStore(preferences), clients, credentials),
        projection = FeedProjection(database, outbox),
        repages = RepageSignal(),
        reachable = {},
        // A reconcile over a directory with no accounts, which does nothing and
        // asks nothing. The sync tests are about `feed_entries`; the schedule
        // has its own suite, and wiring the real one here would put an
        // `EmailSubmission/changes` into every canned transport script.
        schedules = ScheduledSendReconciler(scheduledSends(), NoSubmissions),
    )
}

/** A factory that hands out the one canned transport, whatever it is asked for. */
internal fun fixedTransports(transport: JmapTransport): TransportFactory =
    object : TransportFactory {
        override fun create(address: ServerAddress, pinned: KeyFingerprint?): JmapTransport =
            transport

        override fun createStreaming(
            address: ServerAddress,
            pinned: KeyFingerprint?,
        ): StreamingTransport = error("no stream is opened on this path")
    }

/** An [AccountClients] pointed at [TEST_SERVER], answering out of a canned transport. */
internal suspend fun testClients(transport: JmapTransport): AccountClients {
    val credentials = CredentialStore(InMemoryPreferences(), PlainCipher)

    credentials.save(
        ServerConnection(
            address = (ServerAddress.parse(TEST_SERVER) as ParsedAddress.Valid).address,
            credential = Credential.AppPassword("plmail_" + "a".repeat(64)),
            username = "someone@example.com",
        )
    )

    return AccountClients(credentials, fixedTransports(transport))
}

/** A real [BodyPrefetcher] over a real database, answering out of a canned transport. */
internal suspend fun bodyPrefetcher(
    database: PlMailDatabase,
    transport: JmapTransport,
): BodyPrefetcher = BodyPrefetcher(database, testClients(transport), MailRepository(database))

/** A [ScheduledSends] over a store that is a variable. */
internal fun scheduledSends(): ScheduledSends =
    ScheduledSends(ScheduledSendStore(InMemoryPreferences()))

/** A server with no accounts, for the paths that only have to not fall over. */
internal object NoSubmissions : SubmissionDirectory {
    override suspend fun accountKeys(): List<String> = emptyList()

    override suspend fun submissions(accountKey: String, ids: List<String>) = SubmissionSnapshot()

    override suspend fun submissionChanges(accountKey: String, sinceState: String) =
        SubmissionDelta(newState = sinceState)

    override suspend fun subjects(accountKey: String, emailIds: List<String>) =
        emptyMap<String, String>()
}

/** The session the canned transports below answer discovery with. */
internal val TEST_SESSION =
    """
    {
      "capabilities": {"urn:ietf:params:jmap:core": {}},
      "accounts": {"$TEST_ACCOUNT_ID": {"name": "someone@example.com"}},
      "username": "someone@example.com",
      "apiUrl": "$TEST_SERVER/jmap/api",
      "downloadUrl": "$TEST_SERVER/jmap/download",
      "uploadUrl": "$TEST_SERVER/jmap/upload"
    }
    """
