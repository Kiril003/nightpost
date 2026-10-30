package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePoiIconTest {
    @Test
    fun shopsCafesPharmaciesGetTheirOwnSign() {
        val ids = listOf("cafe", "pharmacy", "supermarket", "restaurant", "bakery").map(POIOverlay::tileImageId)
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertNotEquals("poi-default", it) }
    }

    @Test
    fun unknownSubclassFallsBackToDefaultSign() {
        assertEquals("poi-default", POIOverlay.tileImageId("zzz"))
        assertEquals("poi-default", POIOverlay.tileImageId(null))
    }

    @Test
    fun furnitureClassesStayOffTheMap() {
        assertTrue("parking" in POIOverlay.TILE_SILENT_CLASSES)
    }
}
