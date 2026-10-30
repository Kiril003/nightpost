package local.phantom.companion.core.solana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перемикач мережі в гаманці (збірка 16 на A52): підписом стояла ПОТОЧНА
 * мережа — «Тестова (devnet) — гроші несправжні», — а сам перемикач означав
 * «основна увімкнена». На devnet це читалось як «тестову вимкнено».
 *
 * Тепер підпис — одне незмінне питання «Справжні гроші (mainnet)», поточна
 * мережа — підрядком, а до справжніх грошей веде лише підтвердження.
 */
class NetworkSwitchTest {

    @Test
    fun `a fresh wallet is on devnet and the switch asks about real money`() {
        val fresh = WalletState().cluster
        assertEquals(Cluster.DEVNET, fresh)
        assertEquals(R.string.sol_network_real, NetworkSwitch.label(fresh))
        assertEquals(R.string.sol_network_real, NetworkSwitch.label(Cluster.MAINNET))
        assertFalse("devnet, а «справжні гроші» увімкнено", NetworkSwitch.checked(fresh))
        assertTrue(NetworkSwitch.checked(Cluster.MAINNET))
    }

    @Test
    fun `the current network is the subtitle`() {
        assertEquals(R.string.sol_network_devnet, NetworkSwitch.subtitle(Cluster.DEVNET))
        assertEquals(R.string.sol_network_mainnet, NetworkSwitch.subtitle(Cluster.MAINNET))
    }

    @Test
    fun `real money only after a confirmation, back to test money at once`() {
        assertEquals(NetworkSwitch.Step.ConfirmMainnet, NetworkSwitch.onToggle(wantMainnet = true))
        assertEquals(Cluster.MAINNET, NetworkSwitch.confirmed())
        assertEquals(NetworkSwitch.Step.Set(Cluster.DEVNET), NetworkSwitch.onToggle(wantMainnet = false))
    }
}
