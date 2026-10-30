package local.phantom.companion.core.solana

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WalletKeysCashSealTest {

    private val bip39 = Bip39(File("src/main/assets/bip39_english.txt").readLines().filter { it.isNotBlank() })
    private val abandon = (List(11) { "abandon" } + "about")

    @Test
    fun mnemonicMatchesTheBip39ReferenceVector() {
        assertEquals(abandon, bip39.fromEntropy(ByteArray(16)))
        assertTrue(bip39.isValid(abandon))
        assertFalse(bip39.isValid(List(12) { "abandon" }))
        assertFalse(bip39.isValid(abandon.dropLast(1) + "zoo"))
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            Bip39.seed(abandon).joinToString("") { "%02x".format(it) },
        )
        val fresh = bip39.generate()
        assertEquals(12, fresh.size)
        assertTrue(bip39.isValid(fresh))
    }

    @Test
    fun phraseOpensTheSameAddressAsPhantomAndSolflare() {
        val seed = Bip39.seed(abandon)
        assertEquals("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk", Ed25519Keypair(Slip10.derive(seed, Slip10.SOLANA_MAIN)).publicKey.base58)
        assertEquals("Hh8QwFUA6MtVu1qAoq12ucvFHNwCcVTV7hpWjeY1Hztb", Ed25519Keypair(Slip10.derive(seed, Slip10.SOLANA_CASH)).publicKey.base58)
    }

    @Test
    fun twoSignerTransactionPutsSignaturesInKeyOrder() {
        val seed = Bip39.seed(abandon)
        val main = Ed25519Keypair(Slip10.derive(seed, Slip10.SOLANA_MAIN))
        val pocket = Ed25519Keypair(Slip10.derive(seed, Slip10.SOLANA_CASH))
        val msg = SolanaTx.message(main.publicKey, ByteArray(32) { 1 }, Programs.createNonceWithSeed(main.publicKey, pocket.publicKey, "phantom-cash-0", 1_447_680, pocket.publicKey))
        val tx = SolanaTx.sign(msg, listOf(pocket, main))
        assertEquals(2, tx[0].toInt())
        assertTrue(verifies(main.publicKey, tx.copyOfRange(1, 65), msg))
        assertTrue(verifies(pocket.publicKey, tx.copyOfRange(65, 129), msg))
    }

    @Test
    fun cashVoucherIsASignedNonceTransferThatSurvivesTheWire() {
        val seed = Bip39.seed(abandon)
        val pocket = Ed25519Keypair(Slip10.derive(seed, Slip10.SOLANA_CASH))
        val to = SolanaKey.of(ByteArray(32) { 42 })!!
        val nonce = Programs.seededAddress(pocket.publicKey, "phantom-cash-0", Programs.SYSTEM)
        val msg = CashVoucher.message(pocket.publicKey, nonce, ByteArray(32) { 9 }, to, Coin.USDC, Cluster.DEVNET, 5_000_000)
        val tx = SolanaTx.sign(msg, listOf(pocket))
        val v = CashVoucher.of(Cluster.DEVNET, Coin.USDC, 5_000_000, to, tx)
        val wire = v.encode()
        assertTrue(wire.startsWith("PH-CASH:1;devnet;usdc;5000000;"))
        val back = CashVoucher.decode(wire)!!
        assertEquals(v, back)
        assertEquals(Base58.encode(tx.copyOfRange(1, 65)), back.payment.signature)
        assertTrue(verifies(pocket.publicKey, tx.copyOfRange(1, 65), SolanaTx.messageOf(back.tx)!!))
        assertNull(CashVoucher.decode(wire.replace(";usdc;", ";skr;")))
        assertNull(CashVoucher.decode(wire.replace(";5000000;", ";6000000;")))
        assertNull(CashVoucher.decode(wire.replace(to.base58, SolanaKey.of(ByteArray(32) { 43 })!!.base58)))
        assertNull(CashVoucher.decode(wire.dropLast(40)))
        assertNull(CashVoucher.decode("PH-CASH:1;devnet;usdc;1;${to.base58};AAAA"))
    }

    @Test
    fun voucherFirstInstructionAdvancesTheNonce() {
        val pocket = SolanaKey.of(ByteArray(32) { 3 })!!
        val nonce = SolanaKey.of(ByteArray(32) { 4 })!!
        val to = SolanaKey.of(ByteArray(32) { 5 })!!
        val msg = CashVoucher.message(pocket, nonce, ByteArray(32) { 6 }, to, Coin.SOL, Cluster.DEVNET, 7)
        val withNonce = Transfers.build(pocket, to, Coin.SOL, Cluster.DEVNET, 7, ByteArray(32) { 6 }, nonce = nonce)
        assertTrue(msg.contentEquals(withNonce))
        assertNotEquals(-1, msg.indexOfSequence(byteArrayOf(4, 0, 0, 0)))
    }

    @Test
    fun sealFrameCarriesOnlyTheFingerprint() {
        val hex = SealFrame.sha256Hex("фото".toByteArray())
        val sig = "4WVSP661Y7sGVtg64ScknvfBFVX5rV2UqBaFRWmFwLxZJCGzdtEAnC1psHCiTYnxZgEg1KWMTDgP5o7YvJL6mZHT"
        val f = SealFrame(Cluster.DEVNET, hex, sig)
        assertEquals(f, SealFrame.decode(f.encode()))
        assertNull(SealFrame.decode(f.encode().replace(hex, hex.uppercase())))
        assertEquals("phantom-seal:v1:$hex", SealFrame.memo(hex))
    }

    @Test
    fun sealIsAnchoredOnlyWhenTheMemoCarriesThatHash() {
        val hex = "a".repeat(64)
        fun tx(memo: String, err: String = "null") = SolanaRpc.JSON.parseToJsonElement(
            """{"blockTime":1759300000,"meta":{"err":$err},"transaction":{"message":{"instructions":[
               {"program":"spl-memo","programId":"MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr","parsed":"$memo","stackHeight":null}]}}}""",
        )
        assertEquals(SealVerdict.Anchored(1_759_300_000), SealCheck.judge(hex, tx("phantom-seal:v1:$hex")))
        assertEquals(SealVerdict.Mismatch, SealCheck.judge(hex, tx("phantom-seal:v1:" + "b".repeat(64))))
        assertEquals(SealVerdict.Failed, SealCheck.judge(hex, tx("phantom-seal:v1:$hex", """{"x":1}""")))
        assertEquals(SealVerdict.Pending, SealCheck.judge(hex, kotlinx.serialization.json.JsonNull))
    }

    private fun verifies(key: SolanaKey, sig: ByteArray, msg: ByteArray): Boolean =
        Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(key.bytes, 0)); update(msg, 0, msg.size) }.verifySignature(sig)

    private fun ByteArray.indexOfSequence(seq: ByteArray): Int =
        (0..size - seq.size).firstOrNull { i -> seq.indices.all { this[i + it] == seq[it] } } ?: -1
}
