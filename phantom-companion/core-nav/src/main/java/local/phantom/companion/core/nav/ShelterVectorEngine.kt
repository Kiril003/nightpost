package local.phantom.companion.core.nav

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * M-N01 — Tactical Shelter Vector & Emergency Bearing Indicator.
 *
 * Computes exact vector parameters to the nearest civil defense shelter:
 * - Direct distance in meters and walking/running ETA.
 * - True bearing (0..360°) and Ukrainian compass quadrant.
 * - Relative clock direction (e.g. "на 2 години") relative to the operator's forward heading.
 */
class ShelterVectorEngine {

    data class ShelterTarget(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val type: String,
        val address: String? = null,
    )

    data class VectorGuidance(
        val target: ShelterTarget,
        val distanceMeters: Int,
        val bearingDeg: Int,
        val compassDirectionUk: String,
        val clockPositionUk: String?,
        val walkEtaMinutes: Int,
        val runEtaMinutes: Int,
        val instructionUk: String,
    )

    /**
     * Calculates the vector to the target shelter relative to current position and optional heading.
     */
    fun calculateVector(
        currentLat: Double,
        currentLon: Double,
        currentHeadingDeg: Float? = null,
        target: ShelterTarget,
    ): VectorGuidance {
        val dist = haversineMeters(currentLat, currentLon, target.lat, target.lon).roundToInt()
        val bearing = calculateBearing(currentLat, currentLon, target.lat, target.lon)
        val compassUk = compassQuadrantUk(bearing)

        val clockUk = currentHeadingDeg?.let { heading ->
            calculateClockPosition(heading.toDouble(), bearing.toDouble())
        }

        val walkMin = ((dist / (4.5 * 1000 / 60)).roundToInt()).coerceAtLeast(1)
        val runMin = ((dist / (11.0 * 1000 / 60)).roundToInt()).coerceAtLeast(1)

        val directionStr = if (clockUk != null) "$compassUk ($clockUk)" else compassUk
        val instruction = "Найближче укриття: ${target.name} (${target.type}). Дистанція: $dist м, напрямок $directionStr. Пішки: ~$walkMin хв, бігом: ~$runMin хв."

        return VectorGuidance(
            target = target,
            distanceMeters = dist,
            bearingDeg = bearing,
            compassDirectionUk = compassUk,
            clockPositionUk = clockUk,
            walkEtaMinutes = walkMin,
            runEtaMinutes = runMin,
            instructionUk = instruction,
        )
    }

    internal fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)
        val bearing = (Math.toDegrees(theta) + 360.0) % 360.0
        return bearing.roundToInt()
    }

    internal fun compassQuadrantUk(bearingDeg: Int): String = when (((bearingDeg + 22) % 360) / 45) {
        0 -> "Північ"
        1 -> "Північний Схід"
        2 -> "Схід"
        3 -> "Південний Схід"
        4 -> "Південь"
        5 -> "Південний Захід"
        6 -> "Захід"
        7 -> "Північний Захід"
        else -> "Північ"
    }

    internal fun calculateClockPosition(headingDeg: Double, bearingDeg: Double): String {
        val diff = ((bearingDeg - headingDeg + 360.0) % 360.0)
        val hour = (((diff + 15.0) / 30.0).toInt() % 12).let { if (it == 0) 12 else it }
        return "на $hour годин"
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
    }
}
