package local.phantom.companion.feature.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HIGHWAY — what the car screen says, tested without a car.
 *
 * The templates cannot be unit-tested (the host draws them), which is exactly
 * why every decision a driver actually reads was pulled out of them and into
 * [CarCopy].
 */
class CarCopyTest {

    @Test
    fun `distance is rounded the way a driver reads it`() {
        // Under a kilometre: metres, to the nearest ten. «347 м» and «350 м»
        // are the same turn.
        assertEquals("350 м", CarCopy.distance(347.0))
        assertEquals("0 м", CarCopy.distance(3.0))
        // Kilometres, one decimal, comma — this is Ukrainian, not en-US.
        assertEquals("1,2 км", CarCopy.distance(1_234.0))
        // Beyond 10 km the decimal is a lie: this is a straight line over an
        // OSM point, not a road distance.
        assertEquals("13 км", CarCopy.distance(12_847.0))
    }

    @Test
    fun `the subtitle leads with distance`() {
        val place = CarPlace(1, "ОККО", 50.45, 30.52, 1_234.0, subtitle = "fuel")
        val s = CarCopy.subtitle(place)
        assertTrue("distance must come first — it is the only field that decides", s.startsWith("1,2 км"))
        assertTrue(s.contains("fuel"))
    }

    @Test
    fun `a place with no category still reads cleanly`() {
        val place = CarPlace(1, "ОККО", 50.45, 30.52, 800.0, subtitle = null)
        assertEquals("800 м", CarCopy.subtitle(place))
    }

    @Test
    fun `the three empty states are three different instructions`() {
        // These are not stylistic variants. "I don't know where you are",
        // "I have never seen this road" and "there is genuinely none nearby"
        // tell the driver to do three different things, and collapsing them
        // into «нічого не знайдено» would be a lie two times out of three.
        val noFix = CarCopy.emptyMessage(gazetteerSize = 500, hadFix = false)
        val noCorpus = CarCopy.emptyMessage(gazetteerSize = 0, hadFix = true)
        val nothingNear = CarCopy.emptyMessage(gazetteerSize = 500, hadFix = true)

        assertTrue(noFix.contains("не знаю, де ти"))
        assertTrue("must admit it has never been here", noCorpus.contains("не бачив"))
        assertTrue(nothingNear.contains("немає"))

        assertEquals("all three must differ", 3, setOf(noFix, noCorpus, nothingNear).size)
    }

    @Test
    fun `a driver is never shown a list he cannot read`() {
        assertTrue("Auto caps rows, and so do we", CarCopy.MAX_ROWS <= 6)
    }

    @Test
    fun `every chip maps to real OSM subcategory values`() {
        // A typo here is invisible: the chip simply returns nothing, which on a
        // car screen is indistinguishable from "there is no fuel in this
        // county". These strings are the values PoiRepository actually writes
        // to poi_gazetteer.subcategory (the OSM tag value, e.g. amenity=fuel).
        assertTrue(CarCategory.FUEL.subcategories.contains("fuel"))
        assertTrue("EVs exist and they are also 'fuel' to a driver",
            CarCategory.FUEL.subcategories.contains("charging_station"))
        assertTrue(CarCategory.FOOD.subcategories.contains("cafe"))
        assertTrue(CarCategory.PHARMACY.subcategories.contains("pharmacy"))
        CarCategory.entries.forEach {
            assertTrue("${it.name} must offer at least one selector", it.subcategories.isNotEmpty())
            assertTrue("${it.name} needs a label a driver can read", it.label.isNotBlank())
        }
    }

    @Test
    fun `the chip radius is generous enough for a highway`() {
        // Between towns the nearest fuel is routinely 20+ km away. A 5 km
        // radius would answer "there is none", which is both false and the
        // worst possible false answer.
        assertTrue(CarCategory.RADIUS_METERS >= 25_000)
    }
}
