package local.phantom.companion.core.nav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * NAV-Ω2 (M-H01) — Breadcrumb Tracker and Retrace Route Synthesizer.
 *
 * Silently and locally records accepted navigation-grade fixes in a bounded ring
 * buffer (up to [MAX_POINTS] or [MAX_AGE_MS]). Allows the operator to request
 * "Retrace footsteps" («виведи мене назад моїм шляхом»), reversing the exact path
 * traveled with Ramer-Douglas-Peucker smoothing and synthesizing turn-by-turn maneuvers.
 */
class BreadcrumbTracker(
    private val maxPoints: Int = MAX_POINTS,
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val minStepM: Double = MIN_RECORD_INTERVAL_M,
) {
    data class Point(
        val lat: Double,
        val lon: Double,
        val timeMs: Long,
        val accuracyM: Float?,
    )

    private val points = ArrayDeque<Point>()

    @Synchronized
    fun record(lat: Double, lon: Double, timeMs: Long, accuracyM: Float? = 5f): Boolean {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return false
        if (accuracyM != null && accuracyM > MAX_ACCEPTABLE_ACCURACY_M) return false

        val last = points.lastOrNull()
        if (last != null) {
            val dist = haversineM(last.lat, last.lon, lat, lon)
            if (dist < minStepM) return false
        }

        pruneOld(timeMs)

        if (points.size >= maxPoints) {
            points.removeFirst()
        }

        points.addLast(Point(lat, lon, timeMs, accuracyM))
        return true
    }

    @Synchronized
    fun clear() {
        points.clear()
    }

    @Synchronized
    fun pointCount(): Int = points.size

    @Synchronized
    fun totalDistanceM(): Double {
        if (points.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until points.size) {
            sum += haversineM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        }
        return sum
    }

    @Synchronized
    fun getTrack(): List<Point> = points.toList()

    /**
     * Builds a reverse route from the current location back to the earliest recorded point.
     * Applies RDP polyline simplification to eliminate GPS jitter.
     *
     * @param epsilonM Tolerance in meters for Douglas-Peucker simplification.
     * @return List of coordinates (lat, lon) in reverse order, or null if insufficient history.
     */
    @Synchronized
    fun buildRetraceRoute(epsilonM: Double = DEFAULT_SIMPLIFY_EPSILON_M): List<Pair<Double, Double>>? {
        if (points.size < 2) return null
        val raw = points.map { it.lat to it.lon }
        val simplified = simplifyRdp(raw, epsilonM)
        if (simplified.size < 2) return null
        return simplified.reversed()
    }

    private fun pruneOld(nowMs: Long) {
        val cutoff = nowMs - maxAgeMs
        while (points.isNotEmpty() && points.first().timeMs < cutoff) {
            points.removeFirst()
        }
    }

    companion object {
        const val MAX_POINTS = 4096
        const val MAX_AGE_MS = 12 * 3600 * 1000L // 12 hours
        const val MIN_RECORD_INTERVAL_M = 4.0
        const val MAX_ACCEPTABLE_ACCURACY_M = 25f
        const val DEFAULT_SIMPLIFY_EPSILON_M = 4.0

        private const val EARTH_RADIUS_M = 6_371_000.0

        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return EARTH_RADIUS_M * c
        }

        /**
         * Ramer-Douglas-Peucker polyline simplification algorithm on geographic coordinates.
         */
        fun simplifyRdp(pts: List<Pair<Double, Double>>, epsilonM: Double): List<Pair<Double, Double>> {
            if (pts.size <= 2) return pts

            var maxDist = 0.0
            var maxIndex = 0
            val start = pts.first()
            val end = pts.last()

            for (i in 1 until pts.size - 1) {
                val dist = perpendicularDistanceM(pts[i], start, end)
                if (dist > maxDist) {
                    maxDist = dist
                    maxIndex = i
                }
            }

            return if (maxDist > epsilonM) {
                val left = simplifyRdp(pts.subList(0, maxIndex + 1), epsilonM)
                val right = simplifyRdp(pts.subList(maxIndex, pts.size), epsilonM)
                left.dropLast(1) + right
            } else {
                listOf(start, end)
            }
        }

        private fun perpendicularDistanceM(
            p: Pair<Double, Double>,
            lineStart: Pair<Double, Double>,
            lineEnd: Pair<Double, Double>,
        ): Double {
            val lDist = haversineM(lineStart.first, lineStart.second, lineEnd.first, lineEnd.second)
            if (lDist < 1e-6) return haversineM(p.first, p.second, lineStart.first, lineStart.second)

            val x = (p.second - lineStart.second) * cos(Math.toRadians((lineStart.first + lineEnd.first) / 2))
            val y = p.first - lineStart.first
            val dx = (lineEnd.second - lineStart.second) * cos(Math.toRadians((lineStart.first + lineEnd.first) / 2))
            val dy = lineEnd.first - lineStart.first

            val num = abs(dy * x - dx * y)
            val den = sqrt(dx * dx + dy * dy)
            val degDist = if (den > 0) num / den else 0.0
            return degDist * 111_319.5
        }
    }
}
