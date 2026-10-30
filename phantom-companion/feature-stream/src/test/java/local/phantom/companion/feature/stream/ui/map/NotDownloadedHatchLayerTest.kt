package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.feature.stream.domain.OfflineCoverage.Area
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotDownloadedHatchLayerTest {

    private val view = Area(northLat = 50.48, eastLon = 30.55, southLat = 50.44, westLon = 30.48)

    @Test
    fun `a fully covered viewport paints no hatch`() {
        assertEquals(EMPTY_FC, notDownloadedHatchGeoJson(view, listOf(view)))
    }

    @Test
    fun `an uncovered viewport is one feature`() {
        val geo = notDownloadedHatchGeoJson(view, emptyList())
        assertTrue(geo.contains("\"type\":\"FeatureCollection\""))
        assertEquals(1, Regex("\"type\":\"Feature\"").findAll(geo).count())
        assertTrue(geo.contains("\"type\":\"Polygon\""))
    }

    @Test
    fun `a partially covered viewport produces one feature per uncovered strip`() {
        val corner = Area(northLat = 50.60, eastLon = 30.60, southLat = 50.46, westLon = 30.50)
        val geo = notDownloadedHatchGeoJson(view, listOf(corner))
        val expected = local.phantom.companion.feature.stream.domain.OfflineCoverage
            .uncoveredRects(view, listOf(corner)).size
        assertEquals(expected, Regex("\"type\":\"Feature\"").findAll(geo).count())
    }
}
