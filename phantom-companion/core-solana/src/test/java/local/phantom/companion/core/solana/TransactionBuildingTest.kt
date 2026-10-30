package local.phantom.companion.core.solana

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Еталонні байти згенеровано solders 0.29 (Rust solana-sdk), не нашим кодом. */
class TransactionBuildingTest {

    private val payer = SolanaKey.of(ByteArray(32) { (it + 1).toByte() })!!
    private val to = SolanaKey.of(ByteArray(32) { (it + 101).toByte() })!!
    private val ref = SolanaKey.of(ByteArray(32) { 7 })!!
    private val nonce = SolanaKey.of(ByteArray(32) { 9 })!!
    private val blockhash = ByteArray(32) { 0xAB.toByte() }

    @Test
    fun base58RoundTripsAndKeepsLeadingZeros() {
        assertEquals("4wBqpZM9xaSheZzJSMawUKKwhdpChKbZ5eu5ky4Vigw", payer.base58)
        assertEquals("11111111111111111111111111111111", Programs.SYSTEM.base58)
        assertArrayEquals(ByteArray(32), Programs.SYSTEM.bytes)
        assertEquals(payer, SolanaKey.parse(payer.base58))
        assertNull(SolanaKey.parse("0OIl"))
        assertNull(SolanaKey.parse("abc"))
    }

    @Test
    fun associatedTokenAddressMatchesTheChain() {
        val usdcDev = Coin.USDC.mint(Cluster.DEVNET)!!
        assertEquals("6STAjLYEsKyGotW3YxEv9Rbyxok2cNbLZs5aFw8Px7JC", Programs.associatedTokenAddress(to, usdcDev).base58)
        val owner = SolanaKey.of(ByteArray(32) { 4 })!!
        val (pda, bump) = SolanaKey.findProgramAddress(
            listOf(owner.bytes, Programs.TOKEN.bytes, Coin.USDC.mint(Cluster.MAINNET)!!.bytes),
            Programs.ASSOCIATED_TOKEN,
        )
        assertEquals("DhscvS2mfxadjGkSvoGxKzgEi6ZnuLunmFEjcZ5sd5u1", pda.base58)
        assertEquals(248, bump)
    }

    @Test
    fun curveCheckAgreesWithDalek() {
        assertTrue(Ed25519Curve.isOnCurve(ByteArray(32) { 1 }))
        assertFalse(Ed25519Curve.isOnCurve(payer.bytes))
        assertFalse(Ed25519Curve.isOnCurve(SolanaKey.of("6STAjLYEsKyGotW3YxEv9Rbyxok2cNbLZs5aFw8Px7JC").bytes))
    }

    @Test
    fun solTransferWithReferenceIsByteIdenticalToSolanaSdk() {
        val msg = Transfers.build(payer, to, Coin.SOL, Cluster.DEVNET, 1_500_000, blockhash, reference = ref)
        assertEquals(vector("SOL"), msg.hex())
    }

    @Test
    fun usdcTransferCreatesRecipientAccountAndIsByteIdentical() {
        val msg = Transfers.build(payer, to, Coin.USDC, Cluster.DEVNET, 2_500_000, blockhash, memo = "PHANTOM")
        assertEquals(vector("USDC"), msg.hex())
    }

    @Test
    fun eurcTransferIsByteIdenticalOnBothClustersBecauseCircleUsesOneMint() {
        assertEquals("HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr", Coin.EURC.mint(Cluster.MAINNET)!!.base58)
        assertEquals(Coin.EURC.mint(Cluster.MAINNET), Coin.EURC.mint(Cluster.DEVNET))
        assertEquals(6, Coin.EURC.decimals)
        assertEquals(vector("EURC_ATA"), Programs.associatedTokenAddress(to, Coin.EURC.mint(Cluster.DEVNET)!!).base58)
        for (cluster in Cluster.entries) {
            val msg = Transfers.build(payer, to, Coin.EURC, cluster, 2_500_000, blockhash, memo = "PHANTOM")
            assertEquals(cluster.wire, vector("EURC"), msg.hex())
        }
    }

    @Test
    fun durableNonceTransferAdvancesTheNonceFirst() {
        val msg = Transfers.build(payer, to, Coin.SOL, Cluster.DEVNET, 42, blockhash, nonce = nonce)
        assertEquals(vector("NONCE"), msg.hex())
    }

    @Test
    fun unsignedTransactionReservesOneSignatureSlot() {
        val msg = Transfers.build(payer, to, Coin.SOL, Cluster.DEVNET, 5, blockhash)
        val tx = SolanaTx.unsigned(msg)
        assertEquals(1 + 64 + msg.size, tx.size)
        assertEquals(1, tx[0].toInt())
        assertArrayEquals(msg, SolanaTx.messageOf(tx))
        val signed = SolanaTx.withSignature(msg, ByteArray(64) { 3 })
        assertArrayEquals(ByteArray(64) { 3 }, SolanaTx.firstSignature(signed))
    }

    @Test
    fun coinsAreOfferedStablesFirstAndSkrOnlyOnMainnet() {
        assertFalse(Coin.SKR.availableOn(Cluster.DEVNET))
        assertEquals(listOf(Coin.USDC, Coin.EURC, Coin.SOL), Coin.on(Cluster.DEVNET))
        assertEquals(listOf(Coin.USDC, Coin.EURC, Coin.SOL, Coin.SKR), Coin.on(Cluster.MAINNET))
        assertEquals(Coin.EURC, Coin.of("eurc"))
        assertEquals("SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3", Coin.SKR.mint(Cluster.MAINNET)!!.base58)
    }

    @Test
    fun amountsNeverRoundSilently() {
        assertEquals(1_500_000L, Amounts.toUnits("1,5", Coin.USDC))
        assertEquals(1L, Amounts.toUnits("0.000000001", Coin.SOL))
        assertNull(Amounts.toUnits("0.0000001", Coin.USDC))
        assertNull(Amounts.toUnits("-1", Coin.SOL))
        assertNull(Amounts.toUnits("0", Coin.SOL))
        assertNull(Amounts.toUnits("abc", Coin.SOL))
        assertEquals("1.5", Amounts.format(1_500_000, Coin.USDC))
        assertEquals("0.000000001", Amounts.format(1, Coin.SOL))
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun nonceAccountIsCreatedFromThePocketSeedWithOneFunder() {
        val pocket = SolanaKey.of(ByteArray(32) { 11 })!!
        assertEquals(vector("NONCEADDR"), Programs.seededAddress(pocket, "phantom-cash-0", Programs.SYSTEM).base58)
        val msg = SolanaTx.message(payer, blockhash, Programs.createNonceWithSeed(payer, pocket, "phantom-cash-0", 1_447_680, pocket))
        assertEquals(vector("NONCEINIT"), msg.hex())
        assertEquals(2, SolanaTx.signerCount(msg))
    }

    private fun vector(name: String): String =
        javaClass.getResource("/solana/solders_vectors.txt")!!.readText().lines().first { it.startsWith("$name=") }.substringAfter('=')
}
