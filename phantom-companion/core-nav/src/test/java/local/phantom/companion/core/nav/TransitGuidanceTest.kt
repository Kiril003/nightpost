package local.phantom.companion.core.nav

import local.phantom.companion.core.data.transit.AccessMode
import local.phantom.companion.core.data.transit.Itinerary
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitGuidanceTest {

    private fun lat(i: Int) = 49.80 + i * 0.001
    private val lon = 18.25
    private val track = (0..20).map { lat(it) to lon }

    private fun itinerary(): Itinerary {
        val walk = Itinerary.Leg.Access(
            mode = AccessMode.WALK,
            fromLat = lat(0), fromLon = lon, toLat = lat(2), toLon = lon,
            toStopName = "Náměstí", seconds = 150, meters = 222,
            points = track.subList(0, 3),
        )
        val ride = Itinerary.Leg.Ride(
            routeLabel = "4", mode = TransitMode.TRAM, colour = -1,
            fromStopName = "Náměstí", toStopName = "Hlavní nádraží",
            fromLat = lat(2), fromLon = lon, toLat = lat(18), toLon = lon,
            departSec = 50_000, arriveSec = 50_600, intermediateStops = 3,
            points = track.subList(2, 19), timesAreModelled = false,
            headsign = "Dubina", boardPlatform = "B",
            stops = listOf(6, 10, 14).map { Itinerary.Leg.RideStop("S$it", lat(it), lon) },
        )
        val tail = Itinerary.Leg.Access(
            mode = AccessMode.WALK,
            fromLat = lat(18), fromLon = lon, toLat = lat(20), toLon = lon,
            toStopName = "", seconds = 120, meters = 222,
            points = track.subList(18, 21),
        )
        return Itinerary(listOf(walk, ride, tail), 49_850, 50_720, 0, false)
    }

    @Test
    fun `a transit plan carries the steps guidance speaks`() {
        val steps = TransitSteps.of(itinerary())
        assertEquals(
            listOf(TransitSteps.WALK, TransitSteps.BOARD, TransitSteps.ALIGHT_NEXT, TransitSteps.ALIGHT, "arrive"),
            steps.map { it.type },
        )
        assertEquals(
            "Сідайте на трамвай 4 у напрямку Dubina, платформа B. Їхати 4 зупинки до Hlavní nádraží.",
            steps[1].roadName,
        )
        assertEquals("Виходьте на наступній — Hlavní nádraží", steps[2].roadName)
        assertEquals("Виходьте: Hlavní nádraží. Далі пішки.", steps[3].roadName)
    }

    @Test
    fun `riding past the last stop before yours says get off at the next one, once`() {
        val variant = RouteVariant(
            points = track,
            distanceMeters = 2200.0,
            durationSeconds = 870.0,
            steps = TransitSteps.of(itinerary()),
            speedsMps = emptyList(),
            profile = RouteProfile.TRANSIT,
        )
        val session = GuidanceSession.of(variant) { null }!!
        val spoken = ArrayList<String>()
        var t = 1_000_000_000L
        for (i in 0..160) {
            val la = lat(0) + i * 0.000125
            val speed = if (la < lat(2)) 1.4f else 10f
            session.onFix(la, lon, speed, t).cues.forEach { spoken += it.text }
            t += 1_000
        }
        assertEquals(1, spoken.count { it == "Виходьте на наступній — Hlavní nádraží" })
        assertTrue(spoken.toString(), spoken.any { it.startsWith("Сідайте на трамвай 4") })
        assertTrue(spoken.toString(), spoken.none { it.startsWith("Через") && it.contains("Сідайте") })
    }

    @Test
    fun `the stop count is declined in Ukrainian`() {
        assertEquals("зупинку", TransitSteps.stopsWord(1))
        assertEquals("зупинки", TransitSteps.stopsWord(3))
        assertEquals("зупинок", TransitSteps.stopsWord(5))
        assertEquals("зупинок", TransitSteps.stopsWord(12))
        assertEquals("зупинку", TransitSteps.stopsWord(21))
    }
}
