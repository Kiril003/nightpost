package local.phantom.companion.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateSectionPlayTest {

    @Test
    fun `play wins over developer build`() {
        assertEquals(
            UpdateSection.ViaPlay,
            UpdateSection.of(developerBuild = true, announced = null, licensed = true, viaPlay = true),
        )
    }

    @Test
    fun `dApp Store wins over play`() {
        assertEquals(
            UpdateSection.ViaDappStore,
            UpdateSection.of(developerBuild = false, announced = null, licensed = true, viaDappStore = true, viaPlay = true),
        )
    }

    @Test
    fun `default stays as today`() {
        assertEquals(
            UpdateSection.Newest,
            UpdateSection.of(developerBuild = false, announced = null, licensed = true),
        )
    }
}
