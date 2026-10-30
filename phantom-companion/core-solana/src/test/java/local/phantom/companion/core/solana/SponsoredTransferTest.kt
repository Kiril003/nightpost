package local.phantom.companion.core.solana

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Спонсорований переказ: байти — звірені з solders 0.29 (Rust solana-sdk), а не з нашим кодом. */
class SponsoredTransferTest {

    private val sponsor = SolanaKey.of(ByteArray(32) { ((it + 201) % 256).toByte() })!!
    private val user = Ed25519Keypair(ByteArray(32) { 0x21 })
    private val to = SolanaKey.of(ByteArray(32) { (it + 101).toByte() })!!
    private val blockhash = ByteArray(32) { 0xAB.toByte() }

    private fun sponsoredMessage() =
        Transfers.build(user.publicKey, to, Coin.USDC, Cluster.DEVNET, 2_500_000, blockhash, memo = "PHANTOM", feePayer = sponsor)

    @Test
    fun partiallySignedTransactionLeavesTheSponsorSlotEmptyAndMatchesSolders() {
        assertEquals(vector("SPONSORED_USER"), user.publicKey.base58)
        val tx = SolanaTx.partiallySigned(sponsoredMessage(), listOf(user))
        assertEquals(vector("SPONSORED"), tx.hex())
        assertEquals(2, tx[0].toInt())
        assertArrayEquals(ByteArray(64), tx.copyOfRange(1, 65))
    }

