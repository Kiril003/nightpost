package local.phantom.companion.ui.theme

import java.time.LocalDateTime
import java.time.ZoneId
import local.phantom.companion.core.design.theme.DayPhase
import local.phantom.companion.core.design.theme.resolveLivingTheme
import local.phantom.companion.core.geo.sky.SolarAlmanac
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Жива тема йде за СОНЦЕМ у точці людини, а не за годинником.
 *
 * Годинник (05/09/17/21) помилявся в обидва боки: грудневий Київ о 16:30 уже
 * в сутінках, а він казав «день»; червневий о 21:00 ще світлий, а він казав
 * «ніч». Кожен кейс нижче — саме такий розрив.
 */
class LivingSunTest {

    private val kyivZone = ZoneId.of("Europe/Kyiv")
    private val kyiv = SunPlace(50.45, 30.52, SunPlace.Source.FIX)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = kyivZone): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `Kyiv, 21 December 16-30 — sun already set, dark dusk, not day as the clock said`() {
        val t = at(2026, 12, 21, 16, 30)
        assertTrue(SolarAlmanac.altitudeDeg(t, kyiv.lat, kyiv.lon) < 0.0)
        val look = LivingSun.lookAt(t, kyiv)
        assertTrue("$look", look.dark)
        assertTrue(resolveLivingTheme(look).isDark)
    }

    @Test
    fun `Kyiv, 21 June 21-00 — still golden light, not night as the clock said`() {
        val t = at(2026, 6, 21, 21, 0)
        assertTrue(SolarAlmanac.altitudeDeg(t, kyiv.lat, kyiv.lon) > 0.0)
        assertEquals(DayPhase.Golden, LivingSun.phaseAt(t, kyiv))
    }

    @Test
    fun `Kyiv, an ordinary day walks the six stops in order`() {
        val seen = (0 until 24 * 60 step 5).map { LivingSun.phaseAt(at(2026, 9, 24, 0, 0) + it * 60_000L, kyiv) }
        val runs = seen.zipWithNext().filter { (a, b) -> a != b }.map { it.second }
        assertEquals(
            listOf(DayPhase.Dusk, DayPhase.Dawn, DayPhase.Morning, DayPhase.Day, DayPhase.Golden, DayPhase.Dusk, DayPhase.Night),
            runs,
        )
    }

    @Test
    fun `Kyiv in December still reaches noon - winter peak rule`() {
        assertEquals(DayPhase.Day, LivingSun.phaseAt(at(2026, 12, 21, 12, 0), kyiv))
    }

    @Test
    fun `direction picks the window - same height, morning dawn vs evening dusk`() {
        assertEquals(DayPhase.Dawn, LivingSun.phaseOf(-2.0, rising = true))
        assertEquals(DayPhase.Dusk, LivingSun.phaseOf(-2.0, rising = false))
        assertEquals(DayPhase.Night, LivingSun.phaseOf(Double.NaN, rising = true))
    }

    @Test
    fun `polar extremes never crash and stay physical`() {
        val poles = listOf(90.0, 89.99, 78.22, 69.65, -69.0, -89.99, -90.0)
        for (lat in poles) {
            val place = SunPlace(lat, 15.0, SunPlace.Source.FIX)
            for (month in listOf(3, 6, 9, 12)) {
                for (minute in 0 until 24 * 60 step 20) {
                    val t = at(2026, month, 21, 0, 0, ZoneId.of("UTC")) + minute * 60_000L
                    LivingSun.phaseAt(t, place) // must not throw
                }
            }
        }
        // Північний полюс: у червні сонце цілу добу високо, у грудні — глибоко.
        val north = SunPlace(90.0, 0.0, SunPlace.Source.FIX)
        for (h in 0 until 24) {
            assertEquals(DayPhase.Day, LivingSun.phaseAt(at(2026, 6, 21, h, 0, ZoneId.of("UTC")), north))
            assertEquals(DayPhase.Night, LivingSun.phaseAt(at(2026, 12, 21, h, 0, ZoneId.of("UTC")), north))
        }
        // Шпіцберген у полярну ніч опівдні — темно, а не «день за годинником».
        val svalbard = SunPlace(78.22, 15.65, SunPlace.Source.FIX)
        assertTrue(LivingSun.lookAt(at(2026, 12, 21, 12, 0, ZoneId.of("Europe/Oslo")), svalbard).dark)
    }

    @Test
    fun `fallback ladder - zone centre, then locale country, then offset on the equator`() {
        val zone = SunPlaces.fallback("Europe/Kyiv", 2 * 3_600_000, "US")
        assertEquals(SunPlace.Source.ZONE, zone.source)
        assertEquals(50.45, zone.lat, 0.0)

        val country = SunPlaces.fallback("Etc/GMT-2", 2 * 3_600_000, "ua")
        assertEquals(SunPlace.Source.COUNTRY, country.source)
        assertEquals(49.0, country.lat, 0.0)

        val offset = SunPlaces.fallback("Asia/Nowhere", 3 * 3_600_000, "ZZ")
        assertEquals(SunPlace.Source.OFFSET, offset.source)
        assertEquals(0.0, offset.lat, 0.0)
        assertEquals(45.0, offset.lon, 1e-9)

        val noCountry = SunPlaces.fallback("Asia/Nowhere", -5 * 3_600_000, null)
        assertEquals(-75.0, noCountry.lon, 1e-9)
    }

    @Test
    fun `a broken fix is refused, a wrapped longitude is normalised`() {
        assertNull(SunPlace.of(Double.NaN, 30.0, SunPlace.Source.FIX))
        assertNull(SunPlace.of(50.0, Double.POSITIVE_INFINITY, SunPlace.Source.FIX))
        assertNull(SunPlace.of(91.0, 30.0, SunPlace.Source.FIX))
        assertEquals(-170.0, SunPlace.of(10.0, 190.0, SunPlace.Source.FIX)!!.lon, 1e-9)
    }
}
