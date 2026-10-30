package local.phantom.companion.core.ai.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapViewportContextFormatterEnTest {

    @Test
    fun `english false stays byte-identical to default`() {
        val snap = MapViewportSnapshot(
            centerLat = 50.4501,
            centerLon = 30.5234,
            zoom = 14.0,
            locality = "Khreshchatyk",
            visiblePoiCount = 12,
            activeLayerTitles = listOf("cafes", "cameras"),
            followingOperator = true,
            selectedPointLabel = "Pin A",
            activeRoute = "4.2 km",
            activeNearbyCategory = "pharmacy",
            activeThreatBriefOblast = "Kyiv",
            navigation = NavigationSnapshot(
                stepPhrase = "turn left",
                distanceToManeuverM = 120,
                remainingDistanceM = 850,
                remainingDurationS = 360,
                speedKmh = 42,
                limitKmh = 50,
                offRoute = true,
            ),
        )
        assertEquals(
            MapViewportContextFormatter.format(snap),
            MapViewportContextFormatter.format(snap, english = false),
        )
        assertNull(MapViewportContextFormatter.format(null, english = true))
    }

    @Test
    fun `english true emits no cyrillic for latin snapshot`() {
        val snap = MapViewportSnapshot(
            centerLat = 48.2082,
            centerLon = 16.3738,
            zoom = 13.0,
            locality = "Innere Stadt",
            visiblePoiCount = 7,
            activeLayerTitles = listOf("transit", "cafes"),
            followingOperator = true,
            selectedPointLabel = "Stephansplatz",
            activeRoute = "2.1 km · 25 min",
            activeNearbyCategory = "coffee",
            activeThreatBriefOblast = "Wien",
            navigation = NavigationSnapshot(
                stepPhrase = "turn right onto Graben",
                distanceToManeuverM = 80,
                remainingDistanceM = 600,
                remainingDurationS = 300,
                speedKmh = 30,
                limitKmh = 50,
                offRoute = true,
            ),
        )
        val out = MapViewportContextFormatter.format(snap, english = true)!!
        assertFalse(out, out.any { it in '\u0400'..'\u04FF' })
        assertTrue(out.startsWith("[MAP_VIEW: center 48.2082,16.3738 z13"))
        assertTrue("Innere Stadt" in out)
        assertTrue("POI 7" in out)
        assertTrue("layers: transit, cafes" in out)
        assertTrue("following" in out)
        assertTrue("selected point: Stephansplatz" in out)
        assertTrue("route: 2.1 km · 25 min" in out)
        assertTrue("nearby: coffee" in out)
        assertTrue("alert: Wien" in out)
        assertTrue("NAVIGATION LIVE: turn right onto Graben in 80 m · 600 m left · 5 min · speed 30 km/h (limit 50) · OFF ROUTE" in out)
    }
}
