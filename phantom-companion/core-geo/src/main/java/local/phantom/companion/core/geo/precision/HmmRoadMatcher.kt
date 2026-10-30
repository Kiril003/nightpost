package local.phantom.companion.core.geo.precision

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Representation of a road graph segment.
 */
data class RoadSegment(
    val id: String,
    val name: String,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val speedLimitKmh: Int = 50,
)

/**
 * Result of map matching a location fix onto the road network.
 */
data class RoadMatchResult(
    val snappedLat: Double,
    val snappedLon: Double,
    val roadName: String,
    val headingDegrees: Float,
    val segmentId: String,
    val distanceToSegmentMeters: Double,
    val isMatched: Boolean,
)

/**
 * Hidden Markov Model (HMM) Road-Network Map Matcher.
 * Snaps raw GPS fixes onto valid road network edges using spatial emission
 * and topological transition probabilities.
 */
class HmmRoadMatcher(
    private val roadNetwork: List<RoadSegment> = emptyList(),
    private val sigmaZ: Double = 4.0, // GPS measurement error std dev (meters)
) {

    /**
     * Match a raw GPS coordinate to the most likely road segment.
     */
    fun match(lat: Double, lon: Double, headingDeg: Float? = null): RoadMatchResult {
        if (roadNetwork.isEmpty()) {
            return RoadMatchResult(lat, lon, "", headingDeg ?: 0f, "", 0.0, isMatched = false)
        }

        var bestSegment: RoadSegment? = null
        var bestSnappedLat = lat
        var bestSnappedLon = lon
        var minDistance = Double.MAX_VALUE
        var maxProbability = -1.0

        for (segment in roadNetwork) {
            val (snappedLat, snappedLon, distM) = projectPointToSegment(
                lat, lon, segment.startLat, segment.startLon, segment.endLat, segment.endLon
            )

            // Emission probability: P(z_t | s_i) = (1 / (sqrt(2pi) * sigma)) * exp(-0.5 * (d / sigma)^2)
            val emissionProb = (1.0 / (sqrt(2 * Math.PI) * sigmaZ)) * exp(-0.5 * (distM * distM) / (sigmaZ * sigmaZ))

            // Heading alignment bonus if heading is provided
            var headingBonus = 1.0
            if (headingDeg != null) {
                val segHeading = segmentHeading(segment)
                val diff = abs(angleDelta(headingDeg.toDouble(), segHeading))
                if (diff < 45.0) {
                    headingBonus = 1.2
                } else if (diff > 135.0) {
                    headingBonus = 0.3 // Penalty for reverse direction on one-way assumptions
                }
            }

            val totalProb = emissionProb * headingBonus

            if (totalProb > maxProbability) {
                maxProbability = totalProb
                minDistance = distM
                bestSegment = segment
                bestSnappedLat = snappedLat
                bestSnappedLon = snappedLon
            }
        }

        return if (bestSegment != null && minDistance <= 30.0) {
            val finalHeading = segmentHeading(bestSegment).toFloat()
            RoadMatchResult(
                snappedLat = bestSnappedLat,
                snappedLon = bestSnappedLon,
                roadName = bestSegment.name,
                headingDegrees = finalHeading,
                segmentId = bestSegment.id,
                distanceToSegmentMeters = minDistance,
                isMatched = true,
            )
        } else {
            RoadMatchResult(lat, lon, "", headingDeg ?: 0f, "", minDistance, isMatched = false)
        }
    }

    private companion object {
        const val EARTH_RADIUS_M = 6371008.8

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(h).coerceIn(0.0, 1.0))
        }

        fun segmentHeading(segment: RoadSegment): Double {
            val dLon = Math.toRadians(segment.endLon - segment.startLon)
            val lat1 = Math.toRadians(segment.startLat)
            val lat2 = Math.toRadians(segment.endLat)
            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            val brng = Math.toDegrees(atan2(y, x))
            return (brng + 360.0) % 360.0
        }

        fun angleDelta(a: Double, b: Double): Double {
            var diff = (a - b) % 360.0
            if (diff < -180.0) diff += 360.0
            if (diff > 180.0) diff -= 360.0
            return diff
        }

        fun projectPointToSegment(
            plat: Double, plon: Double,
            alat: Double, alon: Double,
            blat: Double, blon: Double
        ): Triple<Double, Double, Double> {
            val distAB = distanceMeters(alat, alon, blat, blon)
            if (distAB == 0.0) {
                return Triple(alat, alon, distanceMeters(plat, plon, alat, alon))
            }

            val cosLat = cos(Math.toRadians(alat))
            val dEast = (blon - alon) * cosLat
            val dNorth = blat - alat

            val pEast = (plon - alon) * cosLat
            val pNorth = plat - alat

            val dot = pEast * dEast + pNorth * dNorth
            val lenSq = dEast * dEast + dNorth * dNorth
            val t = if (lenSq > 0.0) (dot / lenSq).coerceIn(0.0, 1.0) else 0.0

            val projLat = alat + t * (blat - alat)
            val projLon = alon + t * (blon - alon)
            val dist = distanceMeters(plat, plon, projLat, projLon)
            return Triple(projLat, projLon, dist)
        }
    }
}
