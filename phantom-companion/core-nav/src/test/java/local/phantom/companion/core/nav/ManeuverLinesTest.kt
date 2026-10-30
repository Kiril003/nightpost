package local.phantom.companion.core.nav

import java.util.Locale
import local.phantom.companion.core.data.transit.AccessMode
import local.phantom.companion.core.data.transit.Itinerary
import local.phantom.companion.core.data.transit.raptor.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ManeuverLinesTest {

    private val en = Locale.ENGLISH
    private val uk = Locale.forLanguageTag("uk")

    private fun ride(label: String, mode: TransitMode, to: String) = Itinerary.Leg.Ride(
        routeLabel = label, mode = mode, colour = -1,
        fromStopName = "Náměstí", toStopName = to,
        fromLat = 49.802, fromLon = 18.25, toLat = 49.818, toLon = 18.25,
        departSec = 50_000, arriveSec = 50_600, intermediateStops = 0,
        points = emptyList(), timesAreModelled = false,
        headsign = null, boardPlatform = null,
        stops = listOf(Itinerary.Leg.RideStop("S1", 49.81, 18.25)),
    )

    private fun steps() = TransitSteps.of(
        Itinerary(
            listOf(
                Itinerary.Leg.Access(
                    mode = AccessMode.WALK,
                    fromLat = 49.80, fromLon = 18.25, toLat = 49.802, toLon = 18.25,
                    toStopName = "Náměstí", seconds = 150, meters = 222, points = emptyList(),
                ),
                ride("4", TransitMode.TRAM, "Hlavní nádraží"),
                ride("", TransitMode.BUS, "Dubina"),
                Itinerary.Leg.Access(
                    mode = AccessMode.WALK,
                    fromLat = 49.818, fromLon = 18.25, toLat = 49.82, toLon = 18.25,
                    toStopName = "", seconds = 60, meters = 80, points = emptyList(),
                ),
            ),
            49_850, 51_400, 0, false,
        ),
    )

    private fun lines(locale: Locale) = steps().map { maneuverLines(it.type, it.modifier, it.roadName, it.exitNumber, locale) }

    @Test
    fun `a transit step splits into the instruction and the rest of the ride, in English`() {
        assertEquals(
            listOf(
                ManeuverLines("Walk to Náměstí", null),
                ManeuverLines("Take tram 4", "Ride 1 stop to Hlavní nádraží"),
                ManeuverLines("Get off at the next stop, Hlavní nádraží", null),
                ManeuverLines("Get off at Hlavní nádraží", "Then change to the bus"),
                ManeuverLines("Take the bus", "Ride 1 stop to Dubina"),
                ManeuverLines("Get off at the next stop, Dubina", null),
                ManeuverLines("Get off at Dubina", "Then walk"),
                ManeuverLines("Destination ahead", null),
            ),
            lines(en),
        )
    }

    @Test
    fun `in Ukrainian the two lines are the spoken sentence, nothing lost`() {
        assertEquals(
            listOf(
                ManeuverLines("Ідіть до зупинки Náměstí", null),
                ManeuverLines("Сідайте на трамвай 4", "Їхати 1 зупинку до Hlavní nádraží"),
                ManeuverLines("Виходьте на наступній — Hlavní nádraží", null),
                ManeuverLines("Виходьте: Hlavní nádraží", "Далі пересадка: автобус"),
                ManeuverLines("Сідайте на автобус", "Їхати 1 зупинку до Dubina"),
                ManeuverLines("Виходьте на наступній — Dubina", null),
                ManeuverLines("Виходьте: Dubina", "Далі пішки"),
                ManeuverLines("Фініш попереду", null),
            ),
            lines(uk),
        )
    }

    @Test
    fun `a road step keeps its action and street`() {
        assertEquals(ManeuverLines("Turn left", "Main St"), maneuverLines("turn", "left", "Main St", null, en))
        assertEquals(ManeuverLines("Поверніть ліворуч", "Хрещатик"), maneuverLines("turn", "left", "Хрещатик", null, uk))
        assertEquals(ManeuverLines("Take the 2nd exit", null), maneuverLines("roundabout", "right", " ", 2, en))
    }

    @Test
    fun `the landmark line follows the app language, speech keeps Ukrainian`() {
        val pharmacy = StepHint(LandmarkKind.PHARMACY, null, LandmarkRelation.AFTER, soon = true)
        assertEquals("right after the pharmacy", landmarkLead(pharmacy, en))
        assertEquals("одразу за аптекою", landmarkLead(pharmacy, uk))
        assertEquals("after ATB", landmarkLead(StepHint(LandmarkKind.SUPERMARKET, "ATB", LandmarkRelation.AFTER), en))
        assertEquals("at the Rynok stop", landmarkLead(StepHint(LandmarkKind.STOP, "Rynok", LandmarkRelation.AT), en))
        assertEquals("over the bridge", landmarkLead(StepHint(LandmarkKind.BRIDGE, null, LandmarkRelation.THROUGH), en))
        assertEquals("through the arch", landmarkLead(StepHint(LandmarkKind.ARCH, null, LandmarkRelation.THROUGH), en))
        assertEquals("toward the church", landmarkLead(StepHint(LandmarkKind.CHURCH, null, LandmarkRelation.TOWARD), en))
    }
}
