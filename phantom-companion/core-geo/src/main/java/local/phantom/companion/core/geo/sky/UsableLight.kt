package local.phantom.companion.core.geo.sky

/**
 * «~40 хв придатного світла» — the countdown, composed from two exact facts.
 *
 * **Usable light ends at civil dusk (−6°), not at sunset.** Sunset throws away
 * the ~35–40 minutes at these latitudes in which a crew can still read a map,
 * pick footing and patch a tyre; counting to nautical dusk (−12°, EENT) would
 * do the opposite and call the last half hour "usable" when it is not. So the
 * countdown claims *light for work*, and darkness gets its own separate face —
 * [LightPhase.FallingDark] counts to EENT, and says «темрява», never «світло».
 *
 * Two limits this cannot see, which the surface must state rather than imply:
 * terrain — every time here assumes a flat open horizon, and a balka, a
 * treeline or a street wall goes dark well before it; and the clock — the
 * countdown is exactly as true as the device's idea of "now", which after a
 * long blackout is not a given.
 */
object UsableLight {

    /** Beyond this the number drives no decision and is only noise on a map. */
    const val WINDOW_MINUTES = 90

    private const val SLOPE_PROBE_MS = 300_000L

    fun phase(
        nowMs: Long,
        latDeg: Double,
        lonDeg: Double,
        windowMinutes: Int = WINDOW_MINUTES,
    ): LightPhase {
        val altitude = SolarAlmanac.altitudeDeg(nowMs, latDeg, lonDeg)

        if (altitude > SolarAlmanac.CIVIL_DEG) {
            val civilDusk = SolarAlmanac.nextSet(nowMs, latDeg, lonDeg, SolarAlmanac.CIVIL_DEG)
                ?: return LightPhase.LightAllNight
            val minutes = minutesBetween(nowMs, civilDusk)
            return if (minutes <= windowMinutes) {
                LightPhase.UsableLight(minutes)
            } else {
                LightPhase.Ample(minutes)
            }
        }

        if (altitude > SolarAlmanac.NAUTICAL_DEG) {
            // Asking "does it get dark at all" BEFORE asking which limb we are
            // on: at the bottom of a white night the sun's slope is zero, and a
            // limb test there decides by rounding noise.
            val eent = SolarAlmanac.nextSet(nowMs, latDeg, lonDeg, SolarAlmanac.NAUTICAL_DEG)
                ?: return LightPhase.NoFullDark
            val descending =
                SolarAlmanac.altitudeDeg(nowMs + SLOPE_PROBE_MS, latDeg, lonDeg) < altitude
            return if (descending) {
                LightPhase.FallingDark(minutesBetween(nowMs, eent))
            } else {
                LightPhase.Dawning
            }
        }

        val bmnt = SolarAlmanac.nextRise(nowMs, latDeg, lonDeg, SolarAlmanac.NAUTICAL_DEG)
            ?: return LightPhase.NoDaylight
        val minutes = minutesBetween(nowMs, bmnt)
        return if (minutes <= windowMinutes) LightPhase.FirstLight(minutes) else LightPhase.Night
    }

    /** Nearest five above twenty minutes, exact below it — the precision the
     *  «~» in the copy is actually claiming. */
    fun rounded(minutes: Int): Int = if (minutes <= 20) minutes else ((minutes + 2) / 5) * 5

    /** Floored on purpose: a countdown that rounds up hands the crew light it
     *  does not have. */
    private fun minutesBetween(fromMs: Long, toMs: Long): Int =
        (((toMs - fromMs) / 60_000L).coerceAtLeast(0L)).toInt()
}

sealed interface LightPhase {

    /** Work light left before civil dusk, inside the window worth showing. */
    data class UsableLight(val minutes: Int) : LightPhase

    /** Past civil dusk: counting down to EENT — full dark to the naked eye. */
    data class FallingDark(val minutes: Int) : LightPhase

    /** Full dark, counting up to BMNT — the first usable light of the morning. */
    data class FirstLight(val minutes: Int) : LightPhase

    /** More light left than the window; the number is real but says nothing. */
    data class Ample(val minutes: Int) : LightPhase

    /** Between BMNT and civil dawn — light is arriving, nothing is running out. */
    data object Dawning : LightPhase

    /** Deep in the night with first light beyond the window. */
    data object Night : LightPhase

    /** The sun does not drop below −6° at all: light through the whole night. */
    data object LightAllNight : LightPhase

    /** The sun does not climb to −12° at all: no BMNT, a day without daylight. */
    data object NoDaylight : LightPhase

    /** Below civil but never reaching −12°: it never gets properly dark. */
    data object NoFullDark : LightPhase
}
