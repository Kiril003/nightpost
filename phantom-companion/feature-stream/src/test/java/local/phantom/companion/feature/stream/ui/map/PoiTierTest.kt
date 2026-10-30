package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.data.poi.PoiEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ярус і довжина підпису місця — те, від чого залежить, чи мапа на 500 м є
 * мапою, а не стовпцем тексту (кадр A52 06.09: «Вхід», «Z-Box», вивіска
 * дантиста на чотири рядки — усе одним кеглем без жодного знака).
 */
class PoiTierTest {

    private fun poi(
        category: String,
        subcategory: String?,
        tags: Map<String, String> = emptyMap(),
        name: String = "x",
    ) = PoiEntity(id = 1, lat = 50.0, lon = 30.0, name = name, category = category, subcategory = subcategory, tags = tags)

    @Test
    fun `аптека критична, поштомат опорний, крамниця звичайна`() {
        assertEquals(POIOverlay.TIER_CRITICAL, POIOverlay.tierOf(poi("amenity", "pharmacy")))
        assertEquals(POIOverlay.TIER_SUSTAIN, POIOverlay.tierOf(poi("amenity", "parcel_locker")))
        assertEquals(POIOverlay.TIER_ORDINARY, POIOverlay.tierOf(poi("shop", "clothes")))
    }

    @Test
    fun `вхід у будинок — меблі, вхід у метро — місце`() {
        assertEquals(POIOverlay.TIER_FURNITURE, POIOverlay.tierOf(poi("other", null, mapOf("entrance" to "main"))))
        assertEquals(POIOverlay.TIER_FURNITURE, POIOverlay.tierOf(poi("other", null, mapOf("entrance" to "yes", "wheelchair" to "yes"))))
        assertEquals(
            POIOverlay.TIER_SUSTAIN,
            POIOverlay.tierOf(poi("railway", "subway_entrance", mapOf("entrance" to "yes", "railway" to "subway_entrance"))),
        )
    }

    @Test
    fun `довга вивіска ріжеться по слову з трикрапкою`() {
        val long = "Szymon Frydrych. Specjalista stomatologii zachowawczej i endodoncji."
        assertEquals("Szymon Frydrych. Specjalista…", POIOverlay.clipLabel(long))
        assertTrue(POIOverlay.clipLabel(long).length <= POIOverlay.LABEL_MAX_CHARS + 1)
    }

    @Test
    fun `коротка вивіска не чіпається`() {
        assertEquals("Kavárna Jine Kafe", POIOverlay.clipLabel("Kavárna Jine Kafe"))
    }

    @Test
    fun `слово без пробілів ріжеться твердо`() {
        val word = "a".repeat(POIOverlay.LABEL_MAX_CHARS + 10)
        assertEquals("a".repeat(POIOverlay.LABEL_MAX_CHARS) + "…", POIOverlay.clipLabel(word))
    }

    @Test
    fun `план підписів ріже так само, як вивіска`() {
        val long = "Szymon Frydrych. Specjalista stomatologii zachowawczej i endodoncji."
        val plan = POIOverlay.labelPlan(listOf(poi("amenity", "dentist", name = long)), latin = false)
        assertEquals("Szymon Frydrych. Specjalista…", plan.single().second)
    }

    @Test
    fun `склеєна кома в назві зупинки дістає пробіл`() {
        val plan = POIOverlay.labelPlan(listOf(poi("highway", "bus_stop", name = "Poruba,Otakara Jeremiáše")), latin = false)
        assertEquals("Poruba, Otakara Jeremiáše", plan.single().second)
    }

    @Test
    fun `усі шари з дисками — в одному переліку, і критичний серед них`() {
        assertTrue(POIOverlay.POI_LAYER_ID in POIOverlay.PIN_LAYER_IDS)
        assertTrue(POIOverlay.POI_MINOR_LAYER_ID in POIOverlay.PIN_LAYER_IDS)
        assertTrue(POIOverlay.POI_FURNITURE_LAYER_ID in POIOverlay.PIN_LAYER_IDS)
    }
}
