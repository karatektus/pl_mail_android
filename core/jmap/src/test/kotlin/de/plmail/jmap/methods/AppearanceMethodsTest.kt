package de.plmail.jmap.methods

import de.plmail.jmap.Fixture
import de.plmail.jmap.protocol.JmapError
import de.plmail.jmap.protocol.MethodHandle
import de.plmail.jmap.protocol.MethodResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `Appearance/get` and `Appearance/set`, round trip.
 *
 * Three behaviours are pinned here because getting any of them wrong looks like success. A patch
 * carrying a property nobody touched flattens whatever the web set. A clamp taken from the request
 * rather than the response leaves the app showing a number the server did not store. And a stale
 * `ifInState` fails the whole call rather than one entry, so a caller waiting for a `notUpdated`
 * map never sees one.
 */
class AppearanceMethodsTest {

    private fun results(fixture: String) =
        MethodResults.decode(Fixture.read(fixture).encodeToByteArray(), status = 200)

    @Test
    fun `reads the singleton without an accountId, knobs included`() {
        val arguments = AppearanceGet().arguments()

        // Appearance is per user. An accountId is refused with
        // `invalidArguments` rather than ignored — verified on the wire — so
        // sending one out of habit fails every read.
        assertEquals(setOf("ids"), arguments.keys)

        val handle = MethodHandle(AppearanceGet(), "a0")
        val appearance = assertNotNull(results("appearance-get.json").result(handle).appearance)

        assertEquals("singleton", appearance.id)
        assertEquals("nord", appearance.theme)
        assertEquals("boxed", appearance.layout)
        assertEquals("comfortable", appearance.density)
        assertEquals(0.7f, appearance.paneAlpha)

        // Carried, never rendered. Compose has no backdrop blur, and a value
        // this client dropped would be a value it could silently write away.
        // 24 is the preset the boxed layout seeded when it was chosen.
        assertEquals(24f, appearance.paneBlur)
    }

    @Test
    fun `set writes only the properties the patch names`() {
        val update =
            AppearanceSet(AppearancePatch.build { density("compact") })
                .arguments()["update"]
                ?.jsonObject

        val fields = assertNotNull(update?.get("singleton")?.jsonObject)

        // The whole mechanism by which `paper`, a background image and an ink
        // override survive a phone touching the settings at all: a client that
        // sent a resolved object back would flatten every one of them.
        assertEquals(setOf("density"), fields.keys)
        assertEquals("compact", fields["density"]?.jsonPrimitive?.content)
        assertTrue("ifInState" !in AppearanceSet(AppearancePatch.build {}).arguments().keys)
    }

    @Test
    fun `a clamp comes back in updated and is what gets applied`() {
        val handle = MethodHandle(AppearanceSet(AppearancePatch.build {}), "a0")
        val result = results("appearance-set-clamped.json").result(handle)

        // Sent: layout boxed, density compact, paneAlpha 1.4. Reported back:
        // paneAlpha 1. The other two were taken as asked and therefore say
        // nothing — RFC 8620 §5.3, where `updated` is what the server changed
        // *beyond* the request.
        assertEquals(setOf("paneAlpha"), result.reported.keys)

        val applied =
            Appearance(theme = "nord", layout = "boxed", density = "compact", paneAlpha = 1.4f)
                .with(result.reported)

        // The number the server stored, not the one the slider sent. A client
        // keeping its own would draw 140% for a value held at 100.
        assertEquals(1f, applied.paneAlpha)
        assertEquals("nord", applied.theme, "a property nobody reported keeps what was sent")
    }

    @Test
    fun `a stale ifInState fails the call rather than one entry`() {
        val handle = MethodHandle(AppearanceSet(AppearancePatch.build {}), "a0")

        // Request-level, so there is no `notUpdated` map to inspect and no
        // partial success to reconcile: the caller re-reads and re-applies.
        val failure =
            assertFailsWith<JmapError.MethodFailed> {
                results("appearance-set-state-mismatch.json").result(handle)
            }

        assertEquals("stateMismatch", failure.type)
    }
}
