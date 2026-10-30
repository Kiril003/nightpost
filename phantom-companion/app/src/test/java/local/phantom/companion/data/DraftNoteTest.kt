package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.solana.AddressSource
import local.phantom.companion.core.solana.Cluster
import local.phantom.companion.core.solana.Coin
import local.phantom.companion.core.solana.PaymentDraft
import local.phantom.companion.core.solana.PaymentFrame
import local.phantom.companion.core.solana.SolanaKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftNoteTest {

    private val danielKey = SolanaKey.of(ByteArray(32) { 4 })!!
    private val sig = "5" + "1".repeat(86)
    private val draft = PaymentDraft("daniel", "Daniel", danielKey, AddressSource.CONVERSATION, Coin.USDC, true, 5_000_000, Cluster.DEVNET)

    private fun paid(units: Long, at: Long, coin: Coin = Coin.USDC) = PeerMessage(
        id = "p$at", fromId = "me", toId = "daniel",
        body = PaymentFrame(Cluster.DEVNET, coin, units, danielKey, sig).encode(),
        sentAtMs = at, state = PeerMessage.State.DELIVERED,
    )

    @Test
    fun `the note survives a round trip through storage`() {
        val n = DraftNote.of(draft, 1_000)!!
        assertEquals(n, DraftNote.decode(n.encode()))
        assertNull(DraftNote.decode("garbage"))
        assertNull(DraftNote.of(draft.copy(peerId = null), 1_000))
    }

    @Test
    fun `sent only when my payment to that person in that coin and amount came after the draft`() {
        val n = DraftNote.of(draft, 1_000)!!
        assertFalse(n.sentIn(emptyList(), "me"))
        assertFalse(n.sentIn(listOf(paid(5_000_000, 500)), "me"))
        assertFalse(n.sentIn(listOf(paid(4_000_000, 2_000)), "me"))
        assertFalse(n.sentIn(listOf(paid(5_000_000, 2_000, Coin.EURC)), "me"))
        assertTrue(n.sentIn(listOf(paid(5_000_000, 2_000)), "me"))
    }
}
