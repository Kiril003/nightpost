package local.phantom.companion.feature.stream.ui.map

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt
import local.phantom.companion.core.geo.sky.SkyPhase
import local.phantom.companion.core.geo.sky.SolarAlmanac
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sun as the 3D city's light source. Elevation already drove the basemap
 * scrim; what is pinned here is the direction — get the frame wrong and every
 * shadow in the city falls the wrong way, which no screenshot review catches
 * because a wrong shadow still looks like a shadow.
 */
class SolarSkyTest {

    private val kyivLat = 50.45
    private val kyivLon = 30.52

    /** 2026-06-21T00:00:00Z, summer solstice. */
    private val solsticeUtc = 1_781_308_800_000L

    private fun at(hourUtc: Double, lat: Double = kyivLat, lon: Double = kyivLon) =
        skyFor(lat, lon, solsticeUtc + (hourUtc * 3_600_000.0).toLong())

    @Test
    fun `the sun vector is unit length wherever and whenever it is asked for`() {
        for (lat in listOf(-70.0, -33.9, 0.0, 50.45, 78.0)) {
            for (dayOffset in 0 until 365 step 29) {
                for (hour in 0 until 24 step 3) {
                    val sky = skyFor(lat, kyivLon, solsticeUtc + (dayOffset * 24L + hour) * 3_600_000L)
                    val len = sqrt(
                        (sky.sunX * sky.sunX + sky.sunY * sky.sunY + sky.sunZ * sky.sunZ).toDouble()
                    )
                    assertEquals("lat=$lat day=$dayOffset hour=$hour", 1.0, len, 1e-5)
                }
            }
        }
    }

    @Test
    fun `at solar noon in Kyiv the sun stands due south`() {
        var best = at(0.0)
        for (minute in 1 until 24 * 60) {
            val sky = at(minute / 60.0)
            if (sky.elevationDeg > best.elevationDeg) best = sky
        }
        assertEquals("noon azimuth", 180.0, best.azimuthDeg, 1.0)
        assertEquals("Kyiv solstice noon elevation", 63.0, best.elevationDeg, 1.0)
    }

    @Test
    fun `a southern-hemisphere noon sun stands due north instead`() {
        var best = skyFor(-33.9, 151.2, solsticeUtc)
        for (minute in 1 until 24 * 60) {
            val sky = skyFor(-33.9, 151.2, solsticeUtc + minute * 60_000L)
            if (sky.elevationDeg > best.elevationDeg) best = sky
        }
        val fromNorth = minOf(best.azimuthDeg, 360.0 - best.azimuthDeg)
        assertTrue("azimuth=${best.azimuthDeg}", fromNorth < 1.0)
    }

    @Test
    fun `the morning sun is in the east and the evening sun in the west`() {
        // x is east in the mesh frame, so its sign is the whole assertion.
        val morning = at(6.0)
        val evening = at(14.0)
        assertTrue("morning", morning.morning)
        assertTrue("morning sun x=${morning.sunX}", morning.sunX > 0f)
        assertTrue("evening sun x=${evening.sunX}", evening.sunX < 0f)
    }

    @Test
    fun `the vector agrees with the elevation and azimuth reported beside it`() {
        for (hour in 0 until 24) {
            val sky = at(hour.toDouble())
            assertEquals(
                "hour=$hour up component",
                sin(Math.toRadians(sky.elevationDeg)),
                sky.sunY.toDouble(),
                1e-5,
            )
            // z is south, so north is -z; azimuth is measured from north, clockwise.
            val fromVector = (Math.toDegrees(atan2(sky.sunX.toDouble(), -sky.sunZ.toDouble())) + 360.0) % 360.0
            val gap = abs(fromVector - sky.azimuthDeg).let { minOf(it, 360.0 - it) }
            assertTrue("hour=$hour azimuth=${sky.azimuthDeg} vector=$fromVector", gap < 1e-3)
        }
    }

