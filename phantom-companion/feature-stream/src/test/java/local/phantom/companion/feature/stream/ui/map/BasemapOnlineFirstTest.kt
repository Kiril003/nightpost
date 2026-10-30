package local.phantom.companion.feature.stream.ui.map

import java.io.File
import local.phantom.companion.core.geo.basemap.OfflineBasemapInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BasemapOnlineFirstTest {

    private val cityPack = OfflineBasemapInfo(
        path = "/data/basemap/city.mbtiles",
        name = "city",
        west = 30.2, south = 50.2, east = 30.8, north = 50.6,
        minZoom = 0, maxZoom = 14, bytes = 1L,
    )

    @Test
    fun `online the whole world draws from the network, even with a city pack on the phone`() {
        assertNull(localBasemapFor(cityPack, online = true))
    }

    @Test
    fun `offline the pack carries the map`() {
        assertEquals(cityPack, localBasemapFor(cityPack, online = false))
        assertNull(localBasemapFor(null, online = false))
    }

    @Test
    fun `the style binding and the coverage banner both read the online-first choice`() {
        val canvas = File("src/main/java/local/phantom/companion/feature/stream/ui/map/MapCanvas.kt").readText()
        assertTrue(
            "MapCanvas must pick the local pack through localBasemapFor, or a city pack greys out every other city online",
            Regex("""localBasemapFor\(offlineBasemap,\s*online""").containsMatchIn(canvas),
        )
        assertTrue(
            "no direct offlineBasemap read may reach applyOfflineBasemap",
            !Regex("""val basemap = offlineBasemap\b""").containsMatchIn(canvas),
        )
    }
}
