package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.solana.Cluster
import local.phantom.companion.core.solana.Coin
import local.phantom.companion.core.solana.PaymentFrame
import local.phantom.companion.core.solana.SolanaKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Знахідка 2 рев'ю збірки 18: частка рахунку лягає в групу під конвертом
 * [PeerGroup.encode], і журнал переказів її не бачив — сторож казав «перший переказ
 * цій людині» після щойно заплаченої частки, а заклад завжди був «уперше».
 */
class SentPaymentsGroupTest {

    private val me = "me"
    private val sig = "4WVSP661Y7sGVtg64ScknvfBFVX5rV2UqBaFRWmFwLxZJCGzdtEAnC1psHCiTYnxZgEg1KWMTDgP5o7YvJL6mZHT"
    private val olenaWallet = SolanaKey.of("BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH")
    private val venueWallet = SolanaKey.of(ByteArray(32) { 7 })!!
    private val bill = "3f2b8c1e-6d4a-4b7e-9a10-2c5d7e8f9a0b"
    private val cafe = PeerGroup.Group(id = "grp-cafe", name = "Cafe X · 19:00", memberIds = listOf(me, "olena", "petro"))

    private fun frame(to: SolanaKey, units: Long, ref: String? = null) = PaymentFrame(Cluster.DEVNET, Coin.USDC, units, to, sig, ref)

    private fun row(id: String, from: String, to: String, body: String, at: Long) = PeerMessage(id = id, fromId = from, toId = to, body = body, sentAtMs = at)

    /** Груповий лист — копія на кожного учасника, як його кладе `sendToGroup`. */
    private fun toGroup(id: String, body: String, at: Long) =
        cafe.others(me).map { to -> row("$id-$to", me, to, PeerGroup.encode(cafe, body), at) }

    private val wallets = mapOf("olena" to olenaWallet)

    @Test
    fun aBillShareInTheGroupCountsAsPaidToThatPerson_once() {
        val share = frame(olenaWallet, 7_500_000, bill)
        val log = toGroup("g1", share.encode(), 1_000)
        val sent = sentPaymentsOf(log, me, listOf("olena", "petro"), wallets::get)
        assertEquals("копія на учасника — один переказ, і він Олені", listOf("olena" to share), sent)
    }

    @Test
    fun oneToOneAndGroupBothCount_andTheVenueIsMatchedByAddress() {
        val direct = frame(olenaWallet, 2_000_000)
        val share = frame(olenaWallet, 7_500_000, bill)
        val toVenue = frame(venueWallet, 30_000_000, bill)
        val log = listOf(row("d1", me, "olena", direct.encode(), 500)) +
            toGroup("g1", share.encode(), 1_000) +
            toGroup("g2", toVenue.encode(), 2_000) +
            // Чужа частка в тій самій групі — не мій переказ.
            listOf(row("p1", "petro", me, PeerGroup.encode(cafe, frame(olenaWallet, 1_000_000, bill).encode()), 3_000))
        val sent = sentPaymentsOf(log, me, listOf("olena", "petro"), wallets::get)
        assertEquals(setOf("olena" to direct, "olena" to share, "" to toVenue), sent.toSet())
        assertEquals(3, sent.size)
        // Так їх і читає сторож: Олені — двічі, закладу — за адресою.
        assertEquals(2, sent.count { it.first == "olena" })
        assertEquals(listOf(toVenue), sent.filter { it.second.to == venueWallet }.map { it.second })
    }

    @Test
    fun plainGroupWordsAreNotPayments() {
        val log = toGroup("g1", "хто платить за каву?", 1_000)
        assertEquals(emptyList<Pair<String, PaymentFrame>>(), sentPaymentsOf(log, me, listOf("olena"), wallets::get))
    }
}
