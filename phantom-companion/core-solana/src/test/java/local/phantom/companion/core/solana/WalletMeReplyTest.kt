package local.phantom.companion.core.solana

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalletMeReplyTest {

    private val me = SolanaKey.of(ByteArray(32) { 1 })!!
    private val olena = SolanaKey.of(ByteArray(32) { 5 })!!

    @Test
    fun `fresh balances are served as they are, not stale`() {
        val s = WalletState(address = me, balances = mapOf(Coin.USDC to 18_500_000L), lastBalances = LastBalances(mapOf(Coin.USDC to 18_500_000L), 1_000L))
        val o = WalletMeReply.of(s, emptyList())
        assertEquals(me.base58, o.getString("address"))
        assertEquals("devnet", o.getString("cluster"))
        assertEquals("built_in", o.getString("kind"))
        assertEquals(18_500_000L, o.getJSONObject("balances").getLong("usdc"))
        assertFalse(o.getBoolean("stale"))
        assertEquals(1_000L, o.getLong("balances_at"))
    }

    @Test
    fun `failed read serves the last known value with its time, never zero`() {
        val s = WalletState(address = me, balances = null, lastBalances = LastBalances(mapOf(Coin.USDC to 18_500_000L), 1_000L))
        val o = WalletMeReply.of(s, null)
        assertTrue(o.getBoolean("stale"))
        assertEquals(18_500_000L, o.getJSONObject("balances").getLong("usdc"))
        assertEquals(1_000L, o.getLong("balances_at"))
        assertTrue("історія не прочиталась — null, не порожньо", o.isNull("activity"))
    }

    @Test
    fun `nothing ever read is an honest null, not a zero balance`() {
        val o = WalletMeReply.of(WalletState(address = me), null)
        assertTrue(o.isNull("balances"))
        assertTrue(o.isNull("balances_at"))
        assertFalse(o.getBoolean("stale"))
        assertTrue(o.isNull("tokens"))
        assertTrue(o.isNull("reclaim"))
        assertTrue(WalletMeReply.of(WalletState(), null).isNull("address"))
    }

    @Test
    fun `reclaim is served only when there is something to get back`() {
        val some = WalletMeReply.of(WalletState(address = me, reclaim = Reclaim(2_039_280L, 1)), null)
        assertEquals(2_039_280L, some.getJSONObject("reclaim").getLong("lamports"))
        assertEquals(1, some.getJSONObject("reclaim").getInt("accounts"))
        assertTrue(WalletMeReply.of(WalletState(address = me, reclaim = Reclaim(0L, 1)), null).isNull("reclaim"))
    }

    @Test
    fun `activity names the person when their address is known`() {
        val lines = listOf(
            MeLine(Coin.USDC, ActivityLine("s1", 15_000_000L, 300L, olena), "p-olena", "Олена"),
            MeLine(Coin.SOL, ActivityLine("s2", -1L, null, null, failed = true), null, null),
        )
        val tokens = listOf(HeldToken(SolanaKey.of(ByteArray(32) { 9 })!!, 7L, 2))
        val o = WalletMeReply.of(WalletState(address = me, tokens = tokens), lines)
        val a = o.getJSONArray("activity")
        assertEquals(2, a.length())
        val first = a.getJSONObject(0)
        assertEquals("s1", first.getString("signature"))
        assertEquals("usdc", first.getString("coin"))
        assertEquals(15_000_000L, first.getLong("units"))
        assertEquals(300L, first.getLong("at"))
        assertEquals(olena.base58, first.getString("counterparty"))
        assertEquals("p-olena", first.getString("peer_id"))
        assertEquals("Олена", first.getString("name"))
        val second = a.getJSONObject(1)
        assertTrue(second.isNull("peer_id") && second.isNull("name") && second.isNull("at") && second.isNull("counterparty"))
        assertTrue(second.getBoolean("failed"))
        assertEquals(7L, o.getJSONArray("tokens").getJSONObject(0).getLong("units"))
    }
}
