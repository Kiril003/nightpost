package local.phantom.companion.core.nav

import java.util.Locale
import local.phantom.companion.core.data.transit.AccessMode
import local.phantom.companion.core.data.transit.Itinerary
import local.phantom.companion.core.data.transit.raptor.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ManeuverShortLocaleTest {

    private val cyrillic = Regex("[\\u0400-\\u04FF]")
    private val en = Locale.ENGLISH
    private val uk = Locale.forLanguageTag("uk")

    private fun assertLatin(s: String) = assertFalse("Cyrillic in '$s'", cyrillic.containsMatchIn(s))

    @Test
    fun `English action and short phrase read as English`() {
        assertEquals("Turn left", maneuverAction("turn", "left", locale = en))
        assertEquals("Turn left onto Main St", maneuverShort("turn", "left", "Main St", null, en))
        assertEquals("Continue straight", maneuverShort("turn", "straight", null, null, en))
        assertEquals("Destination ahead", maneuverShort("arrive", null, null, null, en))
        assertEquals("Waypoint ahead", maneuverAction("arrive", null, locale = en, isIntermediate = true))
    }

    /** A Czech, German or Spanish phone has no maneuver phrasebook of its own: it reads English, never Ukrainian. */
    @Test
    fun `languages without a phrasebook read English, not Ukrainian`() {
        val pharmacy = StepHint(LandmarkKind.PHARMACY, null, LandmarkRelation.AFTER, soon = true)
        for (tag in listOf("cs", "de", "es", "pt-BR", "pl")) {
            val l = Locale.forLanguageTag(tag)
            assertEquals(tag, "Turn left onto Main St", maneuverShort("turn", "left", "Main St", null, l))
            assertEquals(tag, "Turn left", maneuverAction("turn", "left", locale = l))
            assertEquals(tag, "right after the pharmacy", landmarkLead(pharmacy, l))
            assertLatin(maneuverLines("roundabout", "right", "Ring Rd", 2, l).action)
        }
    }

    @Test
    fun `English phrasing uses adverbs, exits and on for arrival`() {
        assertEquals("Continue slightly left", maneuverActionEn("continue", "slight left"))
        assertEquals("Turn sharply right", maneuverActionEn("turn", "sharp right"))
        assertEquals("Keep slightly right", maneuverActionEn("fork", "slight right"))
        assertEquals("Make a U-turn", maneuverActionEn("continue", "uturn"))
        assertEquals("Take the 2nd exit onto Ring Rd", maneuverShortEn("roundabout", "right", "Ring Rd", 2))
        assertEquals("At the roundabout, turn right", maneuverActionEn("roundabout", "right"))
        assertEquals("Destination ahead on Main St", maneuverShortEn("arrive", null, "Main St"))
        assertEquals("Take the exit on the right", maneuverActionEn("off ramp", "slight right"))
        assertEquals("At the end of the road, turn left", maneuverActionEn("end of road", "left"))
    }

    @Test
    fun `English ordinals`() {
        val got = listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 101, 111).map(::exitOrdinalEn)
        assertEquals(
            listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "101st", "111th"),
            got,
        )
    }

    @Test
    fun `Ukrainian output is byte-identical`() {
        val cases = listOf(
            maneuverShort("turn", "left", "Хрещатик", null, uk) to "Поверніть ліворуч на Хрещатик",
            maneuverShort("turn", "straight", null, null, uk) to "Далі прямо",
            maneuverShort("roundabout", "right", null, 3, uk) to "На кільці — третій зʼїзд",
            maneuverShort("depart", null, null, null, uk) to "Рушаймо",
            maneuverShort("arrive", null, null, null, uk) to "Фініш попереду",
            maneuverAction("roundabout", "right", locale = uk) to "На кільці праворуч",
            maneuverAction("fork", "slight left", locale = uk) to "Тримайтесь плавно ліворуч",
            maneuverAction("end of road", "straight", locale = uk) to "У кінці дороги — прямо",
            maneuverAction("arrive", null, locale = uk, isIntermediate = true) to "Проміжна точка попереду",
        )
        cases.forEach { (got, want) -> assertEquals(want, got) }
    }

    private fun itinerary(): Itinerary {
        val walk = Itinerary.Leg.Access(
            mode = AccessMode.WALK,
            fromLat = 49.80, fromLon = 18.25, toLat = 49.802, toLon = 18.25,
            toStopName = "Náměstí", seconds = 150, meters = 222,
            points = emptyList(),
        )
        val ride = Itinerary.Leg.Ride(
            routeLabel = "4", mode = TransitMode.TRAM, colour = -1,
            fromStopName = "Náměstí", toStopName = "Hlavní nádraží",
            fromLat = 49.802, fromLon = 18.25, toLat = 49.818, toLon = 18.25,
            departSec = 50_000, arriveSec = 50_600, intermediateStops = 3,
            points = emptyList(), timesAreModelled = false,
            headsign = "Dubina", boardPlatform = "B",
            stops = listOf(Itinerary.Leg.RideStop("S1", 49.81, 18.25)),
        )
        val bus = ride.copy(
            routeLabel = "", mode = TransitMode.BUS, headsign = null, boardPlatform = null,
            fromStopName = "Hlavní nádraží", toStopName = "Dubina",
        )
        return Itinerary(listOf(walk, ride, bus), 49_850, 51_400, 0, false)
    }

    @Test
    fun `English transit steps carry no Ukrainian sentence`() {
        val steps = TransitSteps.of(itinerary())
        val shown = steps.map { maneuverShort(it.type, it.modifier, it.roadName, it.exitNumber, en) }
        shown.forEach(::assertLatin)
        assertEquals(
            listOf(
                "Walk to Náměstí",
                "Take tram 4 toward Dubina, platform B",
                "Get off at the next stop, Hlavní nádraží",
                "Get off at Hlavní nádraží",
                "Take the bus",
                "Get off at the next stop, Dubina",
                "Get off at Dubina",
                "Destination ahead",
            ),
            shown,
        )
    }

    @Test
    fun `Ukrainian transit steps stay the spoken sentence`() {
        val ride = itinerary().legs[1] as Itinerary.Leg.Ride
        assertEquals(
            "Сідайте на трамвай 4 у напрямку Dubina, платформа B",
            maneuverShort(TransitSteps.BOARD, null, TransitSteps.boardSentence(ride), null, uk),
        )
        assertEquals(
            "Take tram 4 toward Dubina, platform B",
            maneuverShort(TransitSteps.BOARD, null, TransitSteps.boardSentence(ride), null, en),
        )
        val train = ride.copy(routeLabel = "S2", mode = TransitMode.RAIL, headsign = null, boardPlatform = "3")
        assertEquals("Take train S2, track 3", maneuverShort(TransitSteps.BOARD, null, TransitSteps.boardSentence(train), null, en))
        val noLabel = ride.copy(routeLabel = "", boardPlatform = null)
        assertEquals("Take the tram toward Dubina", maneuverShort(TransitSteps.BOARD, null, TransitSteps.boardSentence(noLabel), null, en))
    }
}
