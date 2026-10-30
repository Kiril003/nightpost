package local.phantom.companion.core.geo.sky

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Астрономія без даних. Position + clock -> everything in this package.
 *
 * The whole light layer needs no OSM tag, no bake, no server and no network:
 * it cannot go stale, and it is exactly as true in occupied territory as
 * anywhere else. What it CANNOT do is see the ground — every time here is for
 * a flat, open horizon. A valley, a treeline or a street wall goes dark long
 * before the horizon does, and nothing in this package models that.
 */
data class Horizontal(val altitudeDeg: Double, val azimuthDeg: Double)

internal data class Equatorial(val rightAscensionDeg: Double, val declinationDeg: Double)

internal const val MS_PER_DAY = 86_400_000.0

internal const val J2000_JD = 2_451_545.0

internal fun julianDay(epochMs: Long): Double = epochMs / MS_PER_DAY + 2_440_587.5

internal fun daysFromJ2000(epochMs: Long): Double = julianDay(epochMs) - J2000_JD

internal fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

internal fun greenwichMeanSiderealDeg(epochMs: Long): Double {
    val d = daysFromJ2000(epochMs)
    val t = d / 36_525.0
    return norm360(280.460_618_37 + 360.985_647_366_29 * d + 0.000_387_933 * t * t)
}

internal fun Equatorial.toHorizontal(epochMs: Long, latDeg: Double, lonDeg: Double): Horizontal {
    val hourAngle = Math.toRadians(
        norm360(greenwichMeanSiderealDeg(epochMs) + lonDeg - rightAscensionDeg),
    )
    val lat = Math.toRadians(latDeg)
    val dec = Math.toRadians(declinationDeg)
    val sinAltitude = (sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(hourAngle))
        .coerceIn(-1.0, 1.0)
    val east = -cos(dec) * sin(hourAngle)
    val north = sin(dec) * cos(lat) - cos(dec) * sin(lat) * cos(hourAngle)
    return Horizontal(
        altitudeDeg = Math.toDegrees(asin(sinAltitude)),
        azimuthDeg = norm360(Math.toDegrees(atan2(east, north))),
    )
}

/** Coarse enough that a body cannot cross a threshold twice inside one step,
 *  fine enough that the bracket is always real. */
internal const val SCAN_STEP_MS = 240_000L

internal const val TWO_DAYS_MS = 172_800_000L

/**
 * The first time after [fromMs] at which [altitudeAt] crosses [targetDeg] in
 * the requested direction, or **null** — the honest answer for a latitude and
 * date where the crossing simply does not happen. Callers must not substitute
 * a default: a polar summer has no sunset, and saying one exists is a lie the
 * caller cannot detect afterwards.
 */
internal fun nextCrossing(
    fromMs: Long,
    limitMs: Long,
    targetDeg: Double,
    rising: Boolean,
    altitudeAt: (Long) -> Double,
): Long? {
    var lo = fromMs
    var loDelta = altitudeAt(lo) - targetDeg
    while (lo < limitMs) {
        val hi = minOf(lo + SCAN_STEP_MS, limitMs)
        val hiDelta = altitudeAt(hi) - targetDeg
        val crossed =
            if (rising) loDelta <= 0.0 && hiDelta > 0.0 else loDelta >= 0.0 && hiDelta < 0.0
        if (crossed) return bisect(lo, hi, targetDeg, rising, altitudeAt)
        lo = hi
        loDelta = hiDelta
    }
    return null
}

private fun bisect(
    fromMs: Long,
    toMs: Long,
    targetDeg: Double,
    rising: Boolean,
    altitudeAt: (Long) -> Double,
): Long {
    var lo = fromMs
    var hi = toMs
    while (hi - lo > 1L) {
        val mid = lo + (hi - lo) / 2
        val delta = altitudeAt(mid) - targetDeg
        val beforeCrossing = if (rising) delta <= 0.0 else delta >= 0.0
        if (beforeCrossing) lo = mid else hi = mid
    }
    return hi
}

/** Time of greatest altitude inside the window — solar noon, or lunar transit. */
internal fun culmination(
    fromMs: Long,
    toMs: Long,
    altitudeAt: (Long) -> Double,
): Long {
    var best = fromMs
    var bestAltitude = altitudeAt(fromMs)
    var t = fromMs
    while (t < toMs) {
        t = minOf(t + SCAN_STEP_MS, toMs)
        val altitude = altitudeAt(t)
        if (altitude > bestAltitude) {
            bestAltitude = altitude
            best = t
        }
        if (t >= toMs) break
    }
    var lo = maxOf(fromMs, best - SCAN_STEP_MS)
    var hi = minOf(toMs, best + SCAN_STEP_MS)
    while (hi - lo > 1000L) {
        val third = (hi - lo) / 3
        val a = lo + third
        val b = hi - third
        if (altitudeAt(a) < altitudeAt(b)) lo = a else hi = b
    }
    return (lo + hi) / 2
}
