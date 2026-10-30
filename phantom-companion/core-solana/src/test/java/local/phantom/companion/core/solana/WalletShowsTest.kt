package local.phantom.companion.core.solana

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletShowsTest {
    @Test
    fun `licence purchase stays off the wallet screen unless one is unfinished`() {
        assertFalse(WalletShows.licence(true, WalletState()))
        assertTrue(WalletShows.licence(true, WalletState(pendingPurchase = PendingPurchase("ref", "addr", Coin.USDC, 1))))
        assertFalse(WalletShows.licence(false, WalletState(pendingPurchase = PendingPurchase("ref", "addr", Coin.USDC, 1))))
    }

    @Test
    fun `seal from a chat or an unfinished purchase opens More at start`() {
        val plain = WalletActivity.Opened(peer = null, sealHash = null, sealLabel = "")
        assertFalse(WalletShows.moreAtStart(plain, WalletState()))
        assertTrue(WalletShows.moreAtStart(plain.copy(sealHash = "abc"), WalletState()))
        assertTrue(WalletShows.moreAtStart(plain.copy(sealPick = true), WalletState()))
        assertTrue(WalletShows.moreAtStart(plain, WalletState(pendingPurchase = PendingPurchase("ref", "addr", Coin.USDC, 1))))
    }
}
