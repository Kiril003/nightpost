package local.phantom.companion.data

import org.junit.Assert.assertFalse
import org.junit.Test

class SolanaFlavorTest {
    @Test
    fun playBuildNeverOffersWalletLicencePurchase() {
        assertFalse(SolanaFlavor.LICENCE_PURCHASE)
        assertFalse(solanaConfigFlags().licencePurchase)
    }
}
