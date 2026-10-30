package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.solana.CashVoucher
import local.phantom.companion.core.solana.Cluster
import local.phantom.companion.core.solana.Coin
import local.phantom.companion.core.solana.Ed25519Keypair
import local.phantom.companion.core.solana.PaymentFrame
import local.phantom.companion.core.solana.SealFrame
import local.phantom.companion.core.solana.SolanaKey
import local.phantom.companion.core.solana.SolanaNotice
import local.phantom.companion.core.solana.SolanaTx
import local.phantom.companion.core.solana.WalletAddressFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMessengerSolanaKindsTest {

    private val to = SolanaKey.of("BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH")
    private val sig = "4WVSP661Y7sGVtg64ScknvfBFVX5rV2UqBaFRWmFwLxZJCGzdtEAnC1psHCiTYnxZgEg1KWMTDgP5o7YvJL6mZHT"

    @Test
    fun `переказ їде реченням для нинішнього бандла і полем payment для нової картки`() {
        val wire = PaymentFrame(Cluster.DEVNET, Coin.USDC, 2_500_000, to, sig).encode()
        val f = WebMessengerKinds.fields(wire)
        assertEquals(WebMessengerKinds.TEXT, f[WebMessengerKinds.KIND])
        assertEquals(WebMessengerCards.SAY_SOLANA, f[WebMessengerKinds.NOTICE])
        val p = f[WebMessengerKinds.PAYMENT] as Map<*, *>
        assertEquals("2.5", p["amount"])
        assertEquals("USDC", p["token"])
        assertEquals("devnet", p["cluster"])
        assertEquals(sig, p["signature"])
        assertEquals(false, p["offline_cash"])
        assertTrue(WebMessengerKinds.isVisible(wire))
        assertFalse(WebMessengerKinds.shipped(f).values.any { it is String && it.startsWith("PH-") })
    }

    @Test
    fun `міст віддає ref рахунку з кадру v2 і null для v1 та купюри`() {
        val bill = "3f2b8c1e-6d4a-4b7e-9a10-2c5d7e8f9a0b"
        val v2 = WebMessengerKinds.fields(PaymentFrame(Cluster.DEVNET, Coin.USDC, 7_500_000, to, sig, ref = bill).encode())[WebMessengerKinds.PAYMENT] as Map<*, *>
        assertEquals(bill, v2["ref"])
        assertEquals("7.5", v2["amount"])
        val v1 = WebMessengerKinds.fields(PaymentFrame(Cluster.DEVNET, Coin.USDC, 7_500_000, to, sig).encode())[WebMessengerKinds.PAYMENT] as Map<*, *>
        assertTrue("ключ є, значення null", v1.containsKey("ref"))
        assertNull(v1["ref"])
    }

    @Test
    fun `переказ і купюра в EURC доїжджають до картки з монетою EURC`() {
        val p = WebMessengerKinds.fields(PaymentFrame(Cluster.MAINNET, Coin.EURC, 2_500_000, to, sig).encode())[WebMessengerKinds.PAYMENT] as Map<*, *>
        assertEquals("EURC", p["token"])
        assertEquals("2.5", p["amount"])
        assertEquals(6, p["decimals"])
        assertEquals("mainnet", p["cluster"])

        val pocket = Ed25519Keypair(ByteArray(32) { 2 })
        val msg = CashVoucher.message(pocket.publicKey, SolanaKey.of(ByteArray(32) { 9 })!!, ByteArray(32) { 6 }, to, Coin.EURC, Cluster.DEVNET, 5_000_000)
        val wire = CashVoucher.of(Cluster.DEVNET, Coin.EURC, 5_000_000, to, SolanaTx.sign(msg, listOf(pocket))).encode()
        val cash = WebMessengerKinds.fields(wire)[WebMessengerKinds.PAYMENT] as Map<*, *>
        assertEquals("EURC", cash["token"])
        assertEquals("5", cash["amount"])
        assertEquals(true, cash["offline_cash"])
    }

    @Test
    fun `обмін адресами службовий — ні в стрічці, ні в непрочитаних, ні в шторці`() {
        val wire = WalletAddressFrame.encode(to)
        assertEquals(WebMessengerKinds.HIDDEN, WebMessengerKinds.fields(wire)[WebMessengerKinds.KIND])
        assertFalse(WebMessengerKinds.isVisible(wire))
        val msg = PeerMessage(id = "1", fromId = "olena", toId = "me", body = wire, sentAtMs = 10, state = PeerMessage.State.DELIVERED)
        assertEquals(0, WebMessengerKinds.unread(listOf(msg), "olena", 0))
        assertTrue(SolanaNotice.isSilent(wire))
    }

    @Test
    fun `відбиток файла їде полем seal без самого файла`() {
        val hex = "ab".repeat(32)
        val f = WebMessengerKinds.fields(SealFrame(Cluster.DEVNET, hex, sig).encode())
        assertEquals(mapOf("sha256" to hex, "signature" to sig, "cluster" to "devnet"), f[WebMessengerKinds.SEAL])
        assertNull(f[WebMessengerKinds.PAYMENT])
    }

    @Test
    fun `зіпсований переказ не стає карткою`() {
        val f = WebMessengerKinds.fields("PH-PAY:1;devnet;usdc;-5;x;y")
        assertNull(f[WebMessengerKinds.PAYMENT])
        assertEquals(WebMessengerKinds.TEXT, f[WebMessengerKinds.KIND])
    }
}
