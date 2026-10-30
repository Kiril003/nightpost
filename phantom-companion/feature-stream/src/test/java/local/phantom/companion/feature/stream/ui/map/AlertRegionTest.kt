package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRegionTest {

    private val ua by lazy { OblastShapes.of(File("src/main/assets/country/ua/oblasts.geojson").readText()) }
    private val noRegions = OblastShapes.of("{\"type\":\"FeatureCollection\",\"features\":[]}")

    private val prague = 50.087 to 14.421
    private val krakow = 50.061 to 19.937
    private val kyiv = 50.450 to 30.523
    private val lviv = 49.839 to 24.029

    private fun src(name: String) = File("src/main/java/local/phantom/companion/feature/stream/ui/map/$name").readText()

    @Test
    fun `nothing alert-like outside ukraine`() {
        assertTrue(ua.describesUkraine)
        assertFalse(AlertRegion.here(ua, me = prague, view = prague))
        assertFalse(AlertRegion.here(ua, me = null, view = krakow))
        assertFalse(AlertRegion.here(ua, me = null, view = null))
    }

    @Test
    fun `alerts when the person or the map is in ukraine`() {
        assertTrue(AlertRegion.here(ua, me = kyiv, view = prague))
        assertTrue(AlertRegion.here(ua, me = null, view = lviv))
    }

    @Test
    fun `a pack without ukrainian regions never shows alerts`() {
        assertFalse(AlertRegion.here(noRegions, me = kyiv, view = kyiv))
    }

    @Test
    fun `outside ukraine the verdict is no feed, so the pill stays silent`() {
        SkyVerdict.values().forEach {
            assertEquals(SkyVerdict.NO_COUNTRY_FEED, it.onlyIf(false))
            assertEquals(it, it.onlyIf(true))
        }
    }

    @Test
    fun `the map, the pill and the layer chip all read the same gate`() {
        val screen = src("MapScreen.kt")
        assertTrue(screen.contains("ui.alertStates = if (alertsHere) alertStates else emptyMap()"))
        assertTrue(screen.contains(".onlyIf(alertsHere)"))
        assertTrue(screen.contains("alertsHere = alertsHere"))
        val chrome = src("MapChrome.kt")
        assertTrue(chrome.contains(".onlyIf(alertsHere)"))
        assertTrue(chrome.contains("alertsOffered = alertsHere"))
        assertTrue(chrome.contains("(ui.alertsVisible && alertsHere)"))
        assertTrue(src("MapToolsCapsule.kt").contains("if (alertsOffered) add("))
    }
}
