package local.phantom.companion.core.solana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PayLinkTest {

    private val addr = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    private val key = SolanaKey.of(addr)

    @Test
    fun `parses bare address and solana pay links`() {
        assertEquals(PayLink(key, null, null), PayLink.parse(addr, Cluster.DEVNET))
        assertEquals(PayLink(key, null, null), PayLink.parse("solana:$addr", Cluster.DEVNET))
        assertEquals(PayLink(key, Coin.SOL, "1.5"), PayLink.parse("solana:$addr?amount=1.5", Cluster.DEVNET))
        assertEquals(
            PayLink(key, Coin.USDC, "2", reference = key),
            PayLink.parse(
                "solana:$addr?amount=2&spl-token=4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU&reference=$addr&label=Cafe",
                Cluster.DEVNET,
            ),
        )
        assertNull(PayLink.parse("solana:$addr?spl-token=11111111111111111111111111111112", Cluster.DEVNET))
        assertEquals(PayLink(key, null, null), PayLink.parse("solana:$addr?amount=abc", Cluster.DEVNET))
        assertNull(PayLink.parse("hello", Cluster.DEVNET))
        assertNull(PayLink.parse("solana:https://x.example/tx", Cluster.DEVNET))
    }
}
