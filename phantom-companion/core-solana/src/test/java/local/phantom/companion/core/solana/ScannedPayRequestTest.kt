package local.phantom.companion.core.solana

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScannedPayRequestTest {

    private val owner = SolanaKey.of(ByteArray(32) { 0x21 })!!
    private val sponsor = SolanaKey.of(ByteArray(32) { ((it + 201) % 256).toByte() })!!
    private val to = SolanaKey.of("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU")
    private val other = SolanaKey.of(ByteArray(32) { (it + 77).toByte() })!!
    private val ref = SolanaKey.of(ByteArray(32) { (it + 33).toByte() })!!
    private val blockhash = ByteArray(32) { 0xAB.toByte() }
    private val bill = "3f2b8c1e-6d4a-4b7e-9a10-2c5d7e8f9a0b"

    private fun memosIn(message: ByteArray): List<String> =
        SolanaTx.parse(message)!!.instructions
            .filter { (program, _, _) -> program == Programs.MEMO }
            .map { (_, _, data) -> String(data, Charsets.UTF_8) }

    @Test
    fun parsesReferenceAndMemoFromSolanaPayRequest() {
        val usdc = Coin.USDC.mint(Cluster.DEVNET)!!.base58
        val link = PayLink.parse(
            "solana:${to.base58}?amount=1.5&spl-token=$usdc&reference=${ref.base58}&memo=Table%207",
            Cluster.DEVNET,
        )
        assertNotNull(link)
        assertEquals(to, link!!.to)
        assertEquals(Coin.USDC, link.coin)
        assertEquals("1.5", link.amount)
        assertEquals(ref, link.reference)
        assertEquals("Table 7", link.memo)

        val blankMemo = PayLink.parse("solana:${to.base58}?reference=${ref.base58}&memo=%20%20", Cluster.DEVNET)
        assertNotNull(blankMemo)
        assertEquals(ref, blankMemo!!.reference)
        assertNull(blankMemo.memo)

        val bare = PayLink.parse(to.base58, Cluster.DEVNET)
        assertNotNull(bare)
        assertNull(bare!!.reference)
        assertNull(bare.memo)
    }

    @Test
    fun rejectsMalformedOrForgedReferenceAndMemo() {
        val short31 = Base58.encode(ByteArray(31) { 9 })
        val over256 = "a".repeat(257)

        assertNull(
            PayLink.parse(
                "solana:${to.base58}?reference=${ref.base58}&reference=${other.base58}",
                Cluster.DEVNET,
            )
        )
        assertNull(PayLink.parse("solana:${to.base58}?reference=$short31", Cluster.DEVNET))
        assertNull(PayLink.parse("solana:${to.base58}?memo=$over256", Cluster.DEVNET))
        assertNull(PayLink.parse("solana:${to.base58}?memo=nightpost-bill:v1:x", Cluster.DEVNET))
    }

    @Test
    fun scannedExtrasCarryReferenceAndMemoIntoRouteOnlyForScannedTarget() {
        val link = PayLink(to, Coin.USDC, "1.5", reference = ref, memo = "Table 7")
        val extras = WalletActivity.scannedExtras(link)
        val open = WalletActivity.Opened.read(extras::get)
        val target = SolanaContact("", to.short(), to)
        val stranger = SolanaContact("peer-2", other.short(), other)

        assertTrue(open.billArmed)
        assertEquals(
            PayRoute(null, null, ref, "Table 7"),
            open.routeFor(target, cash = false, armed = open.billArmed),
        )
        assertEquals(PayRoute(), open.routeFor(stranger, cash = false, armed = open.billArmed))
        assertEquals(PayRoute(), open.routeFor(target, cash = true, armed = open.billArmed))

        val unscanned = WalletActivity.Opened.read((extras - WalletActivity.EXTRA_SCANNED)::get)
        assertNull(unscanned.reference)
        assertNull(unscanned.memo)
        assertEquals(PayRoute(), unscanned.routeFor(target, cash = false, armed = unscanned.billArmed))
    }

    @Test
    fun transferIncludesScannedReferenceKeyAndMemoOnChain() {
        val msg = PaymentPost.transfer(
            owner = owner,
            feePayer = sponsor,
            to = to,
            coin = Coin.USDC,
            cluster = Cluster.DEVNET,
            units = 7_500_000,
            recentBlockhash = blockhash,
            ref = null,
            reference = ref,
            memo = "Table 7",
        )
        val parsed = SolanaTx.parse(msg)!!
        assertTrue(ref in parsed.keys)
        assertEquals(listOf("Table 7"), memosIn(msg))

        try {
            PaymentPost.transfer(
                owner = owner,
                feePayer = sponsor,
                to = to,
                coin = Coin.USDC,
                cluster = Cluster.DEVNET,
                units = 7_500_000,
                recentBlockhash = blockhash,
                ref = bill,
                reference = ref,
                memo = null,
            )
            fail("bill ref and scanned reference must not mix")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun payPeerForwardsScannedReferenceAndMemoIntoTransfer() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-solana").isDirectory) dir = dir.parentFile
        val layer = File(
            dir ?: fail("root not found") as Nothing,
            "core-solana/src/main/java/local/phantom/companion/core/solana/SolanaLayer.kt",
        ).readText()
        val pay = layer.substringAfter("suspend fun payPeer(", "").substringBefore("fun sentBefore(")
        assertTrue(pay.isNotEmpty())
        assertTrue(pay.contains("validNote?.takeIf { route.reference == null && route.memo == null }"))
        assertTrue(pay.contains("reference = route.reference, memo = route.memo, ref = ref)"))
    }
}
