package local.phantom.companion.core.solana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayAndPayerTest {

    private val sig = "4WVSP661Y7sGVtg64ScknvfBFVX5rV2UqBaFRWmFwLxZJCGzdtEAnC1psHCiTYnxZgEg1KWMTDgP5o7YvJL6mZHT"
    private val stranger = SolanaKey.of(ByteArray(32) { 5 })!!

    private fun tx(name: String) = SolanaRpc.JSON.parseToJsonElement(javaClass.getResource("/solana/$name")!!.readText())

    @Test
    fun aSolTransferIsConfirmedOnlyWhenItCameFromTheSendersWallet() {
        val t = tx("tx_sol.json")
        val f = PaymentFrame(Cluster.DEVNET, Coin.SOL, 2_824_480, SolanaKey.of("BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH"), sig)
        assertEquals(PaymentStatus.CONFIRMED, PaymentCheck.judge(f, t, SolanaKey.of("2BhFbXNz33gKs66W9c7CY8a6myCQ5TvezCmVzXJEbbqw")))
        assertEquals(PaymentStatus.MISMATCH, PaymentCheck.judge(f, t, stranger))
    }

    @Test
    fun aTokenTransferIsConfirmedOnlyWhenTheSendersBalanceWentDown() {
        val t = tx("tx_spl.json")
        val f = PaymentFrame(Cluster.DEVNET, Coin.USDC, 10_000, SolanaKey.of("4BxwEwYtKfjRoTfNXFdQbnrXfzQ23GeRxt1p9M5dwHQT"), sig)
        assertEquals(PaymentStatus.CONFIRMED, PaymentCheck.judge(f, t, SolanaKey.of("BMMXLRyafgqRJj1Z3AF332ekFbWHegT6qKXBjffvkUQ9")))
        assertEquals(PaymentStatus.MISMATCH, PaymentCheck.judge(f, t, stranger))
    }

    @Test
    fun oneSignatureIsOneCardAndTheClaimSurvivesARestart() {
        var saved: Map<String, String> = emptyMap()
        val claims = SignatureClaims(saved) { saved = it }
        assertTrue(claims.claim(sig, "alice:m1"))
        assertTrue(claims.claim(sig, "alice:m1"))
        assertFalse(claims.claim(sig, "mallory:m9"))
        assertFalse(SignatureClaims(saved) { saved = it }.claim(sig, "mallory:m9"))
        assertTrue(SignatureClaims(saved) { saved = it }.claim(sig, "alice:m1"))
    }

    @Test
    fun aReplayedOrUnattributableCardIsNeverConfirmedBeforeTheNetworkIsAsked() {
        val payer = SolanaKey.of("BMMXLRyafgqRJj1Z3AF332ekFbWHegT6qKXBjffvkUQ9")
        assertEquals(CardVerdict(PaymentStatus.MISMATCH, CardVerdict.REPLAY), CardVerdict.before(claimed = false, mine = false, payer = payer))
        assertEquals(CardVerdict(PaymentStatus.MISMATCH, CardVerdict.PAYER_UNKNOWN), CardVerdict.before(claimed = true, mine = false, payer = null))
        assertEquals(CardVerdict(PaymentStatus.MISMATCH, CardVerdict.OTHER_PAYER), CardVerdict.before(true, false, payer, signer = stranger))
        assertNull(CardVerdict.before(true, false, payer, signer = payer))
        assertNull(CardVerdict.before(true, mine = true, payer = null))
    }

    @Test
    fun aVoucherNamesThePocketThatSignedIt() {
        val pocket = Ed25519Keypair(Slip10.derive(Bip39.seed(List(11) { "abandon" } + "about"), Slip10.SOLANA_CASH))
        val to = SolanaKey.of(ByteArray(32) { 42 })!!
        val nonce = Programs.seededAddress(pocket.publicKey, "phantom-cash-0", Programs.SYSTEM)
        val tx = SolanaTx.sign(CashVoucher.message(pocket.publicKey, nonce, ByteArray(32) { 9 }, to, Coin.SOL, Cluster.DEVNET, 7), listOf(pocket))
        assertEquals(pocket.publicKey, CashVoucher.decode(CashVoucher.of(Cluster.DEVNET, Coin.SOL, 7, to, tx).encode())!!.signer)
    }

    @Test
    fun thePocketTravelsInItsOwnLineThatOldBuildsIgnore() {
        val wire = WalletAddressFrame.encodePocket(stranger)
        assertTrue(WalletAddressFrame.isAddress(wire))
        assertEquals(stranger, WalletAddressFrame.decodePocket(wire))
        assertNull(WalletAddressFrame.decode(wire))
        assertNull(WalletAddressFrame.decodePocket(WalletAddressFrame.encode(stranger)))
    }
}
