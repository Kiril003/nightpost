package local.phantom.companion.core.solana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferGuardViewTest {

    private val olena = SolanaKey.of(ByteArray(32) { 1 })!!
    private fun past(n: Int) = List(n) { SentBefore(Coin.USDC, Cluster.DEVNET, 1_000_000, olena) }

    @Test
    fun `поки журнал не прочитано, сторож не каже «перший переказ»`() {
        assertNull(TransferGuard.view(olena, AddressSource.CONVERSATION, Coin.USDC, Cluster.DEVNET, 5_000_000, null))
    }

    @Test
    fun `адреса з розмови й скільки разів уже платили — з журналу`() {
        val v = TransferGuard.view(olena, AddressSource.CONVERSATION, Coin.USDC, Cluster.DEVNET, 5_000_000, past(3))!!
        assertEquals(TransferGuard.View(fromChat = true, paidBefore = 3, notes = emptyList()), v)
        val first = TransferGuard.view(olena, AddressSource.CONVERSATION, Coin.USDC, Cluster.DEVNET, 5_000_000, emptyList())!!
        assertEquals(listOf(GuardNote.FirstTime), first.notes)
        assertEquals(0, first.paidBefore)
    }

    @Test
    fun `вписана вручну адреса не «з розмови»`() {
        val v = TransferGuard.view(olena, AddressSource.TYPED, Coin.USDC, Cluster.DEVNET, 1, emptyList())!!
        assertEquals(false, v.fromChat)
        assertEquals(listOf(GuardNote.Typed, GuardNote.FirstTime), v.notes)
    }
}
