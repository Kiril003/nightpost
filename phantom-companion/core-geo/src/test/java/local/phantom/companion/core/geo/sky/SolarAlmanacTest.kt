package local.phantom.companion.core.geo.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Reference: US Naval Observatory `aa.usno.navy.mil/api/rstt/oneday`, tz=0,
 * queried 2026-08-15. USNO publishes to the whole minute, so ±30 s of the gap
 * measured here is the table's own rounding, not this code's error.
 *
 * There is no published table here for the nautical and astronomical
 * thresholds — USNO's one-day endpoint serves civil twilight only. They are
 * therefore covered structurally instead: the solver that lands civil dusk
 * inside a minute differs from the nautical and astronomical one by nothing
 * but the threshold constant it is handed, and [twilightsAreOrderedEverywhere]
 * pins that the three come out in the physically required order at eight
 * different latitudes. That is weaker evidence than a table and is stated as
 * such rather than dressed up.
 */
class SolarAlmanacTest {

    /** Measured worst case over these vectors is 42 s, of which up to 30 s is
     *  USNO's own rounding to the minute. */
    private val toleranceMs = 60_000L

    @Test
    fun sunEventsMatchNavalObservatoryTable() {
        val errors = mutableListOf<String>()
        var worst = 0L
        VECTORS.forEach { v ->
            val start = utc(v.year, v.month, v.day)
            val day = SolarAlmanac.day(start, start + 86_400_000L, v.lat, v.lon)
            listOf(
                "civil dawn" to (v.civilDawn to day.civil.dawn),
                "sunrise" to (v.sunrise to day.sunrise),
                "transit" to (v.transit to day.solarNoonMs),
                "sunset" to (v.sunset to day.sunset),
                "civil dusk" to (v.civilDusk to day.civil.dusk),
            ).forEach { (label, pair) ->
                val (expected, actual) = pair
                if (expected == null) return@forEach
                if (actual == null) {
                    errors += "${v.place} ${v.month}/${v.day} $label: expected $expected, got none"
                    return@forEach
                }
                val gap = abs(actual - hhmm(start, expected))
                worst = maxOf(worst, gap)
                if (gap > toleranceMs) {
                    errors += "${v.place} ${v.month}/${v.day} $label: off by ${gap / 1000}s " +
                        "(want $expected, got ${clock(start, actual)})"
                }
            }
        }
        println("worst sun-event error vs USNO: ${worst / 1000}s over ${VECTORS.size} vectors")
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test
    fun polarDayReportsNoSunriseAndNoSunsetRatherThanAGuess() {
        val start = utc(2026, 6, 21)
        val day = SolarAlmanac.day(start, start + 86_400_000L, 78.22, 15.65)
        assertNull(day.sunrise)
        assertNull(day.sunset)
        assertNull(day.civil.dusk)
        assertEquals(SunVisibility.ABOVE_ALL_DAY, day.visibility)
        assertTrue("noon altitude ${day.noonAltitudeDeg}", day.noonAltitudeDeg > 30.0)
    }

    @Test
    fun polarNightReportsBelowAllDayNotAboveAllDay() {
        val start = utc(2026, 12, 21)
        val day = SolarAlmanac.day(start, start + 86_400_000L, 78.22, 15.65)
        assertNull(day.sunrise)
        assertNull(day.sunset)
        assertNull(day.civil.dawn)
        assertEquals(SunVisibility.BELOW_ALL_DAY, day.visibility)
        assertTrue("noon altitude ${day.noonAltitudeDeg}", day.noonAltitudeDeg < -5.0)
    }

    @Test
    fun kyivMidsummerHasNoAstronomicalNight() {
        val start = utc(2026, 6, 21)
        val day = SolarAlmanac.day(start, start + 86_400_000L, KYIV_LAT, KYIV_LON)
        assertNotNull(day.nautical.dusk)
        assertNull("the sun does not reach -18 at 50.45N in June", day.astronomical.dusk)
        assertNull(day.astronomical.dawn)
    }

    @Test
    fun stockholmMidsummerNeverReachesNauticalTwilight() {
        val start = utc(2026, 6, 21)
        val day = SolarAlmanac.day(start, start + 86_400_000L, 59.33, 18.07)
        assertNotNull(day.civil.dusk)
        assertNull(day.nautical.dusk)
        assertNull(day.nautical.dawn)
    }

    @Test
    fun twilightsAreOrderedEverywhere() {
        listOf(
            Triple(KYIV_LAT, KYIV_LON, 3),
            Triple(KYIV_LAT, KYIV_LON, 10),
            Triple(0.0, 0.0, 3),
            Triple(0.0, 0.0, 9),
            Triple(-33.87, 151.21, 6),
            Triple(-33.87, 151.21, 12),
            Triple(67.0, 20.0, 3),
            Triple(67.0, 20.0, 9),
        ).forEach { (lat, lon, month) ->
            val start = utc(2026, month, 15)
            val day = SolarAlmanac.day(start, start + 86_400_000L, lat, lon)
            val dusk = listOfNotNull(day.sunset, day.civil.dusk, day.nautical.dusk, day.astronomical.dusk)
            assertEquals(
                "dusk order broken at $lat/$lon month $month: $dusk",
                dusk.sorted(),
                dusk,
            )
            val dawn = listOfNotNull(day.astronomical.dawn, day.nautical.dawn, day.civil.dawn, day.sunrise)
            assertEquals(
                "dawn order broken at $lat/$lon month $month: $dawn",
                dawn.sorted(),
                dawn,
            )
        }
    }

    @Test
    fun fifteenDegreesOfLongitudeShiftsSunsetByOneHour() {
        val start = utc(2026, 4, 10)
        val here = SolarAlmanac.day(start, start + 86_400_000L, 50.0, 0.0).sunset!!
        val west = SolarAlmanac.day(start, start + 86_400_000L, 50.0, -15.0).sunset!!
        val shift = west - here
        assertTrue("shift was ${shift / 1000}s", abs(shift - 3_600_000L) < 30_000L)
    }

    @Test
    fun solarNoonSitsDueSouthInTheNorthAndDueNorthInTheSouth() {
        val start = utc(2026, 5, 5)
        val north = SolarAlmanac.day(start, start + 86_400_000L, KYIV_LAT, KYIV_LON)
        val northAzimuth = SolarAlmanac.position(north.solarNoonMs, KYIV_LAT, KYIV_LON).azimuthDeg
        assertTrue("noon azimuth $northAzimuth", abs(northAzimuth - 180.0) < 0.5)

        val south = SolarAlmanac.day(start, start + 86_400_000L, -33.87, 151.21)
        val southAzimuth = SolarAlmanac.position(south.solarNoonMs, -33.87, 151.21).azimuthDeg
        assertTrue("noon azimuth $southAzimuth", abs(((southAzimuth + 180.0) % 360.0) - 180.0) < 0.5)
    }

    private companion object {
        const val KYIV_LAT = 50.45
        const val KYIV_LON = 30.52

        val VECTORS = listOf(
            SunVector("Kyiv", KYIV_LAT, KYIV_LON, 2026, 6, 21, "01:01", "01:46", "10:00", "18:13", "18:59"),
            SunVector("Kyiv", KYIV_LAT, KYIV_LON, 2026, 12, 21, "05:17", "05:56", "09:56", "13:56", "14:35"),
            SunVector("Kyiv", KYIV_LAT, KYIV_LON, 2026, 3, 20, "03:29", "04:01", "10:05", "16:11", "16:43"),
            SunVector("Kyiv", KYIV_LAT, KYIV_LON, 2026, 8, 15, "02:10", "02:46", "10:02", "17:18", "17:54"),
            SunVector("Kiruna", 67.0, 20.0, 2026, 12, 21, "07:45", "09:53", "10:38", "11:23", "13:31"),
            SunVector("Equator", 0.0, 0.0, 2026, 6, 21, "05:36", "05:58", "12:02", "18:05", "18:28"),
            SunVector("Equator", 0.0, 0.0, 2026, 3, 20, "05:44", "06:04", "12:07", "18:11", "18:31"),
            SunVector("Sydney", -33.87, 151.21, 2026, 6, 21, "20:32", "21:00", "01:57", "06:54", "07:22"),
            SunVector("Stockholm", 59.33, 18.07, 2026, 6, 21, "23:59", "01:31", "10:50", "20:08", "21:40"),
        )
    }
}

internal data class SunVector(
    val place: String,
    val lat: Double,
    val lon: Double,
    val year: Int,
    val month: Int,
    val day: Int,
    val civilDawn: String?,
    val sunrise: String?,
    val transit: String,
    val sunset: String?,
    val civilDusk: String?,
)

internal fun utc(year: Int, month: Int, day: Int): Long =
    LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun hhmm(dayStartMs: Long, text: String): Long {
    val parts = text.split(":")
    return dayStartMs + parts[0].toLong() * 3_600_000L + parts[1].toLong() * 60_000L
}

internal fun clock(dayStartMs: Long, epochMs: Long): String {
    val minutes = (epochMs - dayStartMs) / 60_000L
    return "%02d:%02d".format(minutes / 60, minutes % 60)
}
