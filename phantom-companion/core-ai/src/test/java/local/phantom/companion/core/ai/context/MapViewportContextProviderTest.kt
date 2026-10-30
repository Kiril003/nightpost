package local.phantom.companion.core.ai.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R4.2 — the shared-gaze formatter. */
class MapViewportContextProviderTest {

    @Test
    fun `null snapshot drops the line`() {
        assertNull(MapViewportContextFormatter.format(null))
    }

    @Test
    fun `full snapshot renders centre locality poi and layers`() {
        val out = MapViewportContextFormatter.format(
            MapViewportSnapshot(
                centerLat = 50.4501,
                centerLon = 30.5234,
                zoom = 14.0,
                locality = "Хрещатик",
                visiblePoiCount = 12,
                activeLayerTitles = listOf("3 кафе", "камери"),
                followingOperator = true,
            ),
        )!!
        assertTrue(out.startsWith("[MAP_VIEW: центр 50.4501,30.5234 z14"))
        assertTrue(out.contains("Хрещатик"))
        assertTrue(out.contains("POI 12"))
        assertTrue(out.contains("шари: 3 кафе, камери"))
        assertTrue(out.contains("стеження"))
    }

    @Test
    fun `sparse snapshot omits absent fields (Law 1)`() {
        val out = MapViewportContextFormatter.format(
            MapViewportSnapshot(centerLat = 48.9, centerLon = 24.7, zoom = 11.0),
        )!!
        assertEquals("[MAP_VIEW: центр 48.9000,24.7000 z11]", out)
    }

    @Test
    fun `layer list is capped at four`() {
        val out = MapViewportContextFormatter.format(
            MapViewportSnapshot(
                centerLat = 0.0, centerLon = 0.0, zoom = 5.0,
                activeLayerTitles = listOf("a", "b", "c", "d", "e", "f"),
            ),
        )!!
        assertTrue(out.contains("шари: a, b, c, d"))
        assertTrue(!out.contains("e"))
    }
}
