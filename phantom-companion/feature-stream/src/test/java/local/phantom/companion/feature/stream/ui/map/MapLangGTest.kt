package local.phantom.companion.feature.stream.ui.map

import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.sensor.nav.RoadGraph
import local.phantom.companion.core.sensor.nav.RoadWay
import local.phantom.companion.feature.stream.ui.map.nav.GenerativeOverlays
import local.phantom.companion.feature.stream.ui.map.nav.distanceParts
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapLangGTest {

    @Before
    fun bind() {
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    @Test
    @Config(qualifiers = "en")
    fun englishLabels() {
        assertEquals("now" to "", distanceParts(0.0))
        assertEquals("2.5 km", fmtDistance(2500.0))
        val now = System.currentTimeMillis()
        assertEquals("just now", AlertState(active = true, changedEpochMs = now - 10_000L).sinceText)
        val graph = RoadGraph(listOf(RoadWay(1L, listOf(50.45 to 30.52, 50.4505 to 30.5205))))
        assertEquals("Walk reach", GenerativeOverlays.shelterIsochrone(graph, 50.45, 30.52)?.title)
    }

    @Test
    @Config(qualifiers = "uk")
    fun ukrainianLabels() {
        assertEquals("зараз" to "", distanceParts(0.0))
        assertEquals("2.5 км".replace('.', ',').let { fmtDistance(2500.0) }, fmtDistance(2500.0))
        assertEquals("250" to "м", distanceParts(248.0))
        val now = System.currentTimeMillis()
        assertEquals("щойно", AlertState(active = true, changedEpochMs = now - 10_000L).sinceText)
        val graph = RoadGraph(listOf(RoadWay(1L, listOf(50.45 to 30.52, 50.4505 to 30.5205))))
        assertEquals("Встигаю дійти", GenerativeOverlays.shelterIsochrone(graph, 50.45, 30.52)?.title)
    }
}
