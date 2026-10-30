package local.phantom.companion.core.solana

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EscrowConfigTest {

    private val devUsdc = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    private val mainUsdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val mainProgram = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"

    private fun served(cluster: String, programId: String?, escrow: Boolean, mint: String) =
        """{"cluster":"$cluster","programId":${programId?.let { "\"$it\"" } ?: "null"},""" +
            """"usdcMint":"$mint","usdcDecimals":6,"escrow":$escrow}"""

    @Test
    fun `devnet with escrow on gives the program the server named`() {
        val c = EscrowConfig.parse(served("devnet", BillProgram.DEVNET_ID.base58, true, devUsdc))!!
        assertEquals(Cluster.DEVNET, c.cluster)
        assertEquals(BillProgram.DEVNET_ID, c.billProgram)
        assertTrue(c.escrowOn)
    }

    @Test
    fun `escrow false hides bill escrow even when an id comes along`() {
        val c = EscrowConfig.parse(served("devnet", BillProgram.DEVNET_ID.base58, false, devUsdc))!!
        assertNull(c.billProgram)
        assertFalse(c.escrowOn)
    }

    @Test
    fun `mainnet without a program id never falls back to the devnet one`() {
        val c = EscrowConfig.parse(served("mainnet-beta", null, true, mainUsdc))!!
        assertEquals(Cluster.MAINNET, c.cluster)
        assertNull(c.billProgram)
        assertFalse(c.escrowOn)
    }

    @Test
    fun `mainnet naming the devnet program is refused`() {
        val c = EscrowConfig.parse(served("mainnet-beta", BillProgram.DEVNET_ID.base58, true, mainUsdc))!!
        assertNull(c.billProgram)
    }

    @Test
    fun `mainnet with its own program id turns escrow on`() {
        val c = EscrowConfig.parse(served("mainnet-beta", mainProgram, true, mainUsdc))!!
        assertEquals(SolanaKey.of(mainProgram), c.billProgram)
        assertEquals(Coin.USDC.mint(Cluster.MAINNET), c.usdcMint)
    }

    @Test
    fun `a mint that is not the cluster's usdc makes the whole config unreadable`() {
        assertNull(EscrowConfig.parse(served("mainnet-beta", mainProgram, true, devUsdc)))
        assertNull(EscrowConfig.parse(served("devnet", BillProgram.DEVNET_ID.base58, true, mainUsdc)))
    }

    @Test
    fun `the program serves only its own network`() {
        val dev = EscrowConfig.parse(served("devnet", BillProgram.DEVNET_ID.base58, true, devUsdc))!!
        assertEquals(BillProgram.DEVNET_ID, dev.programFor(Cluster.DEVNET))
        assertNull(dev.programFor(Cluster.MAINNET))
    }

    @Test
    fun `usdc decimals other than the coin's make the whole config unreadable`() {
        val good = served("devnet", BillProgram.DEVNET_ID.base58, true, devUsdc)
        assertNull(EscrowConfig.parse(good.replace("\"usdcDecimals\":6", "\"usdcDecimals\":9")))
        assertNull(EscrowConfig.parse(good.replace("\"usdcDecimals\":6", "\"usdcDecimals\":0")))
    }

    @Test
    fun `an unknown cluster or broken body is unreadable`() {
        assertNull(EscrowConfig.parse(served("testnet", BillProgram.DEVNET_ID.base58, true, devUsdc)))
        assertNull(EscrowConfig.parse("not json"))
        assertNull(EscrowConfig.parse("""{"cluster":"devnet"}"""))
    }

    @Test
    fun `a program id that is not a key turns escrow off`() {
        val c = EscrowConfig.parse(served("devnet", "not-a-key", true, devUsdc))!!
        assertNull(c.billProgram)
    }

    @Test
    fun `the client reads the server config and goes dark on any failure`() = runBlocking {
        val seen = mutableListOf<String>()
        val ok = EscrowConfigClient("https://api.example/") { url, body ->
            seen += "$url ${body == null}"
            200 to served("devnet", BillProgram.DEVNET_ID.base58, true, devUsdc)
        }
        assertEquals(BillProgram.DEVNET_ID, ok.fetch()?.billProgram)
        assertEquals(listOf("https://api.example/api/solana/config true"), seen)

        assertNull(EscrowConfigClient("https://api.example") { _, _ -> 503 to "" }.fetch())
        assertNull(EscrowConfigClient("https://api.example") { _, _ -> error("offline") }.fetch())
        assertNull(EscrowConfigClient("") { _, _ -> error("never called") }.fetch())
    }
}
