package local.phantom.companion.core.nav

import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * NAV-2 — pure route-geometry math behind live guidance: cumulative
 * distance/duration tables over the OSRM polyline, GPS→polyline snapping
 * (map-matching), and each maneuver's position along the corridor. No
 * Android, no MapLibre — everything here runs in JVM unit tests.
 */

/** One GPS fix snapped onto the route polyline. */
data class RouteSnap(
    val lat: Double,
    val lon: Double,
    /** Metres travelled from the route start to the snapped point. */
    val alongM: Double,
    /** Metres between the raw fix and the snapped point — the off-route distance. */
    val offMeters: Double,
    /** Travel direction of the matched segment, degrees clockwise from north. */
    val bearingDeg: Double,
    val segIndex: Int,
)

/**
 * The guidance-ready view of one [RouteVariant]: the polyline with
 * precomputed cumulative distances, cumulative durations (from OSRM's
 * per-segment speed annotations when present, distance-proportional
 * otherwise) and the along-distance of every step's maneuver point.
 */
class RoutePath private constructor(
    val points: List<Pair<Double, Double>>,
    val cumDistM: DoubleArray,
    val cumDurS: DoubleArray,
    /** Distance-along of each OSRM step's maneuver, non-decreasing. */
    val stepAlongM: DoubleArray,
) {
    val totalDistM: Double get() = cumDistM.last()
    val totalDurS: Double get() = cumDurS.last()

    fun snap(lat: Double, lon: Double): RouteSnap = snapToPolyline(points, cumDistM, lat, lon)

    /**
     * Snap constrained to a window of the corridor around [nearAlongM] —
     * the map-matcher's memory. A global nearest-point search teleports on
     * any route that passes near itself (out-and-back streets, loops): the
     * fix matches the RETURN leg and the banner jumps kilometres ahead.
     * Guidance therefore searches only [nearAlongM − backM, nearAlongM +
     * aheadM] and lets progress be continuous, the way every real
     * navigator matches.
     */
    fun snapNear(
        lat: Double,
        lon: Double,
        nearAlongM: Double,
        backM: Double,
        aheadM: Double,
    ): RouteSnap {
        val fromSeg = segIndexAt((nearAlongM - backM).coerceAtLeast(0.0))
        val toSeg = segIndexAt((nearAlongM + aheadM).coerceAtMost(totalDistM)) + 1
        return snapToPolyline(points, cumDistM, lat, lon, fromSeg, toSeg)
    }

    /**
     * HORIZON wave 2 — |heading change| per metre of the corridor just
     * ahead of [alongM], over at most [windowM]. The camera director eats
     * this: a bend ahead flattens the tilt and shortens the look-ahead so
     * the apex stays in frame. Zero-length segments (duplicate polyline
     * points) are skipped — they carry no direction.
     */
    fun curvatureAheadDegPerM(alongM: Double, windowM: Double): Double {
        if (points.size < 3 || windowM <= 0.0) return 0.0
        val startM = alongM.coerceIn(0.0, totalDistM)
        val endM = (startM + windowM).coerceAtMost(totalDistM)
        var turnDeg = 0.0
        var prevBearing: Double? = null
        var i = segIndexAt(startM)
        while (i < points.size - 1 && cumDistM[i] < endM) {
            if (cumDistM[i + 1] - cumDistM[i] > 0.5) {
                val b = bearingDeg(
                    points[i].first, points[i].second,
                    points[i + 1].first, points[i + 1].second,
                )
                prevBearing?.let { pb ->
                    turnDeg += kotlin.math.abs(((b - pb + 540.0) % 360.0) - 180.0)
                }
                prevBearing = b
            }
            i++
        }
        return turnDeg / (endM - startM).coerceAtLeast(1.0)
    }

    /**
     * The corridor point exactly [alongM] metres from the start, carrying the
     * bearing of the segment it sits on. `offMeters` is 0 by construction —
     * this point IS the polyline. [RouteCoastingEngine] walks the corridor
     * with it while the sky is gone.
     */
    fun pointAtM(alongM: Double): RouteSnap {
        val m = alongM.coerceIn(0.0, totalDistM)
        val seg = segIndexAt(m).coerceIn(0, points.size - 2)
        val segLen = cumDistM[seg + 1] - cumDistM[seg]
        val t = if (segLen <= 0.0) 0.0 else ((m - cumDistM[seg]) / segLen).coerceIn(0.0, 1.0)
        val (aLat, aLon) = points[seg]
        val (bLat, bLon) = points[seg + 1]
        return RouteSnap(
            lat = aLat + (bLat - aLat) * t,
            lon = aLon + (bLon - aLon) * t,
            alongM = m,
            offMeters = 0.0,
            bearingDeg = bearingDeg(aLat, aLon, bLat, bLon),
            segIndex = seg,
        )
    }

    /** Segment index whose [cumDistM] span contains [alongM]. */
    private fun segIndexAt(alongM: Double): Int {
        var lo = 0
        var hi = cumDistM.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (cumDistM[mid] <= alongM) lo = mid else hi = mid
        }
        return lo
    }

    /** Seconds of travel from the start to [alongM], linear inside a segment. */
    fun durationAtM(alongM: Double): Double {
        if (alongM <= 0.0) return 0.0
        if (alongM >= totalDistM) return totalDurS
        var lo = 0
        var hi = cumDistM.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (cumDistM[mid] <= alongM) lo = mid else hi = mid
        }
        val segLen = cumDistM[hi] - cumDistM[lo]
        if (segLen <= 0.0) return cumDurS[lo]
        val t = (alongM - cumDistM[lo]) / segLen
        return cumDurS[lo] + (cumDurS[hi] - cumDurS[lo]) * t
    }

    /**
     * Index (into the variant's steps) of the maneuver the operator is
     * approaching at [alongM]. Past the last maneuver → the arrive step.
     */
    fun nextStepIndex(alongM: Double): Int {
        for (i in stepAlongM.indices) {
            if (stepAlongM[i] > alongM + 2.0) return i
        }
        return (stepAlongM.size - 1).coerceAtLeast(0)
    }

    companion object {
        /** null when the variant has no usable geometry (honest failure). */
        fun of(variant: RouteVariant): RoutePath? {
            val pts = variant.points
            if (pts.size < 2) return null

            val cum = DoubleArray(pts.size)
            for (i in 1 until pts.size) {
                cum[i] = cum[i - 1] + haversineM(pts[i - 1], pts[i])
            }
            if (cum.last() <= 0.0) return null

            // Durations: per-segment speed annotations give real mixed-speed
            // ETAs; normalised to OSRM's own total so the strip and the
            // banner never disagree about the same route.
            val cumDur = DoubleArray(pts.size)
            val speeds = variant.speedsMps
            if (speeds.size == pts.size - 1) {
                for (i in 1 until pts.size) {
                    val v = speeds[i - 1].coerceAtLeast(0.5)
                    cumDur[i] = cumDur[i - 1] + (cum[i] - cum[i - 1]) / v
                }
                val raw = cumDur.last()
                if (raw > 0.0) {
                    val scale = variant.durationSeconds / raw
                    for (i in cumDur.indices) cumDur[i] *= scale
                }
            } else {
                val total = cum.last()
                for (i in cumDur.indices) cumDur[i] = variant.durationSeconds * (cum[i] / total)
            }

            // Each step's maneuver point, snapped forward-only so the
            // sequence stays monotonic even where the route self-overlaps.
            val stepAlong = DoubleArray(variant.steps.size)
            var fromSeg = 0
            variant.steps.forEachIndexed { i, s ->
                val snap = snapToPolyline(pts, cum, s.lat, s.lon, fromSeg)
                stepAlong[i] = if (i > 0) maxOf(snap.alongM, stepAlong[i - 1]) else snap.alongM
                fromSeg = snap.segIndex
            }

            return RoutePath(pts, cum, cumDur, stepAlong)
        }
    }
}

