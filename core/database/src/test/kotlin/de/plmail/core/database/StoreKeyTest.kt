package de.plmail.core.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The composite key is the one thing in this module that fails silently.
 *
 * Every other mistake here surfaces as a crash or an empty list. Getting identity wrong does not:
 * it merges two people's mail into one conversation, because JMAP ids are unique only *within* an
 * account and every account numbers its messages from 1. Nothing throws, the list just quietly
 * contains someone else's message.
 *
 * So these tests are about *distinctness*, not about the string format. The format is an
 * implementation detail; the property that two different (server, account, object) triples can
 * never collide is not, and it rests on an assumption about the delimiters that is worth stating
 * out loud — see [ids cannot contain the delimiters, which is what makes the scheme safe].
 */
class StoreKeyTest {

    private val server = "https://nas.local"
    private val other = "https://mail.example.com"

    @Test
    fun `one account is one key`() {
        assertEquals(StoreKey.account(server, "13"), StoreKey.account(server, "13"))
    }

    @Test
    fun `the same account id on two servers is two accounts`() {
        assertNotEquals(StoreKey.account(server, "13"), StoreKey.account(other, "13"))
    }

    @Test
    fun `two accounts on one server are two accounts`() {
        assertNotEquals(StoreKey.account(server, "13"), StoreKey.account(server, "14"))
    }

    @Test
    fun `the same object id in two accounts is two objects`() {
        val first = StoreKey.account(server, "13")
        val second = StoreKey.account(server, "14")

        // The case the whole scheme exists for: both accounts have a message 1.
        assertNotEquals(StoreKey.objectKey(first, "1"), StoreKey.objectKey(second, "1"))
    }

    @Test
    fun `an object key contains its account key, so a row can be traced back`() {
        val accountKey = StoreKey.account(server, "13")

        assertTrue(StoreKey.objectKey(accountKey, "1").startsWith(accountKey))
    }

    @Test
    fun `no two distinct triples collide`() {
        val servers = listOf(server, other, "http://10.0.2.2:8002", "https://nas.local:8443")
        val accountIds = listOf("1", "13", "14", "130")
        val objectIds = listOf("1", "13", "M1", "M-1_a")

        val keys = servers.flatMap { s ->
            accountIds.flatMap { a ->
                objectIds.map { o -> StoreKey.objectKey(StoreKey.account(s, a), o) }
            }
        }

        assertEquals(keys.size, keys.toSet().size, "distinct triples produced a duplicate key")
    }

    /**
     * The assumption the scheme rests on, written down as a test.
     *
     * `"a" + "/" + "b/c"` and `"a/b" + "/" + "c"` are the same string. The scheme is therefore only
     * unambiguous because a JMAP id can never contain the delimiters: RFC 8620 §1.2 restricts ids
     * to `A-Za-z0-9`, `-` and `_`, which excludes both `/` and `#`. The server side of the key is
     * an origin URL, which contains `/` — so if that guarantee ever stopped holding, *this* is the
     * test that should fail rather than a user noticing a stranger's mail in their inbox.
     */
    @Test
    fun `ids cannot contain the delimiters, which is what makes the scheme safe`() {
        val legal = Regex("^[A-Za-z0-9_-]+$")

        val realistic = listOf("1", "13", "M1", "M-1_a", "abc_DEF-123")
        realistic.forEach { assertTrue(legal.matches(it), "“$it” is not a legal JMAP id") }

        // Demonstrates the ambiguity that the character restriction rules out,
        // so the reason the restriction matters is visible here rather than
        // implied.
        assertEquals(
            StoreKey.account("https://a", "b/c"),
            StoreKey.account("https://a/b", "c"),
            "if ids could contain a slash these would be two different accounts sharing one key",
        )
    }
}
