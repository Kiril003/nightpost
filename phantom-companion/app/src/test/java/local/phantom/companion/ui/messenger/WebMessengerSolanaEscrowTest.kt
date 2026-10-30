package local.phantom.companion.ui.messenger

import local.phantom.companion.core.solana.BillProgram
import local.phantom.companion.core.solana.BillStatus
import local.phantom.companion.core.solana.BillView
import local.phantom.companion.core.solana.Cluster
import local.phantom.companion.core.solana.EscrowConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebMessengerSolanaEscrowTest {

    private val devnet = EscrowConfig.parse(
        """{"cluster":"devnet","programId":"${BillProgram.DEVNET_ID.base58}","usdcMint":"4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU","usdcDecimals":6,"escrow":true}""",
    )!!

    @Test
    fun `escrow is on only for the network the wallet is on`() {
        val same = WebMessengerSolana.escrowReply(devnet, Cluster.DEVNET)
        assertTrue(same.getBoolean("on"))
        assertEquals(BillProgram.DEVNET_ID.base58, same.getString("program"))
        assertEquals("devnet", same.getString("cluster"))

        val other = WebMessengerSolana.escrowReply(devnet, Cluster.MAINNET)
        assertFalse(other.getBoolean("on"))
        assertTrue(other.isNull("program"))
    }

    @Test
    fun `no server answer means no escrow`() {
        val o = WebMessengerSolana.escrowReply(null, Cluster.DEVNET)
        assertFalse(o.getBoolean("on"))
        assertTrue(o.isNull("program"))
    }

    @Test
    fun `bill status comes only from the chain`() {
        val o = WebMessengerSolana.billReply(BillView(BillStatus.HELD, 2, 3, 900))
        assertEquals("held", o.getString("status"))
        assertEquals(2, o.getInt("paid"))
        assertEquals(3, o.getInt("total"))
        assertEquals(900L, o.getLong("deadline"))
    }

    @Test
    fun `unread bill has no status`() {
        val o = WebMessengerSolana.billReply(null)
        assertTrue(o.isNull("status"))
        assertTrue(o.isNull("deadline"))
        assertEquals(0, o.getInt("total"))
    }
}
