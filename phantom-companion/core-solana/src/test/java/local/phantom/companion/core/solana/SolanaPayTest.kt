package local.phantom.companion.core.solana

import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaPayTest {

    private val merchant = SolanaKey.of("BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH")
    private val sig = "4WVSP661Y7sGVtg64ScknvfBFVX5rV2UqBaFRWmFwLxZJCGzdtEAnC1psHCiTYnxZgEg1KWMTDgP5o7YvJL6mZHT"

    @Test
    fun transferRequestUrlFollowsSolanaPaySpec() {
        val ref = SolanaKey.of(ByteArray(32) { 7 })!!
        val r = TransferRequest(merchant, Coin.USDC, Cluster.MAINNET, 9_000_000, ref, "PHANTOM", "Ліцензія")
        val url = r.url()
        assertEquals(
            "solana:BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH?amount=9&spl-token=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" +
                "&reference=US517G5965aydkZ46HS38QLi7UQiSojurfbQfKCELFx&label=PHANTOM&message=%D0%9B%D1%96%D1%86%D0%B5%D0%BD%D0%B7%D1%96%D1%8F",
            url,
        )
        assertEquals(r, TransferRequest.parse(url, Cluster.MAINNET))
        assertNull(TransferRequest.parse("https://x", Cluster.MAINNET))
    }

    @Test
    fun claimProofIsAnEd25519SignatureByTheReferenceKey() {
        val ref = PurchaseReference.fresh()
        val proof = Base58.decode(ref.prove(sig))!!
        val msg = PurchaseReference.claimMessage(sig)
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(ref.key.bytes, 0))
            update(msg, 0, msg.size)
        }
        assertTrue(verifier.verifySignature(proof))
        val restored = PurchaseReference.restore(ref.seedBase58)!!
        assertEquals(ref.key, restored.key)
    }

    @Test
    fun offerParsingDropsCoinsTheClusterDoesNotHave() {
        val o = LicenceShop.parseOffer("""{"cluster":"devnet","recipient":"${merchant.base58}","prices":{"usdc":"9","skr":"100"},"tier":"personal"}""")
        assertNotNull(o)
        assertEquals(mapOf(Coin.USDC to 9_000_000L), o!!.prices)
        assertNull(LicenceShop.parseOffer("""{"cluster":"devnet","recipient":"${merchant.base58}","prices":{}}"""))
        assertNull(LicenceShop.parseOffer("not json"))
    }

    @Test
    fun eurcIsReadFromItsMintAndFromAnOffer() {
        val ref = SolanaKey.of(ByteArray(32) { 7 })!!
        for (cluster in Cluster.entries) {
            val r = TransferRequest(merchant, Coin.EURC, cluster, 9_000_000, ref, "PHANTOM", "")
            assertTrue(r.url().contains("spl-token=HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr"))
            assertEquals(r, TransferRequest.parse(r.url(), cluster))
        }
        val o = LicenceShop.parseOffer("""{"cluster":"devnet","recipient":"${merchant.base58}","prices":{"usdc":"9","eurc":"8"}}""")
        assertEquals(mapOf(Coin.USDC to 9_000_000L, Coin.EURC to 8_000_000L), o!!.prices)
    }

    @Test
    fun claimMapsServerAnswersToHonestStates() = runTest {
        val ref = PurchaseReference.fresh()
        var sent = ""
        val shop = LicenceShop("https://lic") { url, body ->
            sent = body.orEmpty()
            assertEquals("https://lic/api/solana/claim", url)
            200 to """{"license_key":"PHTM-AAAA-BBBB-CCCC-DDDD"}"""
        }
        assertEquals(ClaimResult.Key("PHTM-AAAA-BBBB-CCCC-DDDD"), shop.claim(ref, sig))
        assertTrue(ref.key.base58 in sent && sig in sent && "proof" in sent)

        assertEquals(ClaimResult.NotYet, LicenceShop("x") { _, _ -> 202 to "{}" }.claim(ref, sig))
        assertEquals(ClaimResult.NotYet, LicenceShop("x") { _, _ -> throw java.io.IOException() }.claim(ref, sig))
        assertEquals(ClaimResult.Refused("underpaid"), LicenceShop("x") { _, _ -> 402 to """{"detail":"underpaid"}""" }.claim(ref, sig))
    }
}
