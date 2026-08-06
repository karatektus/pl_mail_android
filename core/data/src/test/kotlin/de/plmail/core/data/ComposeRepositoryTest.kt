package de.plmail.core.data

import androidx.test.core.app.ApplicationProvider
import de.plmail.core.database.AccountEntity
import de.plmail.core.database.IdentityEntity
import de.plmail.core.database.PlMailDatabase
import de.plmail.core.database.StoreKey
import de.plmail.core.datastore.AccountPrefsStore
import de.plmail.core.datastore.CredentialStore
import de.plmail.core.datastore.ServerConnection
import de.plmail.jmap.client.Credential
import de.plmail.jmap.client.HttpResponse
import de.plmail.jmap.client.JmapTransport
import de.plmail.jmap.client.KeyFingerprint
import de.plmail.jmap.client.ParsedAddress
import de.plmail.jmap.client.ServerAddress
import de.plmail.jmap.client.StreamingTransport
import de.plmail.jmap.testing.RecordingTransport
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The composer's refusals, and the alias the mail actually goes out as.
 *
 * Two clusters, and both are untested territory the 2026-08-06 compose batch left behind.
 *
 * **The refusals.** `Email/set` and `EmailSubmission/set` report per-object failures inside a
 * perfectly successful 200, so every one of these is a path where doing nothing looks like doing
 * the right thing. `forbiddenFrom` is the sharpest: the server's own wording names an *id*, which
 * is not a thing anybody picked, so it has to be turned into a sentence naming the address — and
 * the identity list has to be re-read in the same breath, or the picker goes on offering the alias
 * that was just refused. `invalidProperties` on a patch carrying attachments refuses the **whole**
 * patch, subject and all, so the message has to say that nothing was saved rather than leaving
 * somebody to discover it.
 *
 * **The aliases.** The 8002 seed has no alias rows at all — each account yields one synthetic
 * identity for its own address — so per-alias behaviour has never met wire truth. The fixtures
 * below carry the synthetic shape *captured from 8002 on 2026-08-06*, including the seed's own
 * quirk that account 1's `email` is the display name "E2E Mailbox" and does not parse as an
 * address; the multi-alias case is constructed from it per the documented contract (one identity
 * per sendable alias, primary first). Both matter: the picker must not assume an address parses,
 * and `loadDraft` must match the draft's existing From rather than taking the first identity, or
 * reopening a draft written from an alias quietly moves it back to the main address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposeRepositoryTest {

    private lateinit var database: PlMailDatabase

    @Before
    fun setUp() {
        database = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------- the From picker

    @Test
    fun `the synthetic identity 8002 actually serves survives a picker that expects an address`() =
        runTest {
            // Captured from 8002 on 2026-08-06: `SeedTestEmailCommand` writes the
            // display name into the account's email column, so `Identity/get`
            // answers `{"email":"E2E Mailbox"}`. Anything that split on `@` --
            // to show a domain, to guess a local part -- produces nonsense here,
            // and this is the account every developer on this project tests
            // against.
            seedAccounts(single = true)
            seedIdentities(SYNTHETIC_8002)

            val picker = repository().identities().first()

            assertEquals(1, picker.size)
            assertEquals("E2E Mailbox", picker.single().email)
            // Name equal to address collapses to one, rather than
            // "E2E Mailbox <E2E Mailbox>".
            assertEquals("E2E Mailbox", picker.single().label)
        }

    @Test
    fun `every sendable alias is an entry, in the order the server published them`() = runTest {
        // The change the `identityId` adoption bought. The picker used to
        // collapse to one entry per account, because a submission ignored the
        // identity and offering four addresses that all sent as one would have
        // been a lie.
        seedAccounts(single = true)
        seedIdentities(MULTI_ALIAS)

        val picker = repository().identities().first()

        assertEquals(
            listOf("anna@example.test", "rechnung@example.test", "anna+lists@example.test"),
            picker.map { it.email },
        )
        // Primary first, as `Identity/get` publishes it, and not re-sorted here:
        // the composer opens on the first entry, so re-ordering would change
        // which address a new message goes out as.
        assertEquals("1", picker.first().identityId)
    }

    @Test
    fun `an alias carries its own display name into the row`() = runTest {
        seedAccounts(single = true)
        seedIdentities(MULTI_ALIAS)

        val picker = repository().identities().first()

        assertEquals("Rechnungen <rechnung@example.test>", picker[1].label)
    }

    @Test
    fun `the account's name rides along so a second account can be told apart`() = runTest {
        // Drawn beside an entry only when more than one account is connected --
        // that decision is the composer's, and what is pinned here is that the
        // information reaches it at all, per identity rather than per account.
        seedAccounts(single = false)
        seedIdentities(MULTI_ALIAS)
        seedIdentities(SECOND_ACCOUNT_ALIAS, accountKey = secondAccountKey)

        val picker = repository().identities().first()

        // "Second" leads because nobody has arranged the accounts yet and the
        // DAO's fallback is `ORDER BY sortIndex, name` — a detail worth pinning
        // rather than working around, because the next test is what happens once
        // somebody *has* arranged them.
        assertEquals(
            listOf("Second", "someone@example.com", "someone@example.com", "someone@example.com"),
            picker.map { it.accountName },
        )
        // And the name is per identity rather than per account, which is what
        // lets three aliases of one account be told from a fourth address that
        // is a different mailbox entirely.
        assertEquals("second@e2e.test", picker.first().email)
    }

    @Test
    fun `the user's account order decides which address a new message opens on`() = runTest {
        // Somebody who has put their personal account at the top of the settings
        // screen has already answered "which mailbox does a new message come
        // from". The identity table's order is the *server's*.
        seedAccounts(single = false)
        seedIdentities(MULTI_ALIAS)
        seedIdentities(SECOND_ACCOUNT_ALIAS, accountKey = secondAccountKey)

        val prefs = AccountPrefsStore(InMemoryPreferences())

        prefs.setOrder(listOf(secondAccountKey, testAccountKey))

        val picker = repository(prefs = prefs).identities().first()

        assertEquals("second@e2e.test", picker.first().email)
        assertEquals(4, picker.size)
    }

    @Test
    fun `an identity whose account has gone is dropped rather than drawn without one`() = runTest {
        // An account removed while a composer was open. A row with no account
        // has no name to show and no client to send through.
        seedAccounts(single = true)
        seedIdentities(MULTI_ALIAS)
        seedIdentities(SECOND_ACCOUNT_ALIAS, accountKey = secondAccountKey)

        assertEquals(3, repository().identities().first().size)
    }

    // ------------------------------------------------------------ loadDraft

    @Test
    fun `reopening a draft written from an alias keeps that alias`() = runTest {
        // The defect this matching exists for. "The first identity" is the
        // primary now that there is one per alias, so taking it would silently
        // move a draft written as `rechnung@` back to `anna@` on the next save
        // -- and the user would only find out from the copy in Sent.
        seedAccounts(single = true)
        seedIdentities(MULTI_ALIAS)

        val draft =
            repository(draftFrom("Rechnungen", "rechnung@example.test"))
                .loadDraft(testAccountKey, "42")

        assertEquals("2", assertNotNull(draft).identityId)
    }

    @Test
    fun `the match ignores case and surrounding space, as an address comparison must`() = runTest {
        // Round-tripped through a server and a header, an address comes back
        // however the sender spelled it. A case-sensitive match here would
        // fall through to the primary and look exactly like no matching at
        // all.
        seedAccounts(single = true)
        seedIdentities(MULTI_ALIAS)

        val draft =
            repository(draftFrom("Rechnungen", " Rechnung@Example.TEST "))
                .loadDraft(testAccountKey, "42")

        assertEquals("2", assertNotNull(draft).identityId)
    }

    @Test
    fun `a draft from an address that is no longer an alias falls back to the primary`() = runTest {
        // An alias deleted on the web since the draft was written. Falling
        // back is right -- the alternative is a composer that cannot open --
        // and the primary is the honest choice, because it is the one address
        // the account definitely still has.
        seedAccounts(single = true)
        seedIdentities(MULTI_ALIAS)

        val draft =
            repository(draftFrom("Gone", "deleted@example.test")).loadDraft(testAccountKey, "42")

        assertEquals("1", assertNotNull(draft).identityId)
    }

    @Test
    fun `an account with no identities at all cannot open a draft`() = runTest {
        // Rather than a composer with an empty From row that fails on save.
        seedAccounts(single = true)

        assertNull(
            repository(draftFrom("Anna", "anna@example.test")).loadDraft(testAccountKey, "42")
        )
    }

    // ------------------------------------------------------- refused sends

    @Test
    fun `a forbiddenFrom names the address the user picked, not the id the server named`() =
        runTest {
            // "Identity 3 is not a sendable address of this account" is the
            // server's wording, verified on 8002. Nobody picked an id.
            seedAccounts(single = true)
            seedIdentities(MULTI_ALIAS)

            val transport = scripted(FORBIDDEN_FROM_RESULT, IDENTITY_GET_RESULT)
            val repository = repository(transport)

            val failure = runCatching {
                repository.submit(
                    ComposeDraft(
                        accountKey = testAccountKey,
                        identityId = "2",
                        emailId = "42",
                        subject = "Hello",
                    ),
                    hold = null,
                )
            }
                .exceptionOrNull()

            assertEquals(
                "This account may not send as \"rechnung@example.test\" any more. " +
                    "Pick another address and try again.",
                assertNotNull(failure).message,
            )
        }

    @Test
    fun `a forbiddenFrom re-reads the identity list so the picker stops offering it`() = runTest {
        // In the same breath, deliberately. A stale list is the commonest cause
        // of this refusal, so leaving it stale guarantees the user's next attempt
        // is refused the same way.
        seedAccounts(single = true)
        seedIdentities(MULTI_ALIAS)

        val transport = scripted(FORBIDDEN_FROM_RESULT, IDENTITY_GET_RESULT)
        val repository = repository(transport)

        runCatching {
            repository.submit(
                ComposeDraft(
                    accountKey = testAccountKey,
                    identityId = "2",
                    emailId = "42",
                    subject = "Hello",
                ),
                hold = null,
            )
        }

        // The re-read answered with the primary alone -- the alias really is
        // gone -- and that is now what the picker offers.
        assertEquals(
            listOf("anna@example.test"),
            repository.identities().first().map { it.email },
        )
    }

    @Test
    fun `a refusal that is not about the From is passed through in the server's own words`() =
        runTest {
            // Only `forbiddenFrom` is worth translating. Rewriting the rest would
            // replace a specific answer with a generic one.
            seedAccounts(single = true)
            seedIdentities(MULTI_ALIAS)

            val failure = runCatching {
                repository(scripted(NO_RECIPIENTS_RESULT))
                    .submit(
                        ComposeDraft(
                            accountKey = testAccountKey,
                            identityId = "1",
                            emailId = "42",
                        ),
                        hold = null,
                    )
            }
                .exceptionOrNull()

            assertEquals(
                "A message must have at least one recipient.",
                assertNotNull(failure).message,
            )
        }

    // -------------------------------------------------- refused attachments

    @Test
    fun `an unresolvable blob is reported as nothing having been saved`() = runTest {
        // The server refuses the *whole* patch -- the attachments and the
        // subject beside them -- and writes nothing. So there is no rollback to
        // own and no partial save to explain; what the user needs is to know
        // that the draft is untouched and which problem to fix. The server's own
        // wording for this is an array index.
        seedAccounts(single = true)

        val draft =
            ComposeDraft(
                accountKey = testAccountKey,
                identityId = "1",
                emailId = "42",
                subject = "With a receipt",
                attachments =
                    listOf(StagedAttachment("receipt.pdf", "application/pdf", 12, blobId = "p-9")),
                savedAttachments = emptyList(),
            )

        val failure = runCatching {
            repository(scripted(INVALID_PROPERTIES_RESULT)).save(draft)
        }
            .exceptionOrNull()

        assertEquals(
            "One of the attached files is no longer on the server, so nothing was saved. " +
                "Remove it and attach it again.",
            assertNotNull(failure).message,
        )
    }

    @Test
    fun `the same refusal on a patch that carries no attachments keeps the server's wording`() =
        runTest {
            // The attachment sentence would be a guess here. `invalidProperties`
            // on a subject-only patch is about something else entirely, and
            // telling somebody to re-attach a file they did not touch is worse
            // than saying nothing.
            seedAccounts(single = true)

            val draft =
                ComposeDraft(
                    accountKey = testAccountKey,
                    identityId = "1",
                    emailId = "42",
                    subject = "Hello",
                )

            val failure = runCatching {
                repository(scripted(INVALID_PROPERTIES_RESULT)).save(draft)
            }
                .exceptionOrNull()

            assertEquals(
                "attachments[0]: blobId could not be resolved.",
                assertNotNull(failure).message,
            )
        }

    // ------------------------------------------------- the submission mode

    @Test
    fun `an account advertising a hold long enough for the window uses the server's`() = runTest {
        seedAccounts(single = false)

        assertEquals(
            SubmissionMode.SERVER_HOLD,
            repository(scripted(session = SUBMISSION_SESSION)).submissionMode(testAccountKey),
        )
    }

    @Test
    fun `an account with delayed send switched off falls back to the local delay`() = runTest {
        // Read per account rather than per server, because that is where RFC
        // 8621 puts it -- and because a login that reaches two mailboxes can
        // genuinely have two answers. The second account here advertises the
        // extension and a ceiling of zero, which is an instance that has turned
        // scheduling off; the undo window still has to work.
        seedAccounts(single = false)

        assertEquals(
            SubmissionMode.LOCAL_DELAY,
            repository(scripted(session = SUBMISSION_SESSION)).submissionMode(secondAccountKey),
        )
    }

    @Test
    fun `an account that does not advertise FUTURERELEASE falls back too`() = runTest {
        // A ceiling with no extension behind it is not a hold this client may
        // use, however large it is. Absence is the signal, as everywhere else.
        seedAccounts(single = true)

        assertEquals(
            SubmissionMode.LOCAL_DELAY,
            repository(scripted(session = NO_EXTENSION_SESSION)).submissionMode(testAccountKey),
        )
    }

    @Test
    fun `an unreachable server is a local delay rather than a failed send`() = runTest {
        seedAccounts(single = true)

        assertEquals(
            SubmissionMode.LOCAL_DELAY,
            repository(RecordingTransport { error("asleep") }).submissionMode(testAccountKey),
        )
    }

    // ------------------------------------------------------------- fixtures

    private val secondAccountKey: String = StoreKey.account(TEST_SERVER, SECOND_ACCOUNT_ID)

    private suspend fun seedAccounts(single: Boolean) {
        val rows = buildList {
            add(
                AccountEntity(
                    uid = testAccountKey,
                    serverId = TEST_SERVER,
                    accountId = TEST_ACCOUNT_ID,
                    name = "someone@example.com",
                )
            )

            if (!single) {
                add(
                    AccountEntity(
                        uid = secondAccountKey,
                        serverId = TEST_SERVER,
                        accountId = SECOND_ACCOUNT_ID,
                        name = "Second",
                    )
                )
            }
        }

        database.accounts().upsert(rows)
    }

    private suspend fun seedIdentities(
        rows: List<Triple<String, String?, String>>,
        accountKey: String = testAccountKey,
    ) {
        database
            .identities()
            .upsert(
                rows.mapIndexed { index, (id, name, email) ->
                    IdentityEntity(
                        uid = StoreKey.objectKey(accountKey, id),
                        accountKey = accountKey,
                        identityId = id,
                        name = name,
                        email = email,
                        sortIndex = index,
                    )
                }
            )
    }

    private fun repository(
        transport: JmapTransport = RecordingTransport.alwaysReturning(TEST_SESSION),
        prefs: AccountPrefsStore = AccountPrefsStore(InMemoryPreferences()),
    ): ComposeRepository {
        val credentials = CredentialStore(InMemoryPreferences(), PlainCipher)

        kotlinx.coroutines.runBlocking {
            credentials.save(
                ServerConnection(
                    address = (ServerAddress.parse(TEST_SERVER) as ParsedAddress.Valid).address,
                    credential = Credential.AppPassword("plmail_" + "a".repeat(64)),
                    username = "someone@example.com",
                )
            )
        }

        val transports =
            object : TransportFactory {
                override fun create(
                    address: ServerAddress,
                    pinned: KeyFingerprint?,
                ): JmapTransport = transport

                override fun createStreaming(
                    address: ServerAddress,
                    pinned: KeyFingerprint?,
                ): StreamingTransport = error("no stream is opened on this path")
            }

        val clients = AccountClients(credentials, transports)

        return ComposeRepository(
            context = ApplicationProvider.getApplicationContext(),
            database = database,
            clients = clients,
            credentials = credentials,
            mail = MailRepository(database),
            accounts = AccountsRepository(database, prefs, clients, credentials),
        )
    }

    /** A transport that serves discovery once and then reads a script, in order. */
    private fun scripted(vararg bodies: String, session: String = TEST_SESSION): JmapTransport {
        var index = 0

        return RecordingTransport { request ->
            val body =
                if (request.url.contains("well-known")) session
                else bodies.getOrNull(index++) ?: error("no scripted response left")

            HttpResponse(
                status = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = body.encodeToByteArray(),
            )
        }
    }

    /** A transport whose `Email/get` answers one draft, written from [email]. */
    private fun draftFrom(name: String, email: String): JmapTransport =
        RecordingTransport { request ->
            val body =
                if (request.url.contains("well-known")) TEST_SESSION
                else
                    """
                    {"methodResponses":[["Email/get",{"accountId":"$TEST_ACCOUNT_ID",
                     "state":"s1","list":[{"id":"42","threadId":"7","subject":"Draft",
                     "from":[{"name":"$name","email":"$email"}],
                     "to":[{"name":null,"email":"someone@example.test"}],
                     "mailboxIds":{"3":true},"keywords":{"${'$'}draft":true},
                     "attachments":[]}],"notFound":[]},"c0"]]}
                    """

            HttpResponse(
                status = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = body.encodeToByteArray(),
            )
        }

    private companion object {
        const val SECOND_ACCOUNT_ID = "2"

        /**
         * `Identity/get` on 8002, account 1, captured 2026-08-06.
         *
         * One synthetic identity for the account address, because the seed has no alias rows — and
         * the address is the display name, which is the seed's own defect and a useful one.
         */
        val SYNTHETIC_8002 = listOf(Triple("1", "E2E Mailbox", "E2E Mailbox"))

        /**
         * The multi-alias case, constructed per the documented contract.
         *
         * One identity per sendable alias, **primary first**, which is the order `Identity/get`
         * publishes and the order the web composer's dropdown shows. Not obtainable from 8002: the
         * seed has no alias rows and alias creation is a server-side write this repo may not make.
         */
        val MULTI_ALIAS =
            listOf(
                Triple("1", "Anna Meyer", "anna@example.test"),
                Triple("2", "Rechnungen", "rechnung@example.test"),
                Triple("3", null, "anna+lists@example.test"),
            )

        val SECOND_ACCOUNT_ALIAS = listOf(Triple("9", "Second Mailbox", "second@e2e.test"))

        /** 8002's own wording, probed on 2026-08-06. */
        val FORBIDDEN_FROM_RESULT =
            """
            {"methodResponses":[["EmailSubmission/set",{"accountId":"$TEST_ACCOUNT_ID",
             "oldState":"s1","newState":"s1","created":{},
             "notCreated":{"s1":{"type":"forbiddenFrom",
              "description":"Identity \"2\" is not a sendable address of this account; use one from Identity/get."}},
             "updated":{},"notUpdated":{},"destroyed":[],"notDestroyed":{}},"c0"]]}
            """

        /** The re-read that follows it, with the alias genuinely gone. */
        val IDENTITY_GET_RESULT =
            """
            {"methodResponses":[["Identity/get",{"accountId":"$TEST_ACCOUNT_ID","state":"0",
             "list":[{"id":"1","name":"Anna Meyer","email":"anna@example.test",
                      "replyTo":null,"bcc":null,"textSignature":"","htmlSignature":"",
                      "mayDelete":false}],"notFound":[]},"c0"]]}
            """

        val NO_RECIPIENTS_RESULT =
            """
            {"methodResponses":[["EmailSubmission/set",{"accountId":"$TEST_ACCOUNT_ID",
             "oldState":"s1","newState":"s1","created":{},
             "notCreated":{"s1":{"type":"noRecipients",
              "description":"A message must have at least one recipient."}},
             "updated":{},"notUpdated":{},"destroyed":[],"notDestroyed":{}},"c0"]]}
            """

        val INVALID_PROPERTIES_RESULT =
            """
            {"methodResponses":[["Email/set",{"accountId":"$TEST_ACCOUNT_ID",
             "oldState":"s1","newState":"s1","created":{},"notCreated":{},"updated":{},
             "notUpdated":{"42":{"type":"invalidProperties",
              "description":"attachments[0]: blobId could not be resolved."}},
             "destroyed":[],"notDestroyed":{}},"c0"]]}
            """

        /** Two accounts with different answers, which is the case `submissionMode` exists for. */
        val SUBMISSION_SESSION =
            """
            {
              "capabilities": {
                "urn:ietf:params:jmap:core": {},
                "urn:ietf:params:jmap:submission": {}
              },
              "accounts": {
                "$TEST_ACCOUNT_ID": {
                  "name": "someone@example.com",
                  "accountCapabilities": {
                    "urn:ietf:params:jmap:submission": {
                      "maxDelayedSend": 2592000,
                      "submissionExtensions": {"FUTURERELEASE": ["HOLDFOR", "HOLDUNTIL"]}
                    }
                  }
                },
                "$SECOND_ACCOUNT_ID": {
                  "name": "Second",
                  "accountCapabilities": {
                    "urn:ietf:params:jmap:submission": {
                      "maxDelayedSend": 0,
                      "submissionExtensions": {"FUTURERELEASE": ["HOLDFOR", "HOLDUNTIL"]}
                    }
                  }
                }
              },
              "username": "someone@example.com",
              "apiUrl": "$TEST_SERVER/jmap/api",
              "downloadUrl": "$TEST_SERVER/jmap/download",
              "uploadUrl": "$TEST_SERVER/jmap/upload"
            }
            """

        val NO_EXTENSION_SESSION =
            """
            {
              "capabilities": {
                "urn:ietf:params:jmap:core": {},
                "urn:ietf:params:jmap:submission": {}
              },
              "accounts": {
                "$TEST_ACCOUNT_ID": {
                  "name": "someone@example.com",
                  "accountCapabilities": {
                    "urn:ietf:params:jmap:submission": {"maxDelayedSend": 2592000}
                  }
                }
              },
              "username": "someone@example.com",
              "apiUrl": "$TEST_SERVER/jmap/api",
              "downloadUrl": "$TEST_SERVER/jmap/download",
              "uploadUrl": "$TEST_SERVER/jmap/upload"
            }
            """
    }
}
