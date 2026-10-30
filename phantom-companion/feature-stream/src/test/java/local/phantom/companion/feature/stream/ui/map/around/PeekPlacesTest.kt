package local.phantom.companion.feature.stream.ui.map.around

import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.feature.stream.ui.map.TilePlaceHarvest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeekPlacesTest {

    private val me = 49.8350 to 18.2920

    private fun poi(name: String, dLat: Double, hours: String? = null) = PoiEntity(
        id = name.hashCode().toLong(), lat = me.first + dLat, lon = me.second, name = name,
        category = "amenity", subcategory = "cafe",
        tags = listOfNotNull("amenity" to "cafe", hours?.let { "opening_hours" to it }).toMap(),
    )

    @Test
    fun `a chip shows its places nearest first with hours carried`() {
        val shown = peekPlaces(
            chipActive = true,
            chipHits = listOf(poi("Далеко", 0.01), poi("Близько", 0.001, "Mo-Su 08:00-20:00"), poi("Unknown", 0.0)),
            chipLoading = false,
            around = null,
            anchor = me,
        ) as PeekPlaces.Shown
        assertEquals(listOf("Близько", "Далеко"), shown.rows.map { it.place.name })
        assertEquals("Mo-Su 08:00-20:00", shown.rows.first().place.hours)
        assertEquals(2, shown.total)
        assertTrue(shown.rows.first().meters!! in 100.0..120.0)
    }

    @Test
    fun `a chip is never silent`() {
        assertEquals(PeekPlaces.Asking, peekPlaces(true, emptyList(), true, null, me))
        assertEquals(PeekPlaces.Nothing, peekPlaces(true, emptyList(), false, null, me))
    }

    @Test
    fun `around without a chip says what it knows`() {
        assertEquals(PeekPlaces.Asking, peekPlaces(false, listOf(poi("Пін", 0.0)), false, null, me))
        assertEquals(PeekPlaces.ZoomIn, peekPlaces(false, emptyList(), false, AroundPlaces.Unknown, me))
        assertEquals(PeekPlaces.Nothing, peekPlaces(false, emptyList(), false, AroundPlaces.Nothing, me))
        val shown = peekPlaces(false, emptyList(), false, AroundPlaces.Shown(listOf(AroundPlace("Кава", me.first, me.second)), false), me)
        assertTrue(shown is PeekPlaces.Shown)
    }

    @Test
    fun `tile harvest waits for street zoom and a real move`() {
        val h = TilePlaceHarvest()
        assertFalse(h.due(me.first, me.second, 12.0, 0))
        assertTrue(h.due(me.first, me.second, 15.0, 0))
        h.mark(me.first, me.second, 0)
        assertFalse(h.due(me.first + 0.001, me.second, 15.0, 1_000))
        assertTrue(h.due(me.first + 0.005, me.second, 15.0, 1_000))
        assertTrue(h.due(me.first, me.second, 15.0, TilePlaceHarvest.REPEAT_MS))
    }
}
