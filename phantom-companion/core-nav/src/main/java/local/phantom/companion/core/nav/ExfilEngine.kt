package local.phantom.companion.core.nav

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * NAV-Ω2 — Exfil Route Engine («Вивези мене звідси»).
 *
 * Computes an urgent egress target coordinate directed away from the nearest
 * threat centroid (air strike zone, combat marker, active EW jammer, or hazard cluster).
 */
object ExfilEngine {

    private const val EARTH_RADIUS_M = 6_371_000.0
    const val DEFAULT_ESCAPE_DISTANCE_M = 4000.0 // 4 km escape radius

    data class ExfilTarget(
        val targetLat: Double,
        val targetLon: Double,
        val escapeBearingDeg: Float,
        val nearestThreatDistanceM: Double,
        val explanationUk: String,
    )

    /**
     * Computes the optimal escape coordinate away from danger.
     *
     * @param operatorLat        Current latitude of the user.
     * @param operatorLon        Current longitude of the user.
     * @param threats            List of active threat positions (lat, lon, label).
     * @param safeAnchors        Optional list of known safe shelters / destinations.
     * @param desiredDistanceM   How far to escape (in meters).
     */
    fun computeExfilTarget(
        operatorLat: Double,
        operatorLon: Double,
        threats: List<Triple<Double, Double, String>> = emptyList(),
        safeAnchors: List<Pair<Double, Double>> = emptyList(),
        desiredDistanceM: Double = DEFAULT_ESCAPE_DISTANCE_M,
    ): ExfilTarget {
        if (threats.isEmpty()) {
            // No direct threats: escape towards nearest known safe anchor or north default
            val nearestAnchor = safeAnchors.minByOrNull {
                haversineM(operatorLat, operatorLon, it.first, it.second)
            }
            if (nearestAnchor != null) {
                val bearing = bearingDeg(operatorLat, operatorLon, nearestAnchor.first, nearestAnchor.second)
                val dist = haversineM(operatorLat, operatorLon, nearestAnchor.first, nearestAnchor.second)
                return ExfilTarget(
                    targetLat = nearestAnchor.first,
                    targetLon = nearestAnchor.second,
                    escapeBearingDeg = bearing.toFloat(),
                    nearestThreatDistanceM = Double.MAX_VALUE,
                    explanationUk = "Прямий вихід до найближчого безпечного укриття (${(dist / 1000.0).formatOneDecimal()} км).",
                )
            }

            // Fallback: 3 km north-west vector
            val fallback = destinationPoint(operatorLat, operatorLon, 315.0, desiredDistanceM)
            return ExfilTarget(
                targetLat = fallback.first,
                targetLon = fallback.second,
                escapeBearingDeg = 315f,
                nearestThreatDistanceM = Double.MAX_VALUE,
                explanationUk = "Екстрений відхід у безпечному напрямку (315° Пн-Зх, ${(desiredDistanceM / 1000.0).formatOneDecimal()} км).",
            )
        }

        // 1. Find closest threat
        val closest = threats.minBy { haversineM(operatorLat, operatorLon, it.first, it.second) }
        val threatDist = haversineM(operatorLat, operatorLon, closest.first, closest.second)

        // 2. Escape vector: vector pointing FROM threat THROUGH operator into safety
        val escapeBearing = if (threatDist < 10.0) {
            // Right on top of the threat: flee north
            0.0
        } else {
            bearingDeg(closest.first, closest.second, operatorLat, operatorLon)
        }

        // 3. Project egress point
        val projected = destinationPoint(operatorLat, operatorLon, escapeBearing, desiredDistanceM)

        // 4. Check if any safe anchor lies within ±45° cone of the escape vector
        val candidateAnchor = safeAnchors.filter {
            val anchorBearing = bearingDeg(operatorLat, operatorLon, it.first, it.second)
            val angleDiff = Math.abs(normalizeAngle(anchorBearing - escapeBearing))
            val dist = haversineM(operatorLat, operatorLon, it.first, it.second)
            angleDiff <= 45.0 && dist in 500.0..(desiredDistanceM * 1.5)
        }.minByOrNull { haversineM(operatorLat, operatorLon, it.first, it.second) }

        return if (candidateAnchor != null) {
            val dist = haversineM(operatorLat, operatorLon, candidateAnchor.first, candidateAnchor.second)
            ExfilTarget(
                targetLat = candidateAnchor.first,
                targetLon = candidateAnchor.second,
                escapeBearingDeg = bearingDeg(operatorLat, operatorLon, candidateAnchor.first, candidateAnchor.second).toFloat(),
                nearestThreatDistanceM = threatDist,
                explanationUk = "Маршрут геть від позначки «${closest.third}» (${(threatDist / 1000.0).formatOneDecimal()} км) у безпечне укриття (${(dist / 1000.0).formatOneDecimal()} км).",
            )
        } else {
            ExfilTarget(
                targetLat = projected.first,
                targetLon = projected.second,
                escapeBearingDeg = escapeBearing.toFloat(),
                nearestThreatDistanceM = threatDist,
                explanationUk = "Екстрений відхід геть від «${closest.third}» (азимут ${escapeBearing.toInt()}°, ${(desiredDistanceM / 1000.0).formatOneDecimal()} км).",
            )
        }
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lon2 - lon1)
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        val deg = Math.toDegrees(atan2(y, x))
        return (deg + 360.0) % 360.0
    }

    private fun destinationPoint(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val δ = distanceM / EARTH_RADIUS_M
        val θ = Math.toRadians(bearingDeg)
        val φ1 = Math.toRadians(lat)
        val λ1 = Math.toRadians(lon)

        val sinφ2 = sin(φ1) * cos(δ) + cos(φ1) * sin(δ) * cos(θ)
        val φ2 = asin(sinφ2)
        val y = sin(θ) * sin(δ) * cos(φ1)
        val x = cos(δ) - sin(φ1) * sin(φ2)
        val λ2 = λ1 + atan2(y, x)

        return Math.toDegrees(φ2) to ((Math.toDegrees(λ2) + 540.0) % 360.0 - 180.0)
    }

    private fun normalizeAngle(angle: Double): Double {
        var a = angle % 360.0
        if (a > 180.0) a -= 360.0
        if (a < -180.0) a += 360.0
        return a
    }

    private fun Double.formatOneDecimal(): String = String.format(java.util.Locale.US, "%.1f", this)
}
