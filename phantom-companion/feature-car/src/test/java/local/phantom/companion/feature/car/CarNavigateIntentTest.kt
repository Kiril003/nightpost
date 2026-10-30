package local.phantom.companion.feature.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host's «navigate to X». Getting this wrong is not a blank screen — it is
 * driving somewhere the operator did not ask for, so the shapes that look
 * alike are separated here on purpose.
 */
class CarNavigateIntentTest {

    @Test
    fun `a plain coordinate is a point`() {
        val t = CarNavigateIntent.parse("geo:50.4501,30.5234")
        assertTrue(t is CarNavigateIntent.Target.Point)
        t as CarNavigateIntent.Target.Point
        assertEquals(50.4501, t.lat, 1e-6)
        assertEquals(30.5234, t.lon, 1e-6)
        assertNull(t.label)
    }

    @Test
    fun `the label in parentheses is the destination name`() {
        val t = CarNavigateIntent.parse("geo:50.45,30.52?q=50.45,30.52(%D0%A1%D0%BE%D1%84%D1%96%D1%8F)")
        t as CarNavigateIntent.Target.Point
        assertEquals("Софія", t.label)
    }

    @Test
    fun `geo zero zero with words is a search, not a drive to the Atlantic`() {
        // The Null Island trap: 0,0 is a real coordinate and never the answer.
        val t = CarNavigateIntent.parse("geo:0,0?q=%D0%B7%D0%B0%D0%BF%D1%80%D0%B0%D0%B2%D0%BA%D0%B0")
        assertTrue(t is CarNavigateIntent.Target.Query)
        assertEquals("заправка", (t as CarNavigateIntent.Target.Query).text)
    }

    @Test
    fun `coordinates carried only in q are still coordinates`() {
        val t = CarNavigateIntent.parse("geo:0,0?q=49.8397,24.0297")
        t as CarNavigateIntent.Target.Point
        assertEquals(49.8397, t.lat, 1e-6)
    }

    @Test
    fun `an address in q is searched`() {
        val t = CarNavigateIntent.parse("geo:0,0?q=Khreshchatyk+1")
        assertTrue(t is CarNavigateIntent.Target.Query)
    }

    @Test
    fun `nonsense is refused rather than guessed`() {
        assertNull(CarNavigateIntent.parse(null))
        assertNull(CarNavigateIntent.parse("https://maps.example/x"))
        assertNull(CarNavigateIntent.parse("geo:"))
        assertNull(CarNavigateIntent.parse("geo:0,0"))
    }

    @Test
    fun `out-of-range coordinates are not a destination`() {
        assertNull(CarNavigateIntent.parse("geo:911,999"))
    }
}
