package local.phantom.companion.core.geo.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flagship composition. Anchors are the same USNO table the almanac is
 * pinned against: Kyiv 2026-06-21 UTC — sunset 18:13, civil dusk 18:59.
 */
class UsableLightTest {

    private val kyivLat = 50.45
    private val kyivLon = 30.52

    @Test
    fun fortyMinutesBeforeCivilDuskReadsAsFortyMinutesOfLight() {
        val now = utc(2026, 6, 21) + hours(18) + minutes(19)
        val phase = UsableLight.phase(now, kyivLat, kyivLon)
        assertTrue("got $phase", phase is LightPhase.UsableLight)
        assertTrue("got $phase", (phase as LightPhase.UsableLight).minutes in 39..40)
    }

    /**
     * The whole product decision in one assertion: at this instant the sun is
     * already below the horizon, and the countdown still reports usable light,
     * because civil twilight is where the last workable light lives. A build
     * that counted to sunset would report nothing here.
     */
    @Test
    fun lightRemainsAfterSunsetBecauseTheCountdownEndsAtCivilDusk() {
        val now = utc(2026, 6, 21) + hours(18) + minutes(19)
        assertTrue(SolarAlmanac.altitudeDeg(now, kyivLat, kyivLon) < 0.0)
        assertTrue(UsableLight.phase(now, kyivLat, kyivLon) is LightPhase.UsableLight)
    }

    @Test
    fun middayCarriesNoCountdownWorthShowing() {
        val now = utc(2026, 6, 21) + hours(9)
        val phase = UsableLight.phase(now, kyivLat, kyivLon)
        assertTrue("got $phase", phase is LightPhase.Ample)
        assertTrue((phase as LightPhase.Ample).minutes > UsableLight.WINDOW_MINUTES)
    }

    @Test
    fun pastCivilDuskTheFaceSwitchesFromLightToDarkness() {
        val now = utc(2026, 6, 21) + hours(19) + minutes(10)
        val phase = UsableLight.phase(now, kyivLat, kyivLon)
        assertTrue("got $phase", phase is LightPhase.FallingDark)
        assertTrue((phase as LightPhase.FallingDark).minutes > 0)
    }

    @Test
    fun firstLightCountsUpToNauticalDawn() {
        val start = utc(2026, 12, 21)
        val bmnt = SolarAlmanac.day(start, start + 86_400_000L, kyivLat, kyivLon).nautical.dawn!!
        val phase = UsableLight.phase(bmnt - minutes(45), kyivLat, kyivLon)
        assertTrue("got $phase", phase is LightPhase.FirstLight)
        assertEquals(45, (phase as LightPhase.FirstLight).minutes)
    }

    @Test
    fun deepNightIsSilentUntilFirstLightIsInsideTheWindow() {
        val start = utc(2026, 12, 21)
        val bmnt = SolarAlmanac.day(start, start + 86_400_000L, kyivLat, kyivLon).nautical.dawn!!
        assertEquals(
            LightPhase.Night,
            UsableLight.phase(bmnt - minutes(200), kyivLat, kyivLon),
        )
    }

    @Test
    fun polarDayReportsLightAllNightInsteadOfACountdown() {
        val now = utc(2026, 6, 21) + hours(12)
        assertEquals(LightPhase.LightAllNight, UsableLight.phase(now, 78.22, 15.65))
    }

    @Test
    fun polarNightReportsNoDaylightInsteadOfZero() {
        val now = utc(2026, 12, 21) + hours(12)
        assertEquals(LightPhase.NoDaylight, UsableLight.phase(now, 82.0, 15.0))
    }

    /**
     * Both limbs, including the exact bottom of the night where the sun's
     * slope is zero — the instant a descending/ascending test decides by noise.
     */
    @Test
    fun aWhiteNightReportsThatItNeverGetsFullyDark() {
        val start = utc(2026, 6, 21)
        val lowest = culmination(start, start + 86_400_000L) { -SolarAlmanac.altitudeDeg(it, 59.33, 18.07) }
        assertEquals(LightPhase.NoFullDark, UsableLight.phase(lowest, 59.33, 18.07))
        assertEquals(LightPhase.NoFullDark, UsableLight.phase(lowest - minutes(20), 59.33, 18.07))
        assertEquals(LightPhase.NoFullDark, UsableLight.phase(lowest + minutes(20), 59.33, 18.07))
    }

    @Test
    fun theCountdownFloorsSoItNeverHandsOutLightThatIsNotThere() {
        val start = utc(2026, 6, 21)
        val dusk = SolarAlmanac.day(start, start + 86_400_000L, kyivLat, kyivLon).civil.dusk!!
        val phase = UsableLight.phase(dusk - 30_000L, kyivLat, kyivLon)
        assertEquals(0, (phase as LightPhase.UsableLight).minutes)
    }

    @Test
    fun roundingIsExactBelowTwentyAndNearestFiveAboveIt() {
        assertEquals(7, UsableLight.rounded(7))
        assertEquals(20, UsableLight.rounded(20))
        assertEquals(40, UsableLight.rounded(42))
        assertEquals(45, UsableLight.rounded(43))
        assertEquals(90, UsableLight.rounded(88))
    }

    private fun hours(n: Int) = n * 3_600_000L

    private fun minutes(n: Int) = n * 60_000L
}
