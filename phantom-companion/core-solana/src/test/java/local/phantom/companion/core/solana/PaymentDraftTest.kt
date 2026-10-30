package local.phantom.companion.core.solana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class PaymentDraftTest {

    private val olena = SolanaKey.of(ByteArray(32) { 7 })!!
    private val other = SolanaKey.of(ByteArray(32) { 9 })!!

    private fun compose(amount: String?, coin: String?, history: List<SentBefore> = emptyList(), cluster: Cluster = Cluster.DEVNET, source: AddressSource = AddressSource.CONVERSATION) =
        PaymentDrafts.compose("p1", "Олена", olena, source, coin, amount?.let(::BigDecimal), cluster, history)

    private fun ready(o: DraftOutcome) = (o as? DraftOutcome.Ready ?: error("not ready: $o"))

    @Test
    fun `amount goes to minimal units without rounding`() {
        assertEquals(5_500_000L, ready(compose("5.5", "usdc")).draft.units)
        assertEquals(100_000_000L, ready(compose("0.1", "sol")).draft.units)
        assertEquals(1L, ready(compose("0.000001", "usdc")).draft.units)
        assertEquals(123_456_789L, ready(compose("0.123456789", "sol")).draft.units)
    }

    @Test
    fun `finer than the coin can carry is refused, not rounded`() {
        val r = compose("0.0000001", "usdc")
        assertTrue(r is DraftOutcome.BadAmount)
        assertEquals("0.000001", (r as DraftOutcome.BadAmount).smallest)
        assertTrue(compose("0.0000000001", "sol") is DraftOutcome.BadAmount)
        assertTrue(compose("0", "usdc") is DraftOutcome.BadAmount)
    }

    @Test
    fun `coin that does not exist on the network is said`() {
        assertEquals(DraftOutcome.CoinNotHere(Coin.SKR, Cluster.DEVNET), compose("1", "skr"))
    }

    @Test
    fun `unsaid coin follows history, else USDC`() {
        val d = ready(compose("1", null)).draft
        assertEquals(Coin.USDC, d.coin)
        assertFalse(d.coinSaid)
        val sol = List(2) { SentBefore(Coin.SOL, Cluster.DEVNET, 1_000_000, olena) }
        assertEquals(Coin.SOL, ready(compose("1", null, sol)).draft.coin)
    }

    @Test
    fun `missing amount still opens a draft with the person`() {
        assertEquals(null, ready(compose(null, "usdc", listOf(SentBefore(Coin.USDC, Cluster.DEVNET, 1, olena)))).draft.units)
    }

    private fun usdc(vararg amounts: Long, to: SolanaKey = olena) = amounts.map { SentBefore(Coin.USDC, Cluster.DEVNET, it * 1_000_000, to) }

    private fun guard(units: Long?, history: List<SentBefore>, coin: Coin = Coin.USDC, cluster: Cluster = Cluster.DEVNET, source: AddressSource = AddressSource.CONVERSATION) =
        TransferGuard.check(olena, source, coin, cluster, units, history)

    @Test
    fun `first transfer to a person is noticed`() {
        assertEquals(listOf(GuardNote.FirstTime), guard(5_000_000, emptyList()))
        assertEquals(emptyList<GuardNote>(), guard(5_000_000, usdc(5)))
    }

    @Test
    fun `typed address is always noticed`() {
        assertEquals(listOf(GuardNote.Typed, GuardNote.FirstTime), guard(1, emptyList(), source = AddressSource.TYPED))
    }

    @Test
    fun `much more than usual is noticed only with history`() {
        val past = usdc(5, 4, 6)
        assertEquals(listOf(GuardNote.Unusual("6", Coin.USDC)), guard(50_000_000, past))
        assertEquals(emptyList<GuardNote>(), guard(20_000_000, past))
        assertEquals(emptyList<GuardNote>(), guard(50_000_000, usdc(5)))
        assertEquals(listOf(GuardNote.FirstTime), guard(50_000_000_000, emptyList()))
    }

    @Test
    fun `other coin and other network are noticed`() {
        assertEquals(listOf(GuardNote.OtherCoin(Coin.USDC, Coin.SOL)), guard(1_000, usdc(1, 2), coin = Coin.SOL))
        assertEquals(listOf(GuardNote.OtherNetwork(Cluster.DEVNET, Cluster.MAINNET)), guard(1_000_000, usdc(1, 2), cluster = Cluster.MAINNET))
    }

    @Test
    fun `euro said is EURC on both networks and the guard tells it from usual USDC`() {
        for (cluster in Cluster.entries) {
            val d = ready(compose("5", "eurc", cluster = cluster)).draft
            assertEquals(Coin.EURC, d.coin)
            assertTrue(d.coinSaid)
            assertEquals(5_000_000L, d.units)
        }
        assertTrue(compose("0.0000001", "eurc") is DraftOutcome.BadAmount)
        assertEquals(listOf(GuardNote.OtherCoin(Coin.USDC, Coin.EURC)), guard(1_000_000, usdc(1, 2), coin = Coin.EURC))
    }

    @Test
    fun `changed address is noticed`() {
        assertEquals(listOf(GuardNote.NewAddress), guard(1_000_000, usdc(1, to = other)))
    }

    @Test
    fun `draft carries guard notes and nothing signed`() {
        val r = ready(compose("50", "usdc", usdc(5, 4, 6)))
        assertEquals(listOf(GuardNote.Unusual("6", Coin.USDC)), r.notes)
        assertEquals(AddressSource.CONVERSATION, r.draft.source)
    }
}