    @Test
    fun `night puts the sun under the ground rather than parking it on the horizon`() {
        val midnight = at(21.5)
        assertTrue("elevation=${midnight.elevationDeg}", midnight.elevationDeg < 0.0)
        assertTrue("sunY=${midnight.sunY}", midnight.sunY < 0f)
        assertTrue(midnight.dark)
    }

    /**
     * Власник, 16.08: «мапа швидко темною стає».
     *
     * Оболонка йде за годинником — `resolveLivingTheme` віддає «dusk-ember» на
     * 17..20 годину, — а мапа йшла за оболонкою. У Житомирі 16 серпня о 18:36
     * сонце ще на ~16°, і земля не має права бути нічною: ніч тут визначає
     * небо, а не календарне відро.
     *
     * Пін стоїть на самому небі, бо саме воно тепер вирішує полярність землі.
     */
    @Test
    fun `an August evening two hours before sunset is not night`() {
        // 2026-08-16T15:36:00Z = 18:36 у Житомирі (UTC+3).
        val zhytomyrLat = 49.97
        val zhytomyrLon = 28.79
        val augEvening = 1_786_894_560_000L
        val sky = skyFor(zhytomyrLat, zhytomyrLon, augEvening)
        assertTrue("сонце на ${sky.elevationDeg}°", sky.elevationDeg > 10.0)
        assertTrue("вечір названо ніччю: елевація ${sky.elevationDeg}°", !sky.dark)

        // А через пʼять годин — уже справді ніч, і мапа мусить це знати.
        val night = skyFor(zhytomyrLat, zhytomyrLon, augEvening + 5 * 3_600_000L)
        assertTrue("ніч не розпізнано: елевація ${night.elevationDeg}°", night.dark)
    }

    @Test
    fun `the scrim and the dark seam still behave as they did before the vector arrived`() {
        assertEquals(0f, at(9.0).scrim.alpha, 1e-6f)
        assertTrue(at(9.0).elevationDeg > 10.0)
        assertTrue(at(21.5).scrim.alpha > 0.4f)
    }

    /** Полярність мапи тепер похідна від фази, а не власне поле. Розходження
     *  між ними означало б дві відповіді на одне питання. */
    @Test
    fun `the map polarity is the phase and nothing beside it`() {
        for (hour in 0 until 24) {
            val sky = at(hour.toDouble())
            assertEquals("година $hour", SolarAlmanac.phaseOf(sky.elevationDeg), sky.phase)
            assertEquals("година $hour", sky.phase.dark, sky.dark)
        }
    }

    /**
     * Золота година тепліє скримом, а НЕ перевантаженням стилю.
     *
     * Це та сама поразка, що й «мапа швидко темною стає», записана з іншого
     * боку: варто мапнути `GOLDEN` на сутінкову землю, і вона піде в темне
     * приблизно за годину до заходу. Скрим уже робить цю роботу безкоштовно —
     * тут доводиться, що він її робить, поки земля лишається денною.
     */
    @Test
    fun `the golden band warms the glass while the ground stays day`() {
        val zhytomyrLat = 49.97
        val zhytomyrLon = 28.79
        // 2026-08-23T00:00:00Z; шов заходу — ~17:35Z.
        val day = 1_787_443_200_000L
        val seam = SolarAlmanac.nextSet(day, zhytomyrLat, zhytomyrLon, SolarAlmanac.SEAM_DEG)!!

        val golden = skyFor(zhytomyrLat, zhytomyrLon, seam - 30 * 60_000L)
        assertEquals(SkyPhase.GOLDEN, golden.phase)
        assertTrue("земля потемніла до шва", !golden.dark)
        assertTrue("скло не тепліє в золоту годину", golden.scrim.alpha > 0.15f)

        val dusk = skyFor(zhytomyrLat, zhytomyrLon, seam + 60_000L)
        assertEquals(SkyPhase.DUSK, dusk.phase)
        assertTrue(dusk.dark)
        assertTrue("скрим не встиг сховати перезавантаження стилю", dusk.scrim.alpha > 0.25f)
    }
}
