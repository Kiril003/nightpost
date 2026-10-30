package local.phantom.companion.core.geo.sky

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Сонце: висота, азимут, схід/захід і три сутінки.
 *
 * Series: the USNO low-precision solar formulae — the sun's apparent position
 * to ±0.01° over 1950–2050, which at Ukrainian latitudes is a few seconds of
 * clock time on a horizon crossing. Threshold times are found by scanning the
 * real altitude curve and bisecting the bracket, not by inverting an hour
 * angle once: the one-shot form quietly drifts by minutes near the poles,
 * where the answer matters most and is hardest to check.
 */
object SolarAlmanac {

    /** Geometric centre at −34′ refraction −16′ semidiameter: the published
     *  sunrise/sunset convention, not a bare 0°. */
    const val HORIZON_DEG = -0.833

    const val CIVIL_DEG = -6.0

    const val NAUTICAL_DEG = -12.0

    const val ASTRONOMICAL_DEG = -18.0

    /** Сонце нижче цього — золота година: довгі тіні, блиск у вітрове скло. */
    const val GOLDEN_DEG = 6.0

    /** Шов полярності землі. Не астрономія — виміряна щільність скриму; див.
     *  таблицю порогів у [SkyPhase]. */
    const val SEAM_DEG = -5.0

    private const val LIMB_PROBE_MS = 300_000L

    fun position(epochMs: Long, latDeg: Double, lonDeg: Double): Horizontal =
        equatorial(epochMs).toHorizontal(epochMs, latDeg, lonDeg)

    fun altitudeDeg(epochMs: Long, latDeg: Double, lonDeg: Double): Double =
        position(epochMs, latDeg, lonDeg).altitudeDeg

    fun phaseOf(altitudeDeg: Double): SkyPhase = when {
        altitudeDeg > GOLDEN_DEG -> SkyPhase.DAY
        altitudeDeg > SEAM_DEG -> SkyPhase.GOLDEN
        altitudeDeg > NAUTICAL_DEG -> SkyPhase.DUSK
        else -> SkyPhase.NIGHT
    }

    /**
     * ЄДИНЕ джерело сонця для всього застосунку — положення і фаза одним
     * значенням. До ATLAS-1 ефемериду рахували втричі: тут, у `core-nav`
     * («Ω-3 TERRAIN») і ще раз у самій мапі, двома різними наближеннями.
     * Розбіжність двох формул доходила до 0,46°, тобто до ~2,5 хвилини на
     * перетині шва — тобто мапа й показник світла могли назвати ніччю різні
     * хвилини одного вечора.
     *
     * Лімб береться нахилом кривої, а не годинним кутом: на дні білої ночі
     * нахил майже нульовий, і там байдуже, куди він округлиться, — зате
     * жодного окремого виведення часу, яке могло б розійтися з висотою.
     */
    fun sky(epochMs: Long, latDeg: Double, lonDeg: Double): SkyFix {
        val here = position(epochMs, latDeg, lonDeg)
        val soon = altitudeDeg(epochMs + LIMB_PROBE_MS, latDeg, lonDeg)
        return SkyFix(
            elevationDeg = here.altitudeDeg,
            azimuthDeg = here.azimuthDeg,
            phase = phaseOf(here.altitudeDeg),
            morning = soon > here.altitudeDeg,
        )
    }

    fun nextRise(
        fromMs: Long,
        latDeg: Double,
        lonDeg: Double,
        targetDeg: Double = HORIZON_DEG,
        withinMs: Long = TWO_DAYS_MS,
    ): Long? = nextCrossing(fromMs, fromMs + withinMs, targetDeg, rising = true) {
        altitudeDeg(it, latDeg, lonDeg)
    }

    fun nextSet(
        fromMs: Long,
        latDeg: Double,
        lonDeg: Double,
        targetDeg: Double = HORIZON_DEG,
        withinMs: Long = TWO_DAYS_MS,
    ): Long? = nextCrossing(fromMs, fromMs + withinMs, targetDeg, rising = false) {
        altitudeDeg(it, latDeg, lonDeg)
    }

    /**
     * Every light boundary inside one window, typically local midnight to
     * local midnight. Any field may be null and that is a fact, not a gap:
     * above the Arctic circle in June there is no sunset, and in December no
     * sunrise. [SunDay.risesToday]/[SunDay.setsToday] name the two cases so a
     * caller never has to infer them from a null.
     */
    fun day(fromMs: Long, toMs: Long, latDeg: Double, lonDeg: Double): SunDay {
        val altitude = { t: Long -> altitudeDeg(t, latDeg, lonDeg) }
        fun pair(target: Double) = TwilightPair(
            dawn = nextCrossing(fromMs, toMs, target, rising = true, altitudeAt = altitude),
            dusk = nextCrossing(fromMs, toMs, target, rising = false, altitudeAt = altitude),
        )
        val horizon = pair(HORIZON_DEG)
        val noon = culmination(fromMs, toMs, altitude)
        return SunDay(
            sunrise = horizon.dawn,
            sunset = horizon.dusk,
            civil = pair(CIVIL_DEG),
            nautical = pair(NAUTICAL_DEG),
            astronomical = pair(ASTRONOMICAL_DEG),
            solarNoonMs = noon,
            noonAltitudeDeg = altitude(noon),
            sunsetBearingDeg = horizon.dusk?.let { position(it, latDeg, lonDeg).azimuthDeg },
        )
    }

    internal fun equatorial(epochMs: Long): Equatorial {
        val n = daysFromJ2000(epochMs)
        val meanLongitude = norm360(280.460 + 0.985_647_4 * n)
        val meanAnomaly = Math.toRadians(norm360(357.528 + 0.985_600_3 * n))
        val ecliptic = Math.toRadians(
            meanLongitude + 1.915 * sin(meanAnomaly) + 0.020 * sin(2.0 * meanAnomaly),
        )
        val obliquity = Math.toRadians(23.439 - 0.000_000_4 * n)
        return Equatorial(
            rightAscensionDeg = norm360(
                Math.toDegrees(atan2(cos(obliquity) * sin(ecliptic), cos(ecliptic))),
            ),
            declinationDeg = Math.toDegrees(asin(sin(obliquity) * sin(ecliptic))),
        )
    }

    /** Earth–sun distance in AU; the moon's phase geometry needs it. */
    internal fun distanceAu(epochMs: Long): Double {
        val meanAnomaly = Math.toRadians(norm360(357.528 + 0.985_600_3 * daysFromJ2000(epochMs)))
        return 1.000_14 - 0.016_71 * cos(meanAnomaly) - 0.000_14 * cos(2.0 * meanAnomaly)
    }
}

data class TwilightPair(val dawn: Long?, val dusk: Long?)

data class SunDay(
    val sunrise: Long?,
    val sunset: Long?,
    val civil: TwilightPair,
    val nautical: TwilightPair,
    val astronomical: TwilightPair,
    val solarNoonMs: Long,
    val noonAltitudeDeg: Double,
    /** Compass bearing the sun sets on — the glare bearing, and the one number
     *  that orients a crew without a compass. Null when it does not set. */
    val sunsetBearingDeg: Double?,
) {
    val visibility: SunVisibility
        get() = when {
            sunrise != null || sunset != null -> SunVisibility.RISES_AND_SETS
            noonAltitudeDeg > SolarAlmanac.HORIZON_DEG -> SunVisibility.ABOVE_ALL_DAY
            else -> SunVisibility.BELOW_ALL_DAY
        }
}

enum class SunVisibility { RISES_AND_SETS, ABOVE_ALL_DAY, BELOW_ALL_DAY }
