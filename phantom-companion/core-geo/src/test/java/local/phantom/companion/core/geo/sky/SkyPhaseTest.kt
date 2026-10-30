package local.phantom.companion.core.geo.sky

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Одне сонце на весь застосунок — і шов, який не сміє поїхати.
 *
 * До ATLAS-1 ефемериду рахували втричі: тут, у `core-nav` і всередині самої
 * мапи. Дві з трьох реалізацій знято, і саме тому ці перевірки існують: поки
 * джерело було не одне, розбіжність між ними ніде не падала — вона просто
 * робила так, що мапа й показник світла називали ніччю різні хвилини.
 */
class SkyPhaseTest {

    private val zhytomyrLat = 49.97
    private val zhytomyrLon = 28.79

    /** 2026-08-23T00:00:00Z. */
    private val augustDay = 1_787_443_200_000L

    @Test
    fun `the phase table is exactly the one the docs claim`() {
        assertEquals(SkyPhase.DAY, SolarAlmanac.phaseOf(40.0))
        assertEquals(SkyPhase.DAY, SolarAlmanac.phaseOf(6.01))
        assertEquals(SkyPhase.GOLDEN, SolarAlmanac.phaseOf(6.0))
        assertEquals(SkyPhase.GOLDEN, SolarAlmanac.phaseOf(0.0))
        assertEquals(SkyPhase.GOLDEN, SolarAlmanac.phaseOf(-4.99))
        assertEquals(SkyPhase.DUSK, SolarAlmanac.phaseOf(-5.0))
        assertEquals("цивільні сутінки мусять лежати ВСЕРЕДИНІ смуги DUSK", SkyPhase.DUSK, SolarAlmanac.phaseOf(SolarAlmanac.CIVIL_DEG))
        assertEquals(SkyPhase.DUSK, SolarAlmanac.phaseOf(-11.99))
        assertEquals(SkyPhase.NIGHT, SolarAlmanac.phaseOf(-12.0))
        assertEquals(SkyPhase.NIGHT, SolarAlmanac.phaseOf(-40.0))
    }

    /**
     * Шов полярності НЕ поїхав.
     *
     * Це головна перевірка цього файлу. Власник спіймав нас на протилежному
     * 16.08 — «мапа швидко темною стає», — і будь-яка спроба назвати землю
     * темною раніше за −5° вертає ту саму поразку. Золота година лишається
     * ДНЕМ: її несе скрим, а не перевантаження стилю.
     */
    @Test
    fun `dark still means exactly five degrees below the horizon`() {
        assertEquals(-5.0, SolarAlmanac.SEAM_DEG, 1e-9)
        for (tenths in -200..200) {
            val altitude = tenths / 10.0
            assertEquals(
                "полярність на $altitude° розійшлася зі швом",
                altitude <= SolarAlmanac.SEAM_DEG,
                SolarAlmanac.phaseOf(altitude).dark,
            )
        }
    }

    /**
     * `DayPhase.DUSK` рік простояв оголошеним і жодного разу не побудованим,
     * бо полярність їхала до мапи як `Boolean`. Тут доводиться, що смуга
     * СПРАВДІ трапляється — і що вона варта окремого файла стилю, а не чотирьох
     * хвилин між двома перезавантаженнями.
     */
    @Test
    fun `dusk is a real band, wide enough to be worth a style of its own`() {
        val seam = SolarAlmanac.nextSet(augustDay, zhytomyrLat, zhytomyrLon, SolarAlmanac.SEAM_DEG)
        val dark = SolarAlmanac.nextSet(augustDay, zhytomyrLat, zhytomyrLon, SolarAlmanac.NAUTICAL_DEG)
        assertNotNull(seam)
        assertNotNull(dark)

        assertEquals(SkyPhase.GOLDEN, phaseAt(seam!! - 60_000L))
        assertEquals(SkyPhase.DUSK, phaseAt(seam + 60_000L))
        assertEquals(SkyPhase.DUSK, phaseAt(dark!! - 60_000L))
        assertEquals(SkyPhase.NIGHT, phaseAt(dark + 60_000L))

        val minutes = (dark - seam) / 60_000L
        assertTrue("сутінки тривають $minutes хв — це не смуга, це блимання", minutes >= 20)
    }

    /**
     * Час шва прибитий до стінного годинника, а не тільки до порядку фаз.
     *
     * Наближення, яке мапа несла до ATLAS-1 (фіксований 365-денний рік), тут
     * давало 20:38:02 за київським часом; альманах USNO — 20:35:32. Різниця
     * 2,5 хв і вона в бік ПРАВДИ: старша формула запізнювалась. Пін стоїть на
     * новому числі, щоб наступна зміна формули не проїхала мовчки.
     */
    @Test
    fun `the seam crossing lands on the minute the almanac promises`() {
        val seam = SolarAlmanac.nextSet(augustDay, zhytomyrLat, zhytomyrLon, SolarAlmanac.SEAM_DEG)
        assertNotNull(seam)
        // 2026-08-23T17:35:32Z = 20:35:32 у Житомирі (UTC+3).
        val expected = 1_787_506_532_000L
        val driftS = abs(seam!! - expected) / 1000
        assertTrue("шов поїхав на ${driftS}s від очікуваного", driftS <= 60)
    }

    /** Власник, 16.08. Пін переїхав сюди разом із самим сонцем. */
    @Test
    fun `an August evening two hours before sunset is not night`() {
        // 2026-08-16T15:36:00Z = 18:36 у Житомирі.
        val evening = 1_786_894_560_000L
        val sky = SolarAlmanac.sky(evening, zhytomyrLat, zhytomyrLon)
        assertTrue("сонце на ${sky.elevationDeg}°", sky.elevationDeg > 10.0)
        assertEquals(SkyPhase.DAY, sky.phase)
        assertTrue("вечір названо ніччю", !sky.dark)

        val later = SolarAlmanac.sky(evening + 5 * 3_600_000L, zhytomyrLat, zhytomyrLon)
        assertTrue("ніч не розпізнано: ${later.elevationDeg}°", later.dark)
    }

    /** Одне джерело означає одне число: зведений вигляд не сміє мати власного
     *  шляху до висоти чи азимута. */
    @Test
    fun `the fix never disagrees with the position it was built from`() {
        for (hour in 0 until 24) {
            val t = augustDay + hour * 3_600_000L
            val sky = SolarAlmanac.sky(t, zhytomyrLat, zhytomyrLon)
            val pos = SolarAlmanac.position(t, zhytomyrLat, zhytomyrLon)
            assertEquals(pos.altitudeDeg, sky.elevationDeg, 1e-12)
            assertEquals(pos.azimuthDeg, sky.azimuthDeg, 1e-12)
            assertEquals(SolarAlmanac.phaseOf(pos.altitudeDeg), sky.phase)
        }
    }

    @Test
    fun `the limb knows morning from evening`() {
        // 06:00 і 15:00 за київським часом — обабіч полудня.
        assertTrue(SolarAlmanac.sky(augustDay + 3 * 3_600_000L, zhytomyrLat, zhytomyrLon).morning)
        assertTrue(!SolarAlmanac.sky(augustDay + 12 * 3_600_000L, zhytomyrLat, zhytomyrLon).morning)
    }

    private fun phaseAt(ms: Long) = SolarAlmanac.sky(ms, zhytomyrLat, zhytomyrLon).phase
}
