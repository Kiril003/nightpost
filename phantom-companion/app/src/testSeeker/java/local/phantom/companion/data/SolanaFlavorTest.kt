package local.phantom.companion.data

import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaFlavorTest {
    @Test
    fun seekerBuildSellsTheLicenceForAWalletTransfer() {
        assertTrue(SolanaFlavor.LICENCE_PURCHASE)
        assertTrue(solanaConfigFlags().licencePurchase)
    }
}