    @Test
    fun sponsorPaysTheRecipientAccountRentNotTheSender() {
        val p = SolanaTx.parse(sponsoredMessage())!!
        assertEquals(sponsor, p.keys[0])
        assertEquals(user.publicKey, p.keys[1])
        val ata = p.instructions.first { it.first == Programs.ASSOCIATED_TOKEN }
        assertEquals(sponsor, ata.second[0])
        val transfer = p.instructions.first { it.first == Programs.TOKEN }
        assertEquals(user.publicKey, transfer.second[3])
        assertFalse(sponsor in transfer.second)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aMessageWithNoSignerOfOursIsNotSigned() {
        SolanaTx.partiallySigned(sponsoredMessage(), listOf(Ed25519Keypair(ByteArray(32) { 5 })))
    }

    @Test(expected = IllegalArgumentException::class)
    fun durableNonceIsNeverSponsored() {
        Transfers.build(user.publicKey, to, Coin.USDC, Cluster.DEVNET, 1, blockhash, nonce = to, feePayer = sponsor)
    }

    // ── вибір шляху ────────────────────────────────────────────────────────

    private val info = SponsorInfo(Cluster.DEVNET, sponsor)

    @Test
    fun stablesFromTheBuiltInWalletGoThroughTheSponsor() {
        assertEquals(FeeRoute.Route.Sponsored(sponsor), FeeRoute.choose(info, WalletKind.BUILT_IN, Cluster.DEVNET, Coin.USDC))
        assertEquals(FeeRoute.Route.Sponsored(sponsor), FeeRoute.choose(info, WalletKind.BUILT_IN, Cluster.DEVNET, Coin.EURC))
    }

    @Test
    fun withoutSponsorOrOnAnotherClusterOrForSolOrExternalWalletThePersonPays() {
        assertEquals(FeeRoute.Route.Self, FeeRoute.choose(null, WalletKind.BUILT_IN, Cluster.DEVNET, Coin.USDC))
        assertEquals(FeeRoute.Route.Self, FeeRoute.choose(info, WalletKind.BUILT_IN, Cluster.MAINNET, Coin.USDC))
        assertEquals(FeeRoute.Route.Self, FeeRoute.choose(info, WalletKind.BUILT_IN, Cluster.DEVNET, Coin.SOL))
        assertEquals(FeeRoute.Route.Self, FeeRoute.choose(info, WalletKind.EXTERNAL, Cluster.DEVNET, Coin.USDC))
    }

    @Test
    fun solIsHiddenOnlyWhenSponsoredAndTheBalanceIsZero() {
        val sponsoredEmpty = WalletState(sponsor = info, balances = mapOf(Coin.SOL to 0L, Coin.USDC to 5_000_000L))
        assertTrue(FeeRoute.hidesSol(sponsoredEmpty))
        assertEquals(mapOf(Coin.USDC to 5_000_000L), FeeRoute.visibleBalances(sponsoredEmpty))
        assertEquals(listOf(Coin.USDC, Coin.EURC), FeeRoute.payableCoins(sponsoredEmpty))

        val ownSol = sponsoredEmpty.copy(balances = mapOf(Coin.SOL to 10L, Coin.USDC to 0L))
        assertFalse(FeeRoute.hidesSol(ownSol))
        assertEquals(listOf(Coin.USDC, Coin.EURC, Coin.SOL), FeeRoute.payableCoins(ownSol))

        val noSponsor = sponsoredEmpty.copy(sponsor = null)
        assertFalse(FeeRoute.hidesSol(noSponsor))
        assertEquals(sponsoredEmpty.balances, FeeRoute.visibleBalances(noSponsor))
    }

    // ── клієнт спонсора ───────────────────────────────────────────────────

    private class FakeHttp(val reply: (String, String?) -> Pair<Int, String>) : HttpCall {
        val calls = mutableListOf<Pair<String, String?>>()
        override suspend fun request(url: String, body: String?): Pair<Int, String> {
            calls += url to body
            return reply(url, body)
        }
    }

    @Test
    fun infoIsReadFromTheServerAndA503MeansNoSponsor() = runBlocking {
        val ok = FakeHttp { _, _ -> 200 to """{"cluster":"devnet","fee_payer":"${sponsor.base58}"}""" }
        assertEquals(info, SponsorClient("https://x.test/", ok).info())
        assertEquals("https://x.test/api/solana/sponsor", ok.calls.single().first)
        assertNull(SponsorClient("https://x.test", FakeHttp { _, _ -> 503 to """{"detail":"not approved"}""" }).info())
        assertNull(SponsorClient("", ok).info())
    }

    @Test
    fun submitCarriesThePartialTransactionAndAnIdentitySignatureTheServerCanCheck() = runBlocking {
        val sig = Base58.encode(ByteArray(64) { 7 })
        val http = FakeHttp { _, _ -> 200 to """{"signature":"$sig","cluster":"devnet"}""" }
        val message = sponsoredMessage()
        val tx = SolanaTx.partiallySigned(message, listOf(user))
        assertEquals(SponsorResult.Sent(sig), SponsorClient("https://x.test", http).submit(tx, user))
        val (url, body) = http.calls.single()
        assertEquals("https://x.test/api/solana/sponsor/sign", url)
        val o = SolanaRpc.JSON.parseToJsonElement(body!!).jsonObject
        assertArrayEquals(tx, Base64.getDecoder().decode(o["tx_base64"]!!.jsonPrimitive.content))
        assertEquals(user.publicKey.base58, o["identity"]!!.jsonPrimitive.content)
        val idSig = Base58.decode(o["identity_sig"]!!.jsonPrimitive.content)!!
        val payload = "phantom-sponsor:v1:".toByteArray() + java.security.MessageDigest.getInstance("SHA-256").digest(message)
        val ok = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(user.publicKey.bytes, 0))
            update(payload, 0, payload.size)
        }.verifySignature(idSig)
        assertTrue(ok)
    }

    @Test
    fun refusalIsARefusalAndUnreachableFallsBackToPayingOneself() = runBlocking {
        val tx = SolanaTx.partiallySigned(sponsoredMessage(), listOf(user))
        assertEquals(
            SponsorResult.Refused("sponsor_quota"),
            SponsorClient("https://x.test", FakeHttp { _, _ -> 429 to """{"detail":"sponsor_quota"}""" }).submit(tx, user),
        )
        assertEquals(SponsorResult.Unavailable, SponsorClient("https://x.test", FakeHttp { _, _ -> 503 to "{}" }).submit(tx, user))
        assertEquals(
            SponsorResult.Unavailable,
            SponsorClient("https://x.test", FakeHttp { _, _ -> throw java.io.IOException("down") }).submit(tx, user),
        )
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun vector(name: String): String =
        javaClass.getResource("/solana/solders_vectors.txt")!!.readText().lines().first { it.startsWith("$name=") }.substringAfter('=')
}
