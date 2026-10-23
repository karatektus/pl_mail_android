package de.plmail.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reader's inbox tabs on the device — `urn:plmail:params:jmap:inboxtabs`.
 *
 * The claim throughout is the server's own: the same conversation in the same tab the web shows it
 * in, and the five built-ins exactly as before on a server that predates the extension. Each test
 * sets up something the web would show one way and asserts the phone files it the same way, against
 * a real database for the reason `FeedProjectionTest` gives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InboxTabsTest {

    private lateinit var database: PlMailDatabase

    @Before
    fun open() {
        database = inMemoryDatabase()
    }

    @After
    fun close() {
        database.close()
    }

    // -- Which tab a conversation is in -------------------------------------

    /** A server without the extension: the category is the tab, as it always was. */
    @Test
    fun `without the extension the category is the tab`() {
        assertEquals(
            "promotions",
            tabKeyOf(inboxTab = null, category = "promotions", enabled = null),
        )
        assertNull(tabKeyOf(inboxTab = null, category = null, enabled = null))
        // A token this build cannot name is no tab, rather than a guess.
        assertNull(tabKeyOf(inboxTab = null, category = "purchases", enabled = null))
    }

    @Test
    fun `with the extension the server's tab wins over the category`() {
        val enabled = setOf("primary", "promotions", "c12")

        assertEquals("c12", tabKeyOf(inboxTab = "c12", category = "promotions", enabled = enabled))
    }

    /**
     * A tab the cache still names but the reader no longer has falls back as the server re-resolves
     * it: to the category where that is on, and to Primary where it is not.
     */
    @Test
    fun `a tab that is gone falls back to the category, then to primary`() {
        val enabled = setOf("primary", "promotions")

        assertEquals("promotions", tabKeyOf("c12", "promotions", enabled))
        assertEquals("primary", tabKeyOf("forums", "forums", enabled))
        assertEquals("primary", tabKeyOf(null, "forums", enabled))
        assertNull(tabKeyOf(null, null, enabled), "never categorised is no tab, as on the web")
    }

    // -- The list a conversation is projected into --------------------------

    /**
     * The projection files by the tab, so a conversation one of the reader's filters put in
     * "Shipping" is in Shipping's list and not also in Promotions'.
     */
    @Test
    fun `a synced conversation lands in its own tab and leaves the built-in one`() = runTest {
        database.seedAccount()
        database.seedInbox()
        database.seedTabs(own = listOf("c12" to "Shipping"))
        database.seedThread(
            "t1",
            category = MailCategory.PROMOTIONS.wire,
            inboxTab = "c12",
            isInInbox = true,
        )
        database.seedCursor(MailCategory.PROMOTIONS.feedId)
        database.seedCursor(tabFeedId("c12"))
        database.seedEntry(MailCategory.PROMOTIONS.feedId, "t1")

        FeedProjection(database, emptyOutbox()).reconcile(testAccountKey, listOf("t1"))

        assertEquals(emptyList(), database.entryIds(MailCategory.PROMOTIONS.feedId))
        assertEquals(1, database.entryIds(tabFeedId("c12")).size)
    }

    /** A switched-off category is Primary's on the web, and so it is here. */
    @Test
    fun `a switched-off category is projected into primary`() = runTest {
        database.seedAccount()
        database.seedInbox()
        database.seedTabs(switchedOff = setOf(MailCategory.FORUMS))
        database.seedThread(
            "t1",
            category = MailCategory.FORUMS.wire,
            inboxTab = MailCategory.PRIMARY.wire,
            isInInbox = true,
        )
        database.seedCursor(MailCategory.PRIMARY.feedId)
        database.seedCursor(MailCategory.FORUMS.feedId)

        FeedProjection(database, emptyOutbox()).reconcile(testAccountKey, listOf("t1"))

        assertEquals(1, database.entryIds(MailCategory.PRIMARY.feedId).size)
        assertEquals(emptyList(), database.entryIds(MailCategory.FORUMS.feedId))
    }

    // -- One list (the Session's appearance `inboxTabs: false`) -------------

    /**
     * Where the reader wants one list, Primary holds every inbox conversation, as the web's does.
     */
    @Test
    fun `on one list every inbox conversation is projected into primary`() = runTest {
        database.seedAccount()
        database.seedInbox()
        database.seedTabs(own = listOf("c12" to "Shipping"))
        oneList()
        database.seedThread(
            "t1",
            category = MailCategory.PROMOTIONS.wire,
            inboxTab = "c12",
            isInInbox = true,
        )
        database.seedCursor(MailCategory.PRIMARY.feedId)
        database.seedCursor(tabFeedId("c12"))

        FeedProjection(database, emptyOutbox()).reconcile(testAccountKey, listOf("t1"))

        assertEquals(1, database.entryIds(MailCategory.PRIMARY.feedId).size)
        assertEquals(emptyList(), database.entryIds(tabFeedId("c12")))
    }

    /**
     * One list has no tabs to draw, to announce mail in, or to move mail into — and the sidebar
     * reads that as "no categories", which is what draws the Inbox row and titles Primary "Inbox".
     */
    @Test
    fun `on one list nothing divides the inbox`() = runTest {
        database.seedAccount()
        database.seedTabs(own = listOf("c12" to "Shipping"))
        database.seedThread(
            "t1",
            category = MailCategory.PROMOTIONS.wire,
            isNew = true,
            isInInbox = true,
            sender = "Shop",
        )
        oneList()

        val transport = serving(api = "unused")
        val labels =
            LabelRepository(
                database,
                testClients(transport),
                MailRepository(database),
                AccountsRepository(
                    database,
                    de.plmail.core.datastore.AccountPrefsStore(InMemoryPreferences()),
                    testClients(transport),
                    de.plmail.core.datastore.CredentialStore(InMemoryPreferences(), PlainCipher),
                ),
            )

        assertTrue(CategoryDigest(database).arrivals.first().isEmpty())
        assertEquals(emptyList(), repository(transport).movable.first())
        assertEquals(false, labels.observeHasCategories().first())
    }

    /** The answer comes off the Session, and a change in it is reported so Primary is re-paged. */
    @Test
    fun `the session's answer is kept on the accounts and a change is reported`() = runTest {
        val mail = MailRepository(database)
        val tabbed = session(inboxTabs = null)
        val single = session(inboxTabs = false)

        assertEquals(emptyList(), mail.replaceAccounts(TEST_SERVER, tabbed), "a new account")
        assertEquals(true, database.accounts().byUid(testAccountKey)?.wantsInboxTabs)

        assertEquals(listOf(testAccountKey), mail.replaceAccounts(TEST_SERVER, single))
        assertEquals(false, database.accounts().byUid(testAccountKey)?.wantsInboxTabs)

        assertEquals(emptyList(), mail.replaceAccounts(TEST_SERVER, single), "nothing changed")
    }

    // -- Moving a conversation into a tab -----------------------------------

    /**
     * The move is local first — out of the tab being looked at and into the one picked — and then
     * `Thread/set { inboxTab }`, declaring the extension it belongs to.
     */
    @Test
    fun `moving into a tab moves the row and tells the server`() = runTest {
        seedTabbedInbox()

        val transport = serving(threadSet(updated = true))
        val outcome =
            actions(transport)
                .apply(
                    MailAction.MoveToTab(tab = "c12", from = MailCategory.PROMOTIONS.wire),
                    listOf(ActionTarget(testAccountKey, "t1")),
                )

        assertIs<ActionOutcome.Applied>(outcome)
        assertEquals("c12", database.threads().byUid(uid("t1"))?.inboxTab)
        assertEquals(emptyList(), database.entryIds(MailCategory.PROMOTIONS.feedId))
        assertEquals(1, database.entryIds(tabFeedId("c12")).size)

        val body =
            transport.requests.single { it.url.endsWith("/jmap/api") }.body!!.decodeToString()
        assertTrue(body.contains("\"Thread/set\""), body)
        assertTrue(body.contains("\"inboxTab\":\"c12\""), body)
        assertTrue(body.contains("urn:plmail:params:jmap:inboxtabs"), body)

        // The way back is where the conversation was, read before the write.
        assertEquals(
            listOf(MailAction.MoveToTab(tab = "promotions", from = "c12")),
            outcome.undoable.steps.map { it.action },
        )
    }

    /**
     * A tab the reader no longer has is refused by the server rather than read as Primary, and that
     * has to reach the user: the row has already moved on screen.
     */
    @Test
    fun `a refused tab is reported`() = runTest {
        seedTabbedInbox()

        val outcome =
            actions(serving(threadSet(updated = false)))
                .apply(
                    MailAction.MoveToTab(tab = "c12", from = MailCategory.PROMOTIONS.wire),
                    listOf(ActionTarget(testAccountKey, "t1")),
                )

        assertIs<ActionOutcome.Rejected>(outcome)
    }

    // -- Keeping the tabs themselves up to date -----------------------------

    /** Nothing moved: one `InboxTab/changes`, the cursor advances, the rows stay. */
    @Test
    fun `a refresh with nothing changed costs one request`() = runTest {
        database.seedAccount()
        database.seedTabs()
        database.accounts().setInboxTabState(testAccountKey, "t1")

        val transport =
            serving(
                session = TABBED_SESSION,
                api =
                    """
                    {"sessionState":"s","methodResponses":[
                      ["InboxTab/changes",{"accountId":"$TEST_ACCOUNT_ID","oldState":"t1",
                        "newState":"t2","hasMoreChanges":false,
                        "created":[],"updated":[],"destroyed":[]},"c0"]]}
                    """,
            )

        repository(transport).refresh(testAccountKey)

        assertEquals(1, transport.requests.count { it.url.endsWith("/jmap/api") })
        assertEquals("t2", database.accounts().byUid(testAccountKey)?.inboxTabState)
        assertEquals(
            MailCategory.entries.size,
            database.inboxTabs().forAccount(testAccountKey).size,
        )
    }

    /**
     * Something moved: the whole list is read again and replaces the cache, so a tab deleted on the
     * web leaves and a built-in switched off there leaves the strip.
     */
    @Test
    fun `a refresh after a change replaces the tabs`() = runTest {
        database.seedAccount()
        database.seedTabs(own = listOf("c12" to "Shipping"))
        database.accounts().setInboxTabState(testAccountKey, "t1")

        val transport =
            serving(session = TABBED_SESSION) { body ->
                if (body.contains("InboxTab/changes"))
                    """
                    {"sessionState":"s","methodResponses":[
                      ["InboxTab/changes",{"accountId":"$TEST_ACCOUNT_ID","oldState":"t1",
                        "newState":"t3","hasMoreChanges":false,
                        "created":[],"updated":["forums"],"destroyed":["c12"]},"c0"]]}
                    """
                else
                    """
                    {"sessionState":"s","methodResponses":[
                      ["InboxTab/get",{"accountId":"$TEST_ACCOUNT_ID","state":"t3","list":[
                        {"id":"primary","name":null,"builtIn":"primary","isEnabled":true,"sortOrder":0},
                        {"id":"forums","name":null,"builtIn":"forums","isEnabled":false,"sortOrder":1}
                      ],"notFound":[]},"c0"]]}
                    """
            }

        val repository = repository(transport)
        repository.refresh(testAccountKey)

        assertEquals("t3", database.accounts().byUid(testAccountKey)?.inboxTabState)
        assertEquals(listOf("primary"), repository.strip.first().map { it.key })
    }

    // -- #52: tabs whose mail is only on the server -------------------------

    /**
     * A tab a filter on the web filled, with none of its mail on this device and none arriving: the
     * server says it holds mail, so the sidebar lists it, and it can be opened.
     */
    @Test
    fun `a tab with mail only on the server is listed, asked in the same request`() = runTest {
        database.seedAccount()
        database.seedTabs(own = listOf("c12" to "Shipping", "c13" to "Empty"))
        database.accounts().setInboxTabState(testAccountKey, "t1")

        val transport =
            serving(session = TABBED_SESSION) { body ->
                // One answer per probe, in the call ids the request used: mail
                // in c12, none in c13.
                val calls =
                    SESSION_JSON.parseToJsonElement(body)
                        .jsonObject["methodCalls"]!!
                        .jsonArray
                        .map { it.jsonArray }
                        .filter { it[0].jsonPrimitive.content == "Email/query" }
                        .joinToString("") { call ->
                            val callId = call[2].jsonPrimitive.content
                            val ids = if ("\"c12\"" in call[1].toString()) "[\"e1\"]" else "[]"
                            """,["Email/query",{"accountId":"$TEST_ACCOUNT_ID","queryState":"q","position":0,"ids":$ids},"$callId"]"""
                        }

                """{"sessionState":"s","methodResponses":[
                  ["InboxTab/changes",{"accountId":"$TEST_ACCOUNT_ID","oldState":"t1",
                    "newState":"t2","hasMoreChanges":false,
                    "created":[],"updated":[],"destroyed":[]},"c0"]$calls]}"""
            }

        repository(transport).refresh(testAccountKey)

        assertEquals(1, transport.requests.count { it.url.endsWith("/jmap/api") })
        val populated = CategoryDigest(database).populated.first()
        assertTrue("c12" in populated, populated.toString())
        assertTrue("c13" !in populated, populated.toString())
    }

    /** Re-reading the tabs keeps what the server last said, so the sidebar does not jump. */
    @Test
    fun `re-reading the tabs keeps what the server said about their mail`() = runTest {
        database.seedAccount()
        database.seedTabs(own = listOf("c12" to "Shipping"))
        database.inboxTabs().setHasMail(StoreKey.objectKey(testAccountKey, "c12"), true)

        MailRepository(database)
            .replaceInboxTabs(
                testAccountKey,
                listOf(
                    de.plmail.jmap.mail.InboxTab(id = "primary", builtIn = "primary"),
                    de.plmail.jmap.mail.InboxTab(id = "c12", name = "Shipping", sortOrder = 1),
                ),
                state = "t2",
            )

        assertTrue(
            database.inboxTabs().forAccount(testAccountKey).single { it.tabId == "c12" }.hasMail
        )
    }

    /** An older server: no request for a method it has never heard of, and the five as before. */
    @Test
    fun `a server without the extension keeps the five built-ins`() = runTest {
        database.seedAccount()

        val transport = serving(api = "unused")
        val repository = repository(transport)

        repository.refresh(testAccountKey)

        assertEquals(0, transport.requests.count { it.url.endsWith("/jmap/api") })
        assertEquals(MailTab.BUILT_IN, repository.strip.first())
        assertEquals(emptyList(), repository.movable.first())
    }

    /** A push naming a new `InboxTab` state re-reads the tabs; the state already held does not. */
    @Test
    fun `a push re-reads the tabs only when their state moved`() = runTest {
        database.seedAccount(emailState = "s5")
        database.accounts().setInboxTabState(testAccountKey, "t1")

        val refreshed = mutableListOf<String>()
        val applier =
            StateChangeApplier(
                database,
                syncStack(database, serving(api = "unused")),
                bodyPrefetcher(database, serving(api = "unused")),
                tabs = { refreshed += it },
            )

        applier.apply(mapOf(TEST_ACCOUNT_ID to mapOf("Email" to "s5", "InboxTab" to "t1")))
        assertEquals(emptyList(), refreshed)

        applier.apply(mapOf(TEST_ACCOUNT_ID to mapOf("Email" to "s5", "InboxTab" to "t2")))
        assertEquals(listOf(testAccountKey), refreshed)
    }

    // -- fixtures ------------------------------------------------------------

    /** One promotion in the inbox, on a server whose reader has a "Shipping" tab. */
    private suspend fun seedTabbedInbox() {
        database.seedAccount()
        database.seedInbox()
        database.seedTabs(own = listOf("c12" to "Shipping"))
        database.seedThread(
            "t1",
            category = MailCategory.PROMOTIONS.wire,
            inboxTab = MailCategory.PROMOTIONS.wire,
            isInInbox = true,
        )
        database.seedEntry(MailCategory.PROMOTIONS.feedId, "t1")
    }

    /** What the Session's `inboxTabs: false` leaves on the account row. */
    private suspend fun oneList() {
        val account = database.accounts().byUid(testAccountKey)!!
        database.accounts().upsert(listOf(account.copy(wantsInboxTabs = false)))
    }

    private fun session(inboxTabs: Boolean?): de.plmail.jmap.protocol.Session {
        val hint = inboxTabs?.let { ""","inboxTabs":$it""" }.orEmpty()

        return SESSION_JSON.decodeFromString(
            de.plmail.jmap.protocol.Session.serializer(),
            """
                {"capabilities":{"urn:plmail:params:jmap:appearance":{"themes":[]$hint}},
                 "accounts":{"$TEST_ACCOUNT_ID":{"name":"someone@example.com"}},
                 "apiUrl":"a","downloadUrl":"d","uploadUrl":"u"}
                """,
        )
    }

    private fun uid(threadId: String) = StoreKey.objectKey(testAccountKey, threadId)

    private suspend fun actions(transport: RecordingTransport): MailActions =
        MailActions(
            database = database,
            clients = testClients(transport),
            mail = MailRepository(database),
            outbox = emptyOutbox(),
            context = ApplicationProvider.getApplicationContext<Context>(),
        )

    private suspend fun repository(transport: RecordingTransport) =
        InboxTabRepository(database, testClients(transport), MailRepository(database))

    /** A `Thread/set` that accepted the move, or refused it the way the server refuses a key. */
    private fun threadSet(updated: Boolean): String {
        val outcome =
            if (updated) """"updated":{"t1":null},"notUpdated":{}"""
            else
                """"updated":{},"notUpdated":{"t1":{"type":"invalidProperties",""" +
                    """"description":"\"c12\" is not one of your inbox tabs."}}"""

        return """
            {"sessionState":"s","methodResponses":[
              ["Thread/set",{"accountId":"$TEST_ACCOUNT_ID","newState":"s2",$outcome},"c0"]]}
            """
    }

    private fun serving(api: String, session: String = TEST_SESSION): RecordingTransport =
        serving(session) { api }

    private fun serving(session: String, api: (String) -> String): RecordingTransport =
        RecordingTransport { request ->
            val body =
                if (request.url.endsWith("/.well-known/jmap")) session
                else api(request.body?.decodeToString().orEmpty())

            HttpResponse(200, mapOf("Content-Type" to "application/json"), body.encodeToByteArray())
        }

    private companion object {
        val SESSION_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        /** [TEST_SESSION] with the account advertising the inbox tabs. */
        val TABBED_SESSION =
            """
            {
              "capabilities": {
                "urn:ietf:params:jmap:core": {},
                "urn:plmail:params:jmap:inboxtabs": {}
              },
              "accounts": {"$TEST_ACCOUNT_ID": {"name": "someone@example.com",
                "accountCapabilities": {"urn:plmail:params:jmap:inboxtabs": {}}}},
              "username": "someone@example.com",
              "apiUrl": "$TEST_SERVER/jmap/api",
              "downloadUrl": "$TEST_SERVER/jmap/download",
              "uploadUrl": "$TEST_SERVER/jmap/upload"
            }
            """
    }
}
