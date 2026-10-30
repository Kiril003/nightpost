package local.phantom.companion.core.solana

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SeekerClaimTest {

    private val owner = SolanaKey.of(ByteArray(32) { 1 })!!
    private val challenge = SiwsChallenge(
        nonce = "abc123def456",
        domain = "phantom-os.dev",
        statement = "Confirm Seeker ownership for Nightpost",
        uri = "https://phantom-os.dev",
        chainId = "mainnet",
    )

    /** Той самий рядок звіряє сервер (test_seeker_claim.py::GOLDEN_FROM_PHONE). */
    private val golden = "phantom-os.dev wants you to sign in with your Solana account:\n" +
        "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi\n" +
        "\n" +
        "Confirm Seeker ownership for Nightpost\n" +
        "\n" +
        "URI: https://phantom-os.dev\n" +
        "Version: 1\n" +
        "Chain ID: mainnet\n" +
        "Nonce: abc123def456\n" +
        "Issued At: 2026-10-02T12:00:00.000Z"

    @Test
    fun builtInWalletSignsExactlyTheSiwsV1Text() {
        assertEquals(golden, Siws.message(challenge, owner, "2026-10-02T12:00:00.000Z"))
    }

    @Test
    fun issuedAtLooksLikeJavascriptToIsoString() {
        assertEquals("2026-10-02T12:00:00.000Z", Siws.now(Instant.parse("2026-10-02T12:00:00Z")))
        assertEquals("2026-10-02T12:00:00.123Z", Siws.now(Instant.parse("2026-10-02T12:00:00.123456Z")))
    }

    @Test
    fun challengeIsTakenFromTheServerAndAForeignNonceIsNot() {
        val c = SeekerClaimClient.parseChallenge(
            """{"nonce":"66fd1a2b0011223344556677aabbccddeeff00112233","domain":"phantom-os.dev","statement":"Confirm Seeker ownership for Nightpost","uri":"https://phantom-os.dev","chain_id":"mainnet","ttl_seconds":600}""",
        )!!
        assertEquals("phantom-os.dev", c.domain)
        assertEquals("mainnet", c.chainId)
        assertNull(SeekerClaimClient.parseChallenge("""{"nonce":"abc\nURI: evil","domain":"phantom-os.dev"}"""))
        assertNull(SeekerClaimClient.parseChallenge("""{"detail":"seeker claim is not configured"}"""))
    }

    @Test
    fun everyServerAnswerBecomesAnHonestResult() {
        assertEquals(
            SeekerClaimResult.Granted("PHTM-AAAAA-BBBBB-CCCCC-D", "2026-11-01", "HAF57K9jMt61dZnQ5pqwNeuBgfguSRNqXqiuNjaF1Vtr"),
            SeekerClaimClient.parseClaim(200, """{"license_key":"PHTM-AAAAA-BBBBB-CCCCC-D","tier":"personal","pro_until":"2026-11-01","mint":"HAF57K9jMt61dZnQ5pqwNeuBgfguSRNqXqiuNjaF1Vtr"}"""),
        )
        assertEquals(SeekerClaimResult.AlreadyUsed(null, otherWallet = true), SeekerClaimClient.parseClaim(409, """{"reason":"sgt_already_used","mint":"m"}"""))
        assertEquals(SeekerClaimResult.AlreadyUsed("2026-11-01", otherWallet = false), SeekerClaimClient.parseClaim(409, """{"reason":"month_used","pro_until":"2026-11-01"}"""))
        assertEquals(SeekerClaimResult.NotSeeker, SeekerClaimClient.parseClaim(404, """{"reason":"not_seeker"}"""))
        assertEquals(SeekerClaimResult.Unavailable, SeekerClaimClient.parseClaim(503, """{"reason":"chain_unreachable"}"""))
        assertEquals(SeekerClaimResult.Unavailable, SeekerClaimClient.parseClaim(502, "<html>bad gateway</html>"))
        assertEquals(SeekerClaimResult.Refused("bad_signature"), SeekerClaimClient.parseClaim(403, """{"detail":"bad_signature"}"""))
        // Сайт без маршруту (старий деплой) — не «ви не Seeker».
        assertEquals(SeekerClaimResult.Refused("Not Found"), SeekerClaimClient.parseClaim(404, """{"detail":"Not Found"}"""))
        assertEquals(SeekerClaimResult.Refused("bad_reply"), SeekerClaimClient.parseClaim(200, """{"tier":"personal"}"""))
    }

    @Test
    fun claimCarriesWalletExactMessageAndBase58Signature() = runTest {
        var seenUrl = ""
        var seenBody = ""
        val client = SeekerClaimClient("https://phantom-os.dev/") { url, body ->
            seenUrl = url
            seenBody = body.orEmpty()
            404 to """{"reason":"not_seeker"}"""
        }
        val sig = ByteArray(64) { it.toByte() }
        assertEquals(SeekerClaimResult.NotSeeker, client.claim(owner, golden, sig))
        assertEquals("https://phantom-os.dev/api/solana/seeker/claim", seenUrl)
        val o = SolanaRpc.JSON.parseToJsonElement(seenBody).jsonObject
        assertEquals(owner.base58, o["wallet"]!!.jsonPrimitive.content)
        assertEquals(golden, o["siws_message"]!!.jsonPrimitive.content)
        assertArrayEquals(sig, Base58.decode(o["siws_signature"]!!.jsonPrimitive.content))
    }

    @Test
    fun noServerOrNoNetworkIsUnavailableNotARefusal() = runTest {
        assertNull(SeekerClaimClient("").challenge())
        val down = SeekerClaimClient("https://x") { _, _ -> throw java.io.IOException("offline") }
        assertNull(down.challenge())
        assertEquals(SeekerClaimResult.Unavailable, down.claim(owner, golden, ByteArray(64)))
    }
}
