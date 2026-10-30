package local.phantom.companion.core.nav

import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteStep
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WAVE 3.2 — Maneuver synthesis for offline mesh routes.
 *
 * When routing offline over the on-device road mesh (or whenever OSRM is
 * unavailable), this synthesizer analyzes the geometric polyline and road names
 * to emit genuine [RouteStep] maneuvers (depart, turn, arrive) with honest turn
 * angles and compass bearings.
 */
object OfflineManeuverSynthesizer {

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val MIN_TURN_ANGLE_DEG = 18.0f
    private const val FOOT_SPEED_MPS = 1.3
    private const val CAR_SPEED_MPS = 7.5

    /**
     * Ті самі 4,2 м/с ≈ 15 км/год, що й
     * `LocalRouter.BIKE_SPEED_MPS`, і з тієї самої причини: це базовий темп
     * публічного `routed-bike`, на який вказує [RouteProfile.BIKE]. Число
     * продубльовано, а не взято посиланням, бо `core-nav` не бачить
     * `core-sensor`.
     */
    private const val BIKE_SPEED_MPS = 4.2

    /**
     * [profile] вирішує темп, яким підписані кроки. Раніше тут стояв булевий
     * `footProfile`, тож велосипедний маршрут діставав автомобільні 7,5 м/с —
     * майже вдвічі швидше за реальність, і то в підказках, за якими людина
     * вирішує, коли повертати.
     */
    fun synthesize(
        points: List<Pair<Double, Double>>,
        roadNames: List<String?> = emptyList(),
        profile: RouteProfile = RouteProfile.CAR,
    ): List<RouteStep> {
        if (points.size < 2) return emptyList()

        val speed = when (profile) {
            RouteProfile.FOOT -> FOOT_SPEED_MPS
            RouteProfile.BIKE -> BIKE_SPEED_MPS
            RouteProfile.CAR, RouteProfile.TRANSIT -> CAR_SPEED_MPS
        }
        val steps = mutableListOf<RouteStep>()

        val firstBearing = bearingDegrees(points[0], points[1])
        val initialName = roadNames.firstOrNull()?.trim().orEmpty()

        // 1. Depart
        steps += RouteStep(
            type = "depart",
            modifier = null,
            roadName = initialName,
            distanceMeters = 0.0,
            durationSeconds = 0.0,
            lat = points[0].first,
            lon = points[0].second,
            exitNumber = null,
            turnAngleDeg = 0f,
            bearingAfterDeg = firstBearing,
        )

        var accumulatedDist = 0.0

        // 2. Intermediate vertices
        for (i in 1 until points.size - 1) {
            val pPrev = points[i - 1]
            val pCurr = points[i]
            val pNext = points[i + 1]

            val segDist = haversineM(pPrev, pCurr)
            accumulatedDist += segDist

            val bIn = bearingDegrees(pPrev, pCurr)
            val bOut = bearingDegrees(pCurr, pNext)
            val turnAngle = signedAngleDiff(bIn, bOut)

            val currName = roadNames.getOrNull(i)?.trim().orEmpty()
            val prevName = roadNames.getOrNull(i - 1)?.trim().orEmpty()
            val nameChanged = currName.isNotEmpty() && prevName.isNotEmpty() && !currName.equals(prevName, ignoreCase = true)

            if (kotlin.math.abs(turnAngle) >= MIN_TURN_ANGLE_DEG || (nameChanged && kotlin.math.abs(turnAngle) >= 10f)) {
                val modifier = modifierForAngle(turnAngle)
                val duration = accumulatedDist / speed
                steps += RouteStep(
                    type = "turn",
                    modifier = modifier,
                    roadName = currName.ifEmpty { prevName },
                    distanceMeters = accumulatedDist,
                    durationSeconds = duration,
                    lat = pCurr.first,
                    lon = pCurr.second,
                    exitNumber = null,
                    turnAngleDeg = turnAngle,
                    bearingAfterDeg = bOut,
                )
                accumulatedDist = 0.0
            }
        }

        // 3. Arrive
        val finalDist = accumulatedDist + haversineM(points[points.size - 2], points.last())
        val lastName = roadNames.lastOrNull()?.trim().orEmpty()

        steps += RouteStep(
            type = "arrive",
            modifier = null,
            roadName = lastName,
            distanceMeters = finalDist,
            durationSeconds = finalDist / speed,
            lat = points.last().first,
            lon = points.last().second,
            exitNumber = null,
            turnAngleDeg = 0f,
            bearingAfterDeg = null,
        )

        return steps
    }

    private fun modifierForAngle(angle: Float): String = when {
        angle in -180f..-135f || angle in 135f..180f -> "uturn"
        angle in -135f..-55f -> "left"
        angle in -55f..-MIN_TURN_ANGLE_DEG -> "slight left"
        angle in MIN_TURN_ANGLE_DEG..55f -> "slight right"
        angle in 55f..135f -> "right"
        else -> "straight"
    }

    private fun bearingDegrees(from: Pair<Double, Double>, to: Pair<Double, Double>): Float {
        val lat1 = Math.toRadians(from.first)
        val lat2 = Math.toRadians(to.first)
        val dLon = Math.toRadians(to.second - from.second)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val rad = atan2(y, x)
        return ((Math.toDegrees(rad) + 360.0) % 360.0).toFloat()
    }

    private fun signedAngleDiff(fromDeg: Float, toDeg: Float): Float {
        var diff = (toDeg - fromDeg) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    private fun haversineM(p1: Pair<Double, Double>, p2: Pair<Double, Double>): Double {
        val dLat = Math.toRadians(p2.first - p1.first)
        val dLon = Math.toRadians(p2.second - p1.second)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(p1.first)) * cos(Math.toRadians(p2.first)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
