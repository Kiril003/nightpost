package local.phantom.companion.core.solana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DraftToCashTest {

    private val olena = SolanaKey.of(ByteArray(32) { 1 })!!
    private val kyrylo = SolanaKey.of(ByteArray(32) { 3 })!!
    private val contacts = listOf(SolanaContact("p3", "Кирило", kyrylo), SolanaContact("p1", "Олена", olena))
    private val draft = PaymentDraft("p1", "Олена", olena, AddressSource.CONVERSATION, Coin.USDC, true, 5_000_000, Cluster.DEVNET)

    @Test
    fun `route is online, cash or an honest gap`() {
        val full = CashOnHand(Coin.USDC, 5_000_000, builtIn = true)
        assertEquals(DraftRoute.Online, DraftRoutes.choose(draft, online = true, hand = full))
        assertEquals(DraftRoute.Cash, DraftRoutes.choose(draft, online = false, hand = full))
        assertEquals(DraftRoute.Cash, DraftRoutes.choose(draft.copy(units = null), online = false, hand = full))
        assertEquals(DraftRoute.NoCash(CashGap.Short(4_000_000, Coin.USDC)), DraftRoutes.choose(draft, false, full.copy(freeUnits = 4_000_000)))
        assertEquals(DraftRoute.NoCash(CashGap.Empty), DraftRoutes.choose(draft, false, CashOnHand(null, 0, true)))
        assertEquals(DraftRoute.NoCash(CashGap.OtherCoin(Coin.SOL, Coin.USDC)), DraftRoutes.choose(draft, false, CashOnHand(Coin.SOL, 10, true)))
        assertEquals(DraftRoute.NoCash(CashGap.External), DraftRoutes.choose(draft, false, full.copy(builtIn = false)))
        assertEquals(DraftRoute.NoCash(CashGap.NoSol), DraftRoutes.choose(draft, false, full.copy(solCovers = false)))
        assertEquals(DraftRoute.NoCash(CashGap.NotAPerson), DraftRoutes.choose(draft.copy(peerId = null, source = AddressSource.TYPED), false, full))
    }

    @Test
    fun `cash draft opens the cash dialog for the same person, coin and amount`() {
        val extras = WalletActivity.draftExtras(draft, cash = true)
        val open = WalletActivity.Opened.read(extras::get)
        val (who, cash) = open.firstDialog(contacts)!!
        assertEquals("p1", who.id)
        assertEquals(true, cash)
        assertEquals(Coin.USDC to "5", open.fillFor(who, cash = true))
        assertNull(open.fillFor(who, cash = false))
        assertNull(open.fillFor(contacts[0], cash = true))
    }

    @Test
    fun `online draft still opens the transfer dialog`() {
        val open = WalletActivity.Opened.read(WalletActivity.draftExtras(draft, cash = false)::get)
        assertEquals(contacts[1] to false, open.firstDialog(contacts))
        assertEquals(Coin.USDC to "5", open.fillFor(contacts[1], cash = false))
    }
}
