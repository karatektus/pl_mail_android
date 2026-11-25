package de.plmail.feature.compose

import de.plmail.core.data.ComposeDraft
import de.plmail.core.data.SendIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How the collapsed header names the sender.
 *
 * The server publishes one identity per name an address sends under: "A" and "Arnaud" both at
 * a@example.org. The address is what tells two aliases apart, and exactly what these two share, so
 * there the name is what the line has to say.
 */
class SummarySenderTest {

    private fun identity(id: String, name: String?, email: String, account: String = "acc") =
        SendIdentity(
            accountKey = account,
            accountName = "Account",
            identityId = id,
            name = name,
            email = email,
        )

    private fun state(chosen: String, vararg identities: SendIdentity) =
        ComposeUiState(
            draft = ComposeDraft(accountKey = "acc", identityId = chosen),
            identities = identities.toList(),
        )

    @Test
    fun `an address of its own is named by its address`() {
        val state =
            state(
                "2",
                identity("1", "Jan", "jan@example.org"),
                identity("2", "Jan", "family@example.org"),
            )

        assertEquals("family@example.org", state.summarySender)
    }

    @Test
    fun `an address with several names is named by the name picked`() {
        val state =
            state(
                "n1-1",
                identity("1", "A", "a@example.org"),
                identity("n1-1", "Arnaud", "a@example.org"),
            )

        assertEquals("Arnaud", state.summarySender)
        assertEquals("A", state(chosen = "1", *state.identities.toTypedArray()).summarySender)
    }

    @Test
    fun `the same address on another account does not count as a second name`() {
        val state =
            state(
                "1",
                identity("1", "A", "a@example.org"),
                identity("9", "B", "a@example.org", account = "other"),
            )

        assertEquals("a@example.org", state.summarySender)
    }

    @Test
    fun `no identity chosen names nobody`() {
        assertNull(state("missing", identity("1", "A", "a@example.org")).summarySender)
    }
}
