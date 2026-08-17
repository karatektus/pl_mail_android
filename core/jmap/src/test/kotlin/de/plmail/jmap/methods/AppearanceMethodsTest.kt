package de.plmail.jmap.methods

import de.plmail.jmap.Fixture
import de.plmail.jmap.protocol.JmapError
import de.plmail.jmap.protocol.MethodHandle
import de.plmail.jmap.protocol.MethodResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
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
 *
 * The v0.0.36 properties are exercised against **constructed** bodies rather than a captured
 * fixture, and are labelled as such at the point of use. The captured files predate the release and
 * the README beside them says to re-capture rather than edit — which this repo cannot do for a
 * server change it did not make — so the same route the multi-alias case took is taken here: build
 * the body to the contract in `AppearanceMapper::toJmap`, and leave the real captures alone as the
 * proof that an older server still decodes.
 */
class AppearanceMethodsTest {

    private fun results(fixture: String) =
        MethodResults.decode(Fixture.read(fixture).encodeToByteArray(), status = 200)

    private fun constructed(body: String) =
        MethodResults.decode(body.encodeToByteArray(), status = 200)

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
    fun `the v0_0_36 properties decode, and a null density is the server's answer`() {
        val handle = MethodHandle(AppearanceGet(), "a0")
        val appearance = assertNotNull(constructed(GET_V36).result(handle).appearance)

        assertEquals(false, appearance.accountCorner)
        assertEquals(true, appearance.listAvatars)
        assertEquals(2, appearance.previewLines)
        assertEquals("strong", appearance.unreadEmphasis)
        assertEquals("serif", appearance.fontFamily)
        assertEquals(1.125f, appearance.fontScale)

        // The list is overridden to compact; the other two surfaces say null,
        // which on a `get` is the server stating that they follow the global
        // density rather than the client failing to read them.
        assertEquals("compact", appearance.listDensity)
        assertNull(appearance.sidebarDensity)
        assertNull(appearance.readingDensity)
    }

    @Test
    fun `an older server's answer leaves the new properties absent rather than defaulted`() {
        // The captured fixture predates v0.0.35. Every new property has to come
        // back null, because null is what the repository reads as "this server
        // said nothing" -- a `false` defaulted in here would look exactly like a
        // server that had switched the account corner off, and the phone would
        // draw it that way for a setting the server does not have.
        val handle = MethodHandle(AppearanceGet(), "a0")
        val appearance = assertNotNull(results("appearance-get.json").result(handle).appearance)

        assertNull(appearance.accountCorner)
        assertNull(appearance.previewLines)
        assertNull(appearance.unreadEmphasis)
        assertNull(appearance.fontScale)
    }

    @Test
    fun `the switches go out as real booleans, not as the web pane's strings`() {
        val fields =
            assertNotNull(
                AppearanceSet(
                        AppearancePatch.build {
                            accountCorner(false)
                            listAvatars(true)
                            previewLines(2)
                            fontScale(1.125f)
                        }
                    )
                    .arguments()["update"]
                    ?.jsonObject
                    ?.get("singleton")
                    ?.jsonObject
            )

        // `requireBool` refuses "1", "0" and "true" -- the spelling the server's
        // own settings form posts, because a checkbox is a string in a DOM node.
        // And the patch is validated whole, so one stringly-typed switch refuses
        // the font scale sitting beside it as well.
        assertEquals(false, fields["accountCorner"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, fields["listAvatars"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(fields["accountCorner"]?.jsonPrimitive?.isString == false)

        // `requireInt` refuses 2.0 as surely as "2", so the preview count must
        // not travel through a Float on its way here.
        assertEquals("2", fields["previewLines"]?.jsonPrimitive?.content)
        assertEquals(1.125f, fields["fontScale"]?.jsonPrimitive?.content?.toFloat())
    }

    @Test
    fun `a per-surface density can be set, cleared, or left alone, and those are three things`() {
        val set = AppearancePatch.build { listDensity("compact") }
        val cleared = AppearancePatch.build { listDensity(null) }
        val untouched = AppearancePatch.build { theme("nord") }

        fun fields(patch: AppearancePatch) = patch.toJson()

        assertEquals("compact", fields(set)["listDensity"]?.jsonPrimitive?.content)

        // Present and null, which is what "follow the overall density" is. An
        // absent key would mean "leave the override alone", so a client that
        // expressed the clear by omission would offer a control that wrote
        // nothing and left the surface compact for ever. The server reads these
        // three with `array_key_exists` rather than `isset` for the same reason.
        assertTrue("listDensity" in cleared.properties)
        assertEquals(JsonNull, fields(cleared)["listDensity"])

        assertTrue("listDensity" !in untouched.properties)
    }

    @Test
    fun `a cleared density comes back in updated and is believed`() {
        // The reconciliation the `?:` in `with()` would break. Picking a global
        // density can drop a surface override server-side, and that arrives as
        // an explicit null in `updated` -- a client folding it into "unreported"
        // would keep the override in its own copy and re-send it on the next
        // write, which is the surface silently refusing to be cleared.
        val handle = MethodHandle(AppearanceSet(AppearancePatch.build {}), "a0")
        val result = constructed(SET_CLEARED_DENSITY).result(handle)

        val applied =
            Appearance(density = "cosy", listDensity = "compact", readingDensity = "comfortable")
                .with(result.reported)

        assertNull(applied.listDensity)
        assertEquals(
            "comfortable",
            applied.readingDensity,
            "a surface nobody reported is untouched",
        )
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

    private companion object {
        /**
         * A v0.0.36 singleton, **constructed** to `AppearanceMapper::toJmap` rather than captured.
         *
         * Trimmed to the nine new properties plus enough of the old ones to be recognisable. The
         * shape that matters is the last three: the server sends all three keys always, and null in
         * them is its answer rather than an omission.
         */
        const val GET_V36 =
            """
            {"methodResponses":[["Appearance/get",{"state":"v36","list":[{
              "id":"singleton","theme":"nord","layout":"boxed","density":"cosy",
              "accountCorner":false,"listAvatars":true,"previewLines":2,
              "unreadEmphasis":"strong","fontFamily":"serif","fontScale":1.125,
              "sidebarDensity":null,"listDensity":"compact","readingDensity":null
            }],"notFound":[]},"a0"]]}
            """

        /** Constructed: the server reporting that it dropped a per-surface override. */
        const val SET_CLEARED_DENSITY =
            """
            {"methodResponses":[["Appearance/set",{"oldState":"v36","newState":"v37",
             "updated":{"singleton":{"listDensity":null}},"notUpdated":{}},"a0"]]}
            """
    }
}
