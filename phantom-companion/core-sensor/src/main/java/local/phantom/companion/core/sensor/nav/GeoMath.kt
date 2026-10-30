package local.phantom.companion.core.sensor.nav

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal const val EARTH_RADIUS_M = 6_371_000.0

internal fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/**
 * Metres → degrees, on a local tangent plane. The engine's whole filter runs
 * in metres east/north of a session origin: a Kalman filter cannot be asked
 * to reason in degrees, where a metre is worth a different number depending
 * on where you are standing.
 */
internal fun metersToLat(m: Double): Double = Math.toDegrees(m / EARTH_RADIUS_M)

internal fun metersToLon(m: Double, atLat: Double): Double =
    Math.toDegrees(m / (EARTH_RADIUS_M * cos(Math.toRadians(atLat)).coerceAtLeast(1e-6)))

internal fun latToMeters(dLatDeg: Double): Double = Math.toRadians(dLatDeg) * EARTH_RADIUS_M

internal fun lonToMeters(dLonDeg: Double, atLat: Double): Double =
    Math.toRadians(dLonDeg) * EARTH_RADIUS_M * cos(Math.toRadians(atLat))

/** Compass bearing (0…360, north-up) of a velocity in metres/second east+north. */
internal fun bearingOf(vEast: Double, vNorth: Double): Float {
    val deg = Math.toDegrees(atan2(vEast, vNorth))
    return ((deg + 360.0) % 360.0).toFloat()
}
