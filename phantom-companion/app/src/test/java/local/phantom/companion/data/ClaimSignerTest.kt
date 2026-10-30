package local.phantom.companion.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import local.phantom.companion.core.net.crypto.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Рішення №6.4: заяви сцени підписує окремий ключ заяв, не ключ месенджера. */
class ClaimSignerTest {

    private val b64 = java.util.Base64.getEncoder().withoutPadding()
    private val messenger = Ed25519.generateKeypair()
    private val claimsPair = Ed25519.generateKeypair()
    private val messengerPub = b64.encodeToString(messenger.publicKey)
    private val claimsPub = b64.encodeToString(claimsPair.publicKey)
    private val signer = ClaimSigner(key = { ClaimKey(claimsPub, claimsPair.privateKey) }, nowMs = { 42L })

    private val claims = listOf(
        DeviceClaim.VenueRate("0123456789abcdef", 3),
        DeviceClaim.CheckIn("0f0f0f0f-1111-4222-8333-444444444444", "cafe", "881e805011fffff", 7L),
        DeviceClaim.Settled("devnet", "5".repeat(88), "1".repeat(32), "0f0f0f0f-1111-4222-8333-444444444444"),
        DeviceClaim.Me,
    )

    @Test
    fun `кожну заяву підписує ключ заяв, і він не ключ месенджера`() {
        assertNotEquals(messengerPub, claimsPub)
        for (claim in claims) {
            val proof = signer.prove(claim) ?: error("підпису немає: $claim")
            assertEquals(claimsPub, proof.pubKey)
            assertNotEquals("заяву $claim підписав ключ месенджера", messengerPub, proof.pubKey)
            val sentence = claim.sentence(proof.pubKey, proof.nonce, proof.issuedMs).toByteArray(Charsets.UTF_8)
            val sig = java.util.Base64.getDecoder().decode(proof.signature)
            assertTrue(Ed25519.verify(claimsPair.publicKey, sentence, sig))
            assertFalse(Ed25519.verify(messenger.publicKey, sentence, sig))
            assertTrue(Regex("[0-9a-f]{32}").matches(proof.nonce))
            assertEquals(42L, proof.issuedMs)
        }
    }

    @Test
    fun `на сервер їде ключ заяв, і гаманець підписує речення з ним`() = runTest {
        var body: String? = null
        val client = PointsClient(
            baseUrl = "https://example.test",
            signer = VenueClient.deviceSigner(signer::prove),
            http = { _, _, json -> body = json; 200 to """{"awarded":[]}""" },
        )
        var walletSaw: String? = null
        client.settled("devnet", "5".repeat(88), "1".repeat(32), "0f0f0f0f-1111-4222-8333-444444444444") {
            walletSaw = it.toString(Charsets.UTF_8)
            ByteArray(64)
        }
        val sent = Json.parseToJsonElement(body ?: error("запиту не було")).jsonObject
        assertEquals(claimsPub, sent["pub_key"]!!.jsonPrimitive.content)
        assertTrue(walletSaw!!.contains("|$claimsPub|"))
        assertFalse(walletSaw!!.contains(messengerPub))
        assertEquals(42L, sent["issued_ms"]!!.jsonPrimitive.long)
    }

    @Test
    fun `без ключа заяв підпису немає — і запит не йде`() = runTest {
        var called = false
        val client = VenueClient(
            baseUrl = "https://example.test",
            signer = VenueClient.deviceSigner(ClaimSigner(key = { null })::prove),
            http = { _, _, _ -> called = true; null },
        )
        assertEquals(VenueClient.Answer.NoKey, client.rate("0123456789abcdef", 4))
        assertFalse(called)
    }
}
