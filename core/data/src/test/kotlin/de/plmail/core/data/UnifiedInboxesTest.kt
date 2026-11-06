package de.plmail.core.data

import de.plmail.core.database.AccountEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.ServerConnection
import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.client.ServerAddress
import de.plmail.jmap.mail.UnifiedInbox
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reader's unified inboxes (pl_mail#265): what is cached, what the navigation draws, and the
 * lists each one keeps of its own.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36, for the reason `core/ui`'s screenshot tests give.
@Config(sdk = [36])
class UnifiedInboxesTest {

    private lateinit var database: PlMailDatabase

    private val second = StoreKey.account(TEST_SERVER, "14")
    private val primary = MailCategory.PRIMARY.feedId

    @Before
    fun open() {
        database = inMemoryDatabase()
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun `a scoped feed id names its inbox and the list it is a part of`() {
        val id = scopedFeedId("12", primary)

        assertEquals("ui.12/category.primary", id)
        assertEquals(primary, baseFeedId(id))
        assertEquals("12", inboxOfFeed(id))
        assertEquals(primary, baseFeedId(primary))
        assertNull(inboxOfFeed(primary))
    }

    @Test
    fun `the inboxes come back in the reader's order with only the accounts this device has`() =
        runTest {
            seedAccounts()

            repository()
                .store(
                    TEST_SERVER,
                    listOf(
                        UnifiedInbox("2", "Personal", "house", 1, accountIds = listOf("14")),
                        UnifiedInbox("1", null, "briefcase", 0, accountIds = listOf("13", "99")),
                    ),
                )

            val inboxes = repository().inboxes.first()

            assertEquals(listOf("1", "2"), inboxes.map { it.id })
            assertEquals(setOf(testAccountKey), inboxes[0].accountKeys)
            assertNull(inboxes[0].name, "no name is the app's own word")
            assertEquals(setOf(second), inboxes[1].accountKeys)
        }

    @Test
    fun `other is left out while none of its accounts is here, and a named inbox is not`() =
        runTest {
            seedAccounts()

            repository()
                .store(
                    TEST_SERVER,
                    listOf(
                        UnifiedInbox("1", null, accountIds = listOf("13", "14")),
                        UnifiedInbox("2", "Empty", sortOrder = 1),
                        UnifiedInbox(
                            "3",
                            null,
                            sortOrder = 2,
                            isOther = true,
                            accountIds = listOf("99"),
                        ),
                    ),
                )

            assertEquals(listOf("1", "2"), repository().inboxes.first().map { it.id })
        }

    @Test
    fun `one inbox is drawn as the inbox the app has always had`() = runTest {
        seedAccounts()

        repository()
            .store(
                TEST_SERVER,
                listOf(
                    UnifiedInbox("1", null, accountIds = listOf("13", "14")),
                    UnifiedInbox("3", null, sortOrder = 1, isOther = true),
                ),
            )

        assertEquals(emptyList(), repository().navigable.first())
    }

    @Test
    fun `the unread count is the inbox of its accounts only`() = runTest {
        seedAccounts()
        database.seedInbox()
        database.seedInbox(second)
        database.mailboxes().forAccount(testAccountKey).single().let {
            database.mailboxes().upsert(listOf(it.copy(unreadThreads = 3)))
        }
        database.mailboxes().forAccount(second).single().let {
            database.mailboxes().upsert(listOf(it.copy(unreadThreads = 5)))
        }

        repository().store(TEST_SERVER, twoInboxes())

        assertEquals(mapOf("1" to 3, "2" to 5), repository().unread.first())
    }

    @Test
    fun `an inbox whose accounts changed has its lists emptied, and the others keep theirs`() =
        runTest {
            seedAccounts()
            repository().store(TEST_SERVER, twoInboxes())

            val work = scopedFeedId("1", primary)
            val personal = scopedFeedId("2", primary)
            database.seedEntry(work, "t1")
            database.seedCursor(work)
            database.seedEntry(personal, "t2", accountKey = second)
            database.seedCursor(personal, accountKey = second)

            repository()
                .store(
                    TEST_SERVER,
                    listOf(
                        UnifiedInbox("1", "Work", accountIds = listOf("13", "14")),
                        UnifiedInbox("2", "Personal", sortOrder = 1, accountIds = listOf("14")),
                    ),
                )

            assertEquals(emptyList(), database.entryIds(work))
            assertNull(database.feed().cursor(work, testAccountKey))
            assertEquals(1, database.entryIds(personal).size)
        }

    @Test
    fun `a server without the extension keeps no inboxes`() = runTest {
        seedAccounts()
        repository().store(TEST_SERVER, twoInboxes())

        repository().store(TEST_SERVER, null)

        assertEquals(emptyList(), repository().inboxes.first())
    }

    @Test
    fun `a refresh asks the first account that serves the inboxes`() = runTest {
        seedAccounts()

        val transport = RecordingTransport { request ->
            val body =
                if (request.url.endsWith("/.well-known/jmap")) SESSION
                else
                    """
                    {"sessionState":"s","methodResponses":[
                      ["UnifiedInbox/get",{"accountId":"14","state":"u1","list":[
                        {"id":"1","name":"Work","icon":"briefcase","sortOrder":0,"isOther":false,"accountIds":["13"]},
                        {"id":"2","name":null,"icon":"boxes-stacked","sortOrder":1,"isOther":true,"accountIds":["14"]}
                      ],"notFound":[]},"c0"]]}
                    """

            HttpResponse(200, mapOf("Content-Type" to "application/json"), body.encodeToByteArray())
        }

        repository(transport).refresh()

        val api = transport.requests.single { it.url.endsWith("/jmap/api") }.body!!.decodeToString()
        assertTrue(api.contains("\"UnifiedInbox/get\""), api)
        assertTrue(api.contains("urn:plmail:params:jmap:unifiedinboxes"), api)
        assertTrue(api.contains("\"accountId\":\"14\""), "13 does not advertise it: $api")

        val inboxes = repository().inboxes.first()
        assertEquals(listOf("Work", null), inboxes.map { it.name })
        assertEquals(listOf("briefcase", "boxes-stacked"), inboxes.map { it.icon })
        assertTrue(inboxes[1].isOther)
    }

    @Test
    fun `a synced conversation reaches its inbox's own list, and only where it is paged`() =
        runTest {
            seedAccounts()
            database.seedInbox()

            val work = scopedFeedId("1", primary)
            val personal = scopedFeedId("2", primary)
            database.seedCursor(work)
            // Paged for the other account only: this account is not in Personal.
            database.seedCursor(personal, accountKey = second)
            database.seedThread("t1")

            FeedProjection(database, emptyOutbox()).reconcile(testAccountKey, listOf("t1"))

            assertEquals(
                listOf("$work#${StoreKey.objectKey(testAccountKey, "t1")}"),
                database.entryIds(work),
            )
            assertEquals(emptyList(), database.entryIds(personal))
        }

    @Test
    fun `a conversation that leaves the inbox leaves the inbox's own lists`() = runTest {
        seedAccounts()
        database.seedInbox()

        val work = scopedFeedId("1", primary)
        database.seedCursor(work)
        database.seedEntry(work, "t1")
        // No longer carrying the inbox binding.
        database.seedThread("t1", labelKeys = "")

        FeedProjection(database, emptyOutbox()).reconcile(testAccountKey, listOf("t1"))

        assertEquals(emptyList(), database.entryIds(work))
    }

    @Test
    fun `archiving takes a conversation out of every inbox's lists`() = runTest {
        val work = scopedFeedId("1", primary)
        val workSocial = scopedFeedId("1", MailCategory.SOCIAL.feedId)
        database.seedEntry(work, "t1")
        database.seedEntry(workSocial, "t1")
        database.seedEntry(MailCategory.PRIMARY.feedId, "t2")

        database.clearFromInboxLists(testAccountKey, "t1")

        assertEquals(emptyList(), database.entryIds(work))
        assertEquals(emptyList(), database.entryIds(workSocial))
        assertEquals(1, database.entryIds(MailCategory.PRIMARY.feedId).size)
    }

    // -- helpers -----------------------------------------------------------

    private fun twoInboxes() =
        listOf(
            UnifiedInbox("1", "Work", accountIds = listOf("13")),
            UnifiedInbox("2", "Personal", sortOrder = 1, accountIds = listOf("14")),
        )

    private suspend fun seedAccounts() {
        database.seedAccount()
        database
            .accounts()
            .upsert(
                listOf(
                    AccountEntity(
                        uid = second,
                        serverId = TEST_SERVER,
                        accountId = "14",
                        name = "other@example.com",
                        sortIndex = 1,
                    )
                )
            )
    }

    private suspend fun repository(
        transport: RecordingTransport = RecordingTransport { error("no request expected") }
    ): UnifiedInboxRepository {
        val credentials = CredentialStore(InMemoryPreferences(), PlainCipher)
        val clients = testClients(transport)

        credentials.save(
            ServerConnection(
                address = (ServerAddress.parse(TEST_SERVER) as ParsedAddress.Valid).address,
                credential = Credential.AppPassword("plmail_" + "a".repeat(64)),
                username = "someone@example.com",
            )
        )

        return UnifiedInboxRepository(database, clients, credentials)
    }

    private companion object {
        val SESSION =
            """
            {
              "capabilities": {"urn:ietf:params:jmap:core": {},
                "urn:plmail:params:jmap:unifiedinboxes": {"icons": ["inbox", "briefcase"]}},
              "accounts": {
                "13": {"name": "someone@example.com", "accountCapabilities": {}},
                "14": {"name": "other@example.com",
                  "accountCapabilities": {"urn:plmail:params:jmap:unifiedinboxes": {}}}
              },
              "username": "someone@example.com",
              "apiUrl": "$TEST_SERVER/jmap/api",
              "downloadUrl": "$TEST_SERVER/jmap/download",
              "uploadUrl": "$TEST_SERVER/jmap/upload"
            }
            """
    }
}
