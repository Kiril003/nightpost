package local.phantom.companion.core.solana

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Збірка 18, N3: id рахунку пишеться НА МЕРЕЖУ. Картка в чаті — лише вказівка; а Memo-інструкція
 * в самій транзакції переказу лишається в мережі й видна в провіднику (як у купівлі ліцензії,
 * `SolanaLayer.buyLicence`). Тест читає байти ПОБУДОВАНОЇ транзакції, а не рядок, який ми
 * збираємося туди покласти.
 */
class BillMemoOnChainTest {

    private val bill = "3f2b8c1e-6d4a-4b7e-9a10-2c5d7e8f9a0b"
    private val owner = SolanaKey.of(ByteArray(32) { 0x21 })!!
    private val sponsor = SolanaKey.of(ByteArray(32) { ((it + 201) % 256).toByte() })!!
    private val to = SolanaKey.of(ByteArray(32) { (it + 101).toByte() })!!
    private val blockhash = ByteArray(32) { 0xAB.toByte() }

    private fun memosIn(message: ByteArray): List<String> =
        SolanaTx.parse(message)!!.instructions
            .filter { (program, _, _) -> program == Programs.MEMO }
            .map { (_, _, data) -> String(data, Charsets.UTF_8) }

    private fun build(coin: Coin, ref: String?, feePayer: SolanaKey = owner, cluster: Cluster = Cluster.DEVNET) =
        PaymentPost.transfer(owner, feePayer, to, coin, cluster, 7_500_000, blockhash, ref)

    @Test
    fun theBillIdIsInTheBuiltTransaction_forTokenAndForSol() {
        for (coin in listOf(Coin.USDC, Coin.EURC, Coin.SOL)) {
            val memos = memosIn(build(coin, bill))
            assertEquals("$coin: одна Memo-інструкція з id рахунку", listOf("nightpost-bill:v1:$bill"), memos)
            assertEquals(bill, PaymentFrame.refOfMemo(memos.single()))
        }
    }

    @Test
    fun theMemoRidesTheSponsoredTransferToo_withinTheServersLimit() {
        val msg = build(Coin.USDC, bill, feePayer = sponsor)
        val parsed = SolanaTx.parse(msg)!!
        assertEquals(sponsor, parsed.keys[0])
        assertEquals(listOf("nightpost-bill:v1:$bill"), memosIn(msg))
        // Memo без акаунтів: нових підписантів не додає, спонсор за нього нічого не підписує.
        assertTrue(parsed.instructions.first { it.first == Programs.MEMO }.second.isEmpty())
        // platform-site/server/sponsor.py: MAX_MEMO = 256 байт. Найдовший id — теж влазить.
        assertTrue(PaymentFrame.memo("a".repeat(PaymentFrame.MAX_REF))!!.toByteArray().size <= 256)
    }

    @Test
    fun aTransferWithoutABillIsTheSameBytesAsBefore() {
        for (coin in listOf(Coin.USDC, Coin.SOL)) {
            val plain = Transfers.build(owner, to, coin, Cluster.DEVNET, 7_500_000, blockhash, feePayer = owner)
            assertArrayEquals("$coin: без рахунку — без Memo і байт у байт як було", plain, build(coin, null))
            assertTrue(memosIn(build(coin, null)).isEmpty())
        }
    }

    @Test
    fun theMemoIsOnlyEverTheBillIdAndNothingAboutThePeople() {
        val text = memosIn(build(Coin.USDC, bill)).single()
        assertEquals("nightpost-bill:v1:$bill", text)
        assertFalse(text.contains(to.base58) || text.contains(owner.base58))
    }

    @Test
    fun aCrookedRefNeverReachesTheChain_andNeverMovesMoney() {
        for (bad in listOf("a;b", "", "x".repeat(37), "bill 1")) {
            assertNull(PaymentFrame.memo(bad))
            try {
                build(Coin.USDC, bad)
                fail("«$bad» не сміє збирати транзакцію")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertNull(PaymentFrame.memo(null))
    }

    @Test
    fun onlyOurMemoIsReadBackAsABill() {
        assertNull(PaymentFrame.refOfMemo(null))
        assertNull(PaymentFrame.refOfMemo("Nightpost licence"))
        assertNull(PaymentFrame.refOfMemo("nightpost-bill:v1:"))
        assertNull(PaymentFrame.refOfMemo("nightpost-bill:v1:a;b"))
        assertNull(PaymentFrame.refOfMemo("nightpost-bill:v2:$bill"))
        assertEquals(bill, PaymentFrame.refOfMemo(PaymentFrame.memo(bill)))
    }

    /**
     * Досяжність: переказ з екрана гаманця будує транзакцію саме цим шляхом, і той самий id
     * їде в картку — на мережі й у чаті не може бути двох різних рахунків.
     */
    @Test
    fun payPeerBuildsThroughTheMemoPathWithTheRouteRef() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-solana").isDirectory) dir = dir.parentFile
        val layer = File(dir ?: fail("корінь не знайдено") as Nothing, "core-solana/src/main/java/local/phantom/companion/core/solana/SolanaLayer.kt").readText()
        val pay = layer.substringAfter("suspend fun payPeer(", "").substringBefore("fun sentBefore(")
        assertTrue("payPeer є", pay.isNotEmpty())
        assertTrue("транзакція — з id рахунку", Regex("""PaymentPost\.transfer\([^\n]*\bref\)""").containsMatchIn(pay) && pay.contains("val ref = route.ref ?:"))
        assertFalse("напряму, повз Memo, переказ не збирається", pay.contains("Transfers.build("))
        assertTrue("картка несе той самий id", pay.contains("PaymentFrame(cluster, coin, units, to, sig, ref,"))
    }
}
