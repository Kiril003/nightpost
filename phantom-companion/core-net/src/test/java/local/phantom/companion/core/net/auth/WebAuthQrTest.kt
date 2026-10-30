package local.phantom.companion.core.net.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.b64ToBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAuthQrTest {

    @Test
    fun `parse accepts valid NP-AUTH-v1 payload before expiry`() {
        val raw = "NP-AUTH-v1|chal_9f8e7d|nonce_1a2b3c|1800000000"
        val result = WebAuthQr.parse(raw, nowMs = 1_799_999_999_000L)

        assertTrue(result is WebAuthQr.ParseResult.Valid)
        val challenge = (result as WebAuthQr.ParseResult.Valid).challenge
        assertEquals("chal_9f8e7d", challenge.challengeId)
        assertEquals("nonce_1a2b3c", challenge.nonce)
        assertEquals(1_800_000_000L, challenge.expiresAtSeconds)
    }

    @Test
    fun `parse marks payload expired at or after expires_at_unix_s`() {
        val raw = "NP-AUTH-v1|chal_9f8e7d|nonce_1a2b3c|1800000000"

        val atBoundary = WebAuthQr.parse(raw, nowMs = 1_800_000_000_000L)
        assertTrue(atBoundary is WebAuthQr.ParseResult.Expired)

        val afterExpiry = WebAuthQr.parse(raw, nowMs = 1_800_000_060_000L)
        assertTrue(afterExpiry is WebAuthQr.ParseResult.Expired)
        assertEquals("chal_9f8e7d", (afterExpiry as WebAuthQr.ParseResult.Expired).challenge.challengeId)
    }

    @Test
    fun `parse rejects wrong prefix and malformed payloads`() {
        val nowMs = 1_700_000_000_000L
        val invalidInputs = listOf(
            null,
            "",
            "   ",
            "PH2:abc:def",
            "NP-AUTH-v2|chal|nonce|1900000000",
            "np-auth-v1|chal|nonce|1900000000",
            "NP-AUTH-v1|chal|nonce",
            "NP-AUTH-v1|chal|nonce|1900000000|extra",
            "NP-AUTH-v1||nonce|1900000000",
            "NP-AUTH-v1|chal||1900000000",
            "NP-AUTH-v1|chal id|nonce|1900000000",
            "NP-AUTH-v1|chal|non ce|1900000000",
            "NP-AUTH-v1|chal|nonce|not_a_number",
            "NP-AUTH-v1|chal|nonce|0",
            "NP-AUTH-v1|chal|nonce|-100",
        )

        for (input in invalidInputs) {
            assertEquals(
                "Expected Invalid for input: $input",
                WebAuthQr.ParseResult.Invalid,
                WebAuthQr.parse(input, nowMs),
            )
        }
    }

    @Test
    fun `messageBytes matches exact protocol format`() {
        val pubKeyB64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val bytes = WebAuthQr.messageBytes(
            challengeId = "ch_42",
            nonce = "nc_99",
            pubKeyB64 = pubKeyB64,
            issuedMs = 1_712_345_678_901L,
        )
        val expected = "NP-AUTH-v1|ch_42|nc_99|AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=|1712345678901"
            .toByteArray(Charsets.UTF_8)

        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `signApproval signs exact messageBytes and verifies with Ed25519 public key`() {
        val keypair = Ed25519.generateKeypair()
        val challenge = WebAuthQr.Challenge(
            challengeId = "chal_abc",
            nonce = "nonce_def",
            expiresAtSeconds = 1_900_000_000L,
        )
        val issuedMs = 1_750_000_000_123L

        val approval = WebAuthQr.signApproval(
            challenge = challenge,
            privateKey = keypair.privateKey,
            issuedMs = issuedMs,
        )

        assertTrue(WebAuthQr.SignedApproval::class.java.declaredFields.none { it.name == "displayName" })

        val decodedPub = b64ToBytes(approval.pubKeyB64)
        val decodedSig = b64ToBytes(approval.signatureB64)
        assertEquals(Ed25519.PUBLIC_KEY_SIZE, decodedPub.size)
        assertEquals(Ed25519.SIGNATURE_SIZE, decodedSig.size)
        assertArrayEquals(keypair.publicKey, decodedPub)

        val expectedMsg = "NP-AUTH-v1|chal_abc|nonce_def|${approval.pubKeyB64}|$issuedMs"
            .toByteArray(Charsets.UTF_8)
        assertTrue(Ed25519.verify(keypair.publicKey, expectedMsg, decodedSig))
        assertTrue(WebAuthQr.verifyApproval(approval))
        assertFalse(WebAuthQr.verifyApproval(approval.copy(nonce = "tampered")))

        val bodyJson = Json.parseToJsonElement(WebAuthQr.approveRequestJson(approval)).jsonObject
        assertEquals(
            setOf("challenge_id", "pub_key", "nonce", "issued_ms", "signature"),
            bodyJson.keys,
        )
        assertEquals("chal_abc", bodyJson["challenge_id"]?.jsonPrimitive?.content)
        assertEquals(approval.pubKeyB64, bodyJson["pub_key"]?.jsonPrimitive?.content)
        assertEquals("nonce_def", bodyJson["nonce"]?.jsonPrimitive?.content)
        assertEquals(issuedMs, bodyJson["issued_ms"]?.jsonPrimitive?.long)
        assertEquals(approval.signatureB64, bodyJson["signature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parsePeekResponse reads purpose`() {
        val erase = WebAuthQr.parsePeekResponse("""{"browser":"Firefox on Linux","purpose":"erase"}""")
        assertEquals("erase", erase?.purpose)
        val without = WebAuthQr.parsePeekResponse("""{"browser":"Firefox on Linux"}""")
        assertEquals("", without?.purpose)
        val nullPurpose = WebAuthQr.parsePeekResponse("""{"browser":"Firefox on Linux","purpose":null}""")
        assertEquals("", nullPurpose?.purpose)
        assertTrue(WebAuthQr.Purpose.known(WebAuthQr.Purpose.SIGN_IN))
        assertTrue(WebAuthQr.Purpose.known(WebAuthQr.Purpose.ERASE))
        assertTrue(WebAuthQr.Purpose.known("link"))
        assertFalse(WebAuthQr.Purpose.known("transfer"))
    }

    @Test
    fun `peekRequestJson and parsePeekResponse handle present and null country`() {
        val challenge = WebAuthQr.Challenge("c1", "n1", 1_900_000_000L)
        val req = Json.parseToJsonElement(WebAuthQr.peekRequestJson(challenge)).jsonObject
        assertEquals("c1", req["challenge_id"]?.jsonPrimitive?.content)
        assertEquals("n1", req["nonce"]?.jsonPrimitive?.content)

        val withCountry = WebAuthQr.parsePeekResponse(
            """{"browser":"Firefox 128","country":"UA","created_at":"2026-04-17T12:00:00Z"}""",
        )
        assertNotNull(withCountry)
        assertEquals("Firefox 128", withCountry?.browser)
        assertEquals("UA", withCountry?.country)
        assertEquals("2026-04-17T12:00:00Z", withCountry?.createdAt)

        val withoutCountry = WebAuthQr.parsePeekResponse(
            """{"browser":"Safari","country":null,"created_at":"2026-04-17T12:05:00Z"}""",
        )
        assertNotNull(withoutCountry)
        assertEquals("Safari", withoutCountry?.browser)
        assertNull(withoutCountry?.country)
        assertEquals("2026-04-17T12:05:00Z", withoutCountry?.createdAt)
    }

    @Test
    fun `parsePeekResponse surfaces a different network`() {
        val other = WebAuthQr.parsePeekResponse("""{"browser":"Firefox on Linux","country":null,"created_at":1,"ip_mismatch":true}""")
        assertEquals(true, other?.ipMismatch)
        assertEquals(false, other?.sameIp)
        val same = WebAuthQr.parsePeekResponse("""{"browser":"Firefox on Linux","ip_mismatch":false}""")
        assertEquals(false, same?.ipMismatch)
        assertEquals(true, same?.sameIp)
        val explicitSameIpFalse = WebAuthQr.parsePeekResponse("""{"browser":"Firefox on Linux","same_ip":false}""")
        assertEquals(true, explicitSameIpFalse?.ipMismatch)
        assertEquals(false, explicitSameIpFalse?.sameIp)
        val explicitSameIpTrue = WebAuthQr.parsePeekResponse("""{"browser":"Firefox on Linux","same_ip":true}""")
        assertEquals(false, explicitSameIpTrue?.ipMismatch)
        assertEquals(true, explicitSameIpTrue?.sameIp)
    }
}
