package local.phantom.companion.core.solana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannedAddressGuardTest {

    private val key = SolanaKey.of("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU")

    @Test
    fun `scanned extras mark source as SCANNED and prefill dialog without assistant kicker`() {
        val extras = WalletActivity.scannedExtras(PayLink(key, Coin.USDC, "2"))
        val open = WalletActivity.Opened.read(extras::get)
        val contact = SolanaContact("", key.short(), key)

        assertEquals(AddressSource.SCANNED, open.sourceFor(contact))
        assertFalse(open.byAssistant)
        assertEquals(key, open.firstDialog(emptyList())?.first?.address)
        assertEquals(Coin.USDC to "2", open.fillFor(contact, false))
    }

    @Test
    fun `transfer guard reports Scanned note and not Typed`() {
        val notes = TransferGuard.check(key, AddressSource.SCANNED, Coin.USDC, Cluster.DEVNET, 2_000_000, emptyList())
        assertTrue(GuardNote.Scanned in notes)
        assertFalse(GuardNote.Typed in notes)
    }
}
