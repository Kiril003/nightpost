package local.phantom.companion.packaging

import local.phantom.companion.Distribution
import local.phantom.companion.ui.settings.UpdateSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreDistributionTest {

    @Test
    fun `store build never updates itself`() {
        assertTrue(Distribution.isStore)
        assertFalse(Distribution.selfUpdate)
        assertFalse(Distribution.cabinetUpdates)
        assertTrue(Distribution.viaPlay)
        assertFalse(Distribution.viaDappStore)
    }

    @Test
    fun `store update row names Google Play`() {
        assertEquals(
            UpdateSection.ViaPlay,
            UpdateSection.of(
                developerBuild = false,
                announced = null,
                licensed = true,
                viaDappStore = Distribution.viaDappStore,
                viaPlay = Distribution.viaPlay,
            ),
        )
    }
}
