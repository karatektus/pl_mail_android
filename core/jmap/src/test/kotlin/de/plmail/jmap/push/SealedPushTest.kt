package de.plmail.jmap.push

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Opening a push the server sealed to this device.
 *
 * The first test is the one that matters and the reason the rest can be short: its fixture was
 * produced by the **server's own code** — `FcmPayloadCipher` on a plMail test instance, sealing a
 * `CalendarAlert` to the key pair written out below. If this side derives a key any differently
 * from that side, by one byte of `info` or one byte of padding, it does not open. A test that
 * sealed and opened with this module's own code would pass on any pair of functions that were each
 * other's inverse, including a pair that agrees with nobody else.
 *
 * The cost of that is a fixture that cannot be regenerated here. It is regenerated on the server,
 * by sealing to a fresh key pair and pasting all four strings; none of them is a secret, since the
 * private key below has never belonged to a device.
 */
class SealedPushTest {

    @Test
    fun `a payload sealed by the server opens with the device's key`() {
        val opened = SealedPush.open(SEALED, keys())

        assertEquals(
            """{"@type":"CalendarAlert","title":"Dentist — Praxis Dr. Ilg","body":"in 15 minutes",""" +
                """"url":"/calendar/day/2026-10-16","tag":"412/display-15m/2026-10-16T09:00:00Z"}""",
            opened.decodeToString(),
        )
    }

    /**
     * RFC 8291 Appendix A, the vector every implementation checks against — here so that agreeing
     * with plMail's server is also agreeing with the standard, rather than with one library.
     */
    @Test
    fun `the vector from RFC 8291 opens`() {
        val keys =
            PushKeys(
                privateScalar = decode("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"),
                publicPoint =
                    decode(
                        "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcx" +
                            "aOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
                    ),
                auth = decode("BTBZMqHH6r4Tts7J_aSIgg"),
            )

        val opened =
            SealedPush.open(
                "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml" +
                    "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT" +
                    "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
                keys,
            )

        assertEquals("When I grow up, I want to be a watermelon", opened.decodeToString())
    }

    /** Somebody else's key is the same failure as a tampered body: the tag does not verify. */
    @Test
    fun `a payload does not open with another device's key`() {
        assertFailsWith<SealedPushException> { SealedPush.open(SEALED, PushKeys.generate()) }
    }

    @Test
    fun `a changed byte anywhere in the record is refused`() {
        val body = Base64.getUrlDecoder().decode(SEALED)

        body[body.size - 20] = (body[body.size - 20].toInt() xor 0x01).toByte()

        assertFailsWith<SealedPushException> { SealedPush.open(body, keys()) }
    }

    /**
     * What arrives on a device's token is whatever was sent to it. None of these may throw anything
     * but the one exception the caller handles.
     */
    @Test
    fun `garbage is refused as a sealed push that will not open, not as a crash`() {
        for (garbage in listOf("", "not base64url!!", "AAAA", SEALED.take(40))) {
            assertFailsWith<SealedPushException>(garbage) { SealedPush.open(garbage, keys()) }
        }
    }

    /**
     * The encodings the server is sent: an uncompressed point and sixteen bytes, base64url with no
     * padding — and always those sizes. A coordinate with a leading zero, or one with its top bit
     * set, comes out of `BigInteger` a byte short or a byte long, about once in a few hundred keys.
     */
    @Test
    fun `generated keys are always the sizes the server expects`() {
        repeat(400) {
            val keys = PushKeys.generate()

            assertEquals(65, Base64.getUrlDecoder().decode(keys.p256dh).size)
            assertEquals(16, Base64.getUrlDecoder().decode(keys.authSecret).size)
            assertEquals(32, keys.privateScalar.size)
            assertTrue('=' !in keys.p256dh && '=' !in keys.authSecret)
        }
    }

    @Test
    fun `stored keys read back as the same keys`() {
        val keys = PushKeys.generate()
        val restored = PushKeys(keys.privateScalar, keys.publicPoint, keys.auth)

        assertContentEquals(keys.publicPoint, restored.publicPoint)
        assertEquals(keys.p256dh, restored.p256dh)
    }

    @Test
    fun `the keys never print themselves`() {
        assertEquals("PushKeys(…)", PushKeys.generate().toString())
    }

    private fun keys() =
        PushKeys(
            privateScalar = decode("e21UbNQzEkZmbLtKTbjwzHK01YqM6pL7OUDT3S9F60s"),
            publicPoint =
                decode(
                    "BOZk5elS66ra3hjK92gpnB0vcTNToEuIIgAM9e9rT_h_" +
                        "anKqp-LhZdAgsxjQClEeaTQt4L3t9aOY_oI5tUDxXTw"
                ),
            auth = decode("x8jbNfS-1Do_nOkA_REtuA"),
        )

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private companion object {
        /** Sealed on a plMail test server by `FcmPayloadCipher`, to the keys in [keys]. */
        const val SEALED =
            "3Qpo0-DTg7E45N3q6-b21wAAEABBBO6ba-saNRgHR1etwr9md7JQHSVatmWGEvvbyOb0IegRCxRXwoO9leUT7oG8" +
                "Ut24O9FUbED5v_phdpuHCo_qblwZWxkkvCCPfwuMP0RCHtGe67diPq6Cm6ReK5UWIk5WrP1VoSA2HY-_CZjn" +
                "QBgrc05ORxPRwreObo5yBFwlo8LVFm_vEleKOW_DozYxzwGnC_aY5nKwCJGS1sS-WwVmRh0p0xck6fHIggrc" +
                "8BqsKIU8xIVn_tFJaFFvVEYav-oJpp12WRSvA3DdjI0CKlCNykwFHz_NR2dWlXGfhUNrrVcaftIy-LTIoWLS" +
                "PkP_o0HIViKSG1Y"
    }
}