/**
 * Nearest point of the polyline to (lat, lon), searching segments from
 * [fromSeg] until [toSegExclusive] (≤0 → the whole tail). Equirectangular
 * per-segment projection — exact enough at the ≤100 m scales snapping
 * cares about.
 */
fun snapToPolyline(
    points: List<Pair<Double, Double>>,
    cumDistM: DoubleArray,
    lat: Double,
    lon: Double,
    fromSeg: Int = 0,
    toSegExclusive: Int = 0,
): RouteSnap {
    val end = if (toSegExclusive <= 0) points.size - 1 else toSegExclusive.coerceAtMost(points.size - 1)
    var bestD = Double.MAX_VALUE
    var bestSeg = fromSeg.coerceIn(0, points.size - 2)
    var bestT = 0.0
    for (i in fromSeg.coerceAtLeast(0) until end) {
        val (aLat, aLon) = points[i]
        val (bLat, bLon) = points[i + 1]
        val kx = cos(Math.toRadians((aLat + bLat) / 2)) * 111_320.0
        val ky = 110_540.0
        val px = (lon - aLon) * kx
        val py = (lat - aLat) * ky
        val bx = (bLon - aLon) * kx
        val by = (bLat - aLat) * ky
        val len2 = bx * bx + by * by
        val t = if (len2 <= 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - bx * t
        val dy = py - by * t
        val d = sqrt(dx * dx + dy * dy)
        if (d < bestD) {
            bestD = d
            bestSeg = i
            bestT = t
        }
    }
    val (aLat, aLon) = points[bestSeg]
    val (bLat, bLon) = points[bestSeg + 1]
    val sLat = aLat + (bLat - aLat) * bestT
    val sLon = aLon + (bLon - aLon) * bestT
    val segLen = cumDistM[bestSeg + 1] - cumDistM[bestSeg]
    return RouteSnap(
        lat = sLat,
        lon = sLon,
        alongM = cumDistM[bestSeg] + segLen * bestT,
        offMeters = bestD,
        bearingDeg = bearingDeg(aLat, aLon, bLat, bLon),
        segIndex = bestSeg,
    )
}

/** What the body is actually doing, as the kinematic classifier sees it. */
enum class NavGait { ON_FOOT, CYCLING, DRIVING, UNKNOWN }

/** Everything about guidance that depends on how fast the body moves. */
data class NavPace(
    val tierFloorsM: List<Double>,
    val tierLeadsS: List<Double>,
    val offRouteM: Double,
    val offRouteSustainMs: Long,
    val arriveRadiusM: Double,
    /** What this body actually holds over a city kilometre. Null = trust the
     *  planner's own model (traffic is OSRM's job, not a constant's). */
    val nominalSpeedMps: Double?,
)

val PACE_FOOT = NavPace(
    tierFloorsM = listOf(65.0, 20.0),
    tierLeadsS = listOf(40.0, 12.0),
    offRouteM = 55.0,
    offRouteSustainMs = 8_000L,
    arriveRadiusM = 15.0,
    // The pedestrian fix this device reports, crossings and all.
    nominalSpeedMps = 1.3,
)

val PACE_BIKE = NavPace(
    tierFloorsM = listOf(150.0, 35.0),
    tierLeadsS = listOf(30.0, 10.0),
    offRouteM = 40.0,
    offRouteSustainMs = 4_000L,
    arriveRadiusM = 20.0,
    nominalSpeedMps = 4.2,
)

val PACE_CAR = NavPace(
    tierFloorsM = listOf(600.0, 250.0, 60.0),
    tierLeadsS = listOf(40.0, 18.0, 8.0),
    offRouteM = 45.0,
    offRouteSustainMs = 2_000L,
    arriveRadiusM = 25.0,
    nominalSpeedMps = null,
)

/**
 * Seconds still to go: the planner's own estimate, floored by what the body
 * measurably does. It may only ever move LATER — a navigator that promises an
 * arrival the operator cannot walk to is worse than one that says nothing.
 */
fun remainingDurationS(plannedS: Double, remainingM: Double, pace: NavPace): Double {
    val planned = plannedS.coerceAtLeast(0.0)
    val nominal = pace.nominalSpeedMps ?: return planned
    if (remainingM <= 0.0) return planned
    return maxOf(planned, remainingM / nominal)
}

/**
 * The measured body outranks the planned profile: a 7 km route planned for a
 * car and then WALKED would otherwise open every turn 600 m out.
 */
fun paceFor(profile: RouteProfile, gait: NavGait): NavPace = when (gait) {
    NavGait.ON_FOOT -> PACE_FOOT
    NavGait.CYCLING -> PACE_BIKE
    NavGait.DRIVING -> PACE_CAR
    NavGait.UNKNOWN -> when (profile) {
        RouteProfile.CAR -> PACE_CAR
        RouteProfile.BIKE -> PACE_BIKE
        RouteProfile.FOOT, RouteProfile.TRANSIT -> PACE_FOOT
    }
}

/** Initial great-circle bearing a→b, degrees clockwise from north in [0, 360). */
fun bearingDeg(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val f1 = Math.toRadians(aLat)
    val f2 = Math.toRadians(bLat)
    val dl = Math.toRadians(bLon - aLon)
    val y = sin(dl) * cos(f2)
    val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

fun haversineM(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.first - a.first)
    val dLon = Math.toRadians(b.second - a.second)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) *
        sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/**
 * Найкоротший кут між двома напрямками, 0..180.
 *
 * Потрібен там, де питання не «як близько лінія», а «чи туди я взагалі їду».
 */
fun headingGapDeg(a: Double, b: Double): Double {
    val d = kotlin.math.abs((a - b) % 360.0)
    return if (d <= 180.0) d else 360.0 - d
}

/**
 * Розбіжність курсу з коридором, за якою фікс уже не належить цьому маршруту.
 *
 * 60° — не смак: паралельна вулиця дає одиниці градусів (тому чесний обʼїзд
 * поряд не читається як схід), поперечна — близько девʼяноста. Той самий поріг
 * тримає й замок піна в мапі, і це навмисно: одна відповідь на одне питання.
 */
const val OFF_ROUTE_HEADING_DEG = 60.0
