package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Правда, якої стільки, що крізь неї не видно міста»: дворові барʼєри
 * представляє hazard-шар з доречністю (HazardMarkerGate) — POI-конвеєр їх
 * не малює, інакше квартал вкривається тузином однакових «Ворота».
 */
class PoiBarrierDensityTest {

    @Test
    fun `courtyard barrier furniture never renders on the poi layer`() {
        POIOverlay.BARRIER_FURNITURE_NAMES.forEach { barred ->
            assertFalse("«$barred» мусив лишитись hazard-шару", POIOverlay.poiRenderable(barred))
        }
        assertFalse(POIOverlay.poiRenderable("  Ворота  "))
    }

    @Test
    fun `road stops keep their poi presence`() {
        assertTrue(POIOverlay.poiRenderable("Пункт пропуску"))
        assertTrue(POIOverlay.poiRenderable("Пункт оплати"))
        assertTrue(POIOverlay.poiRenderable("Камера швидкості"))
    }

    @Test
    fun `a NAMED gate is a place and stays`() {
        assertTrue(POIOverlay.poiRenderable("Брама Заборовського"))
    }

    @Test
    fun `placeholder rule survives the refactor`() {
        assertFalse(POIOverlay.poiRenderable(""))
        assertFalse(POIOverlay.poiRenderable("Unknown"))
        assertFalse(POIOverlay.poiRenderable("parking"))
        assertTrue(POIOverlay.poiRenderable("Аптека Доброго Дня"))
    }
}
