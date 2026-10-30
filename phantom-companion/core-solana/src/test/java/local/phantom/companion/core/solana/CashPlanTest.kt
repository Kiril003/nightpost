package local.phantom.companion.core.solana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CashPlanTest {

    private val owner = Ed25519Keypair(ByteArray(32) { 1 })
    private val pocket = Ed25519Keypair(ByteArray(32) { 2 })

    @Test
    fun fundingAllNotesAtOnceFitsOnePacketForEveryCoin() {
        for (coin in Coin.on(Cluster.DEVNET)) {
            val msg = CashPlan.fund(owner.publicKey, pocket.publicKey, coin, Cluster.DEVNET, 3_000_000, 1_447_680, CashPlan.notes(pocket.publicKey), ByteArray(32) { 7 })
            val tx = SolanaTx.sign(msg, listOf(owner, pocket))
            assertTrue("${coin.symbol}: ${tx.size} bytes", tx.size <= 1232)
            assertEquals(2, SolanaTx.signerCount(msg))
        }
    }

    @Test
    fun notesLiveAtTheAddressesTheChainDerivesFromThePocket() {
        val notes = CashPlan.notes(pocket.publicKey)
        assertEquals(CashPlan.NOTES, notes.map { it.nonce }.distinct().size)
        notes.forEach { assertEquals(Programs.seededAddress(pocket.publicKey, it.seed, Programs.SYSTEM).base58, it.nonce) }
    }

    @Test
    fun toppingUpThePocketKeepsNotesThatAreStillOnTheirWay() {
        val fresh = CashPlan.notes(pocket.publicKey)
        val inFlight = fresh[0].copy(value = "V", voucherSig = "SIG", voucherUnits = 400)
        val before = CashPocket("usdc", 1_000, listOf(inFlight, fresh[1].copy(value = "W"), fresh[2]))
        val after = CashPlan.merge(before, Coin.USDC, fresh)!!
        assertEquals(before.notes, after.notes)
        assertEquals(400L, after.notes.sumOf { it.voucherUnits })
        assertEquals(CashPlan.NOTES, CashPlan.merge(CashPocket(), Coin.SOL, fresh)!!.notes.size)
        assertEquals(null, CashPlan.merge(before, Coin.SOL, fresh))
        assertEquals("sol", CashPlan.merge(before.copy(notes = fresh), Coin.SOL, fresh)!!.coin)
    }

    private val ownerKey = SolanaKey.of(ByteArray(32) { (it + 1).toByte() })!!
    private val pocketKey = SolanaKey.of(ByteArray(32) { 11 })!!
    private val to = SolanaKey.of(ByteArray(32) { (it + 101).toByte() })!!
    private val nonce = SolanaKey.of(ByteArray(32) { 9 })!!
    private val blockhash = ByteArray(32) { 0xAB.toByte() }

    @Test
    fun usdcVoucherCreatesTheRecipientAccountAtThePocketsCostAndIsByteIdentical() {
        val msg = CashVoucher.message(pocketKey, nonce, blockhash, to, Coin.USDC, Cluster.DEVNET, 5_000_000)
        assertEquals(vector("CASH_SPL"), msg.hex())
        assertEquals(1, SolanaTx.signerCount(msg))
    }

    @Test
    fun usdcFundingIsByteIdentical() {
        val msg = CashPlan.fund(ownerKey, pocketKey, Coin.USDC, Cluster.DEVNET, 3_000_000, 1_447_680, CashPlan.notes(pocketKey), blockhash)
        assertEquals(vector("FUND_SPL"), msg.hex())
    }

    @Test
    fun voucherOnAnotherMintIsNotTakenForUsdc() {
        val testMint = SolanaKey.of(ByteArray(32) { 42 })!!
        val pocket = Ed25519Keypair(ByteArray(32) { 2 })
        val msg = CashVoucher.message(pocket.publicKey, nonce, blockhash, to, Coin.USDC, Cluster.DEVNET, 5_000_000, mint = testMint)
        val wire = CashVoucher.of(Cluster.DEVNET, Coin.USDC, 5_000_000, to, SolanaTx.sign(msg, listOf(pocket))).encode()
        assertNull(CashVoucher.decode(wire))
        assertNotNull(CashVoucher.decode(wire, mint = testMint))
        val usdc = CashVoucher.message(pocket.publicKey, nonce, blockhash, to, Coin.USDC, Cluster.DEVNET, 5_000_000)
        val usdcWire = CashVoucher.of(Cluster.DEVNET, Coin.USDC, 5_000_000, to, SolanaTx.sign(usdc, listOf(pocket))).encode()
        assertNotNull(CashVoucher.decode(usdcWire))
        assertNull(CashVoucher.decode(usdcWire, mint = testMint))
    }

    @Test
    fun eurcVoucherIsByteIdenticalAndNeverPassesForUsdcOfTheSameSize() {
        assertEquals(vector("CASH_EURC"), CashVoucher.message(pocketKey, nonce, blockhash, to, Coin.EURC, Cluster.DEVNET, 5_000_000).hex())
        val pocket = Ed25519Keypair(ByteArray(32) { 2 })
        val msg = CashVoucher.message(pocket.publicKey, nonce, blockhash, to, Coin.EURC, Cluster.DEVNET, 5_000_000)
        val wire = CashVoucher.of(Cluster.DEVNET, Coin.EURC, 5_000_000, to, SolanaTx.sign(msg, listOf(pocket))).encode()
        assertTrue(wire.startsWith("PH-CASH:1;devnet;eurc;5000000;"))
        val back = CashVoucher.decode(wire)!!
        assertEquals(Coin.EURC, back.payment.coin)
        assertEquals("5", back.payment.amount)
        assertNull(CashVoucher.decode(wire.replace(";eurc;", ";usdc;")))
        val usdc = CashVoucher.message(pocket.publicKey, nonce, blockhash, to, Coin.USDC, Cluster.DEVNET, 5_000_000)
        val usdcWire = CashVoucher.of(Cluster.DEVNET, Coin.USDC, 5_000_000, to, SolanaTx.sign(usdc, listOf(pocket))).encode()
        assertNull(CashVoucher.decode(usdcWire.replace(";usdc;", ";eurc;")))
    }

    @Test
    fun tokenNotesStopBeforeThePocketRunsOutOfSolForRecipientAccounts() {
        for (inFlight in 0 until CashPlan.NOTES) assertTrue(CashPlan.solCovers(Coin.USDC, CashPlan.FEES, inFlight))
        val spent = CashPlan.FEES - 8 * (CashPlan.TOKEN_ACCOUNT_RENT + CashPlan.NOTE_FEE)
        assertTrue(CashPlan.solCovers(Coin.USDC, spent, 0))
        assertFalse(CashPlan.solCovers(Coin.USDC, spent - (CashPlan.TOKEN_ACCOUNT_RENT + CashPlan.NOTE_FEE), 0))
        assertFalse(CashPlan.solCovers(Coin.USDC, CashPlan.POCKET_RENT + CashPlan.TOKEN_ACCOUNT_RENT, 0))
        assertTrue(CashPlan.solCovers(Coin.SOL, 0, 2))
        assertTrue(CashPlan.solCovers(Coin.USDC, -1, 2))
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun vector(name: String): String =
        javaClass.getResource("/solana/solders_vectors.txt")!!.readText().lines().first { it.startsWith("$name=") }.substringAfter('=')
}
