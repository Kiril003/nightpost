package local.phantom.companion.feature.stream.ui.map.dossier

import java.io.File
import local.phantom.companion.core.net.map.GeoEndpointsConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepartureBoardOptInTest {

    private val src = File("src/main/java/local/phantom/companion/feature/stream/ui/map/dossier/DepartureBoardSection.kt").readText()

    @Test
    fun `a tapped stop asks Transitous only after the world transit opt-in`() {
        assertFalse(GeoEndpointsConfig.DISABLED.worldTransitOptIn)
        assertTrue("відкликана згода гасить уже показане табло", src.contains("var state by remember(lat, lon, worldTransitAllowed)"))
        assertTrue(src.contains("worldTransitAllowed: Boolean = local.phantom.companion.core.net.map.GeoEndpoints.config.worldTransitOptIn"))
        val effect = src.substringAfter("LaunchedEffect(lat, lon, worldTransitAllowed) {")
        val gate = effect.indexOf("if (!worldTransitAllowed) return@LaunchedEffect")
        val call = effect.indexOf("client.departureBoard(")
        assertTrue("табло ходить у мережу до перевірки згоди", gate in 0 until call)
    }
}
