package local.phantom.companion.core.net.map

/**
 * NAV-Ω1 — «по дорозі»: category search ALONG a route corridor instead of
 * around a point. The fetch reuses [OverpassClient.fetchAlong] (around-
 * linestring, same clause family as the NAV-1 corridor); the ranking here
 * is pure and unit-tested: each hit is projected onto the route and
 * ordered by how far ALONG the way it sits — «перша заправка попереду»,
 * not «найближча по прямій, можливо позаду».
 */
data class CorridorHit(
    val element: OverpassElement,
    val lat: Double,
    val lon: Double,
    /** Metres from the route start to the hit's projection point. */
    val alongM: Double,
    /** Metres off the route — the honest detour proxy for a quick stop. */
    val offRouteM: Double,
)

/**
 * Rank raw Overpass elements along [route]. [minAheadM] drops hits behind
 * the operator's current progress (pass 0.0 for a not-yet-started plan).
 * Sorted by along-distance: first-reachable first.
 */
fun rankAlongRoute(
    elements: List<OverpassElement>,
    route: List<Pair<Double, Double>>,
    minAheadM: Double = 0.0,
    maxOffRouteM: Double = CORRIDOR_FIND_MAX_OFF_M,
): List<CorridorHit> {
    if (route.size < 2) return emptyList()
    val cum = DoubleArray(route.size)
    for (i in 1 until route.size) {
        cum[i] = cum[i - 1] + haversineMeters(
            route[i - 1].first, route[i - 1].second, route[i].first, route[i].second,
        )
    }
    if (cum.last() <= 0.0) return emptyList()

    return elements.mapNotNull { el ->
        val lat = el.effectiveLat ?: return@mapNotNull null
        val lon = el.effectiveLon ?: return@mapNotNull null
        var bestOff = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in 0 until route.size - 1) {
            val (aLat, aLon) = route[i]
            val (bLat, bLon) = route[i + 1]
            val off = distanceToSegmentMeters(lat, lon, aLat, aLon, bLat, bLon)
            if (off < bestOff) {
                bestOff = off
                // Project along the segment for the along-distance.
                val kx = kotlin.math.cos(Math.toRadians((aLat + bLat) / 2)) * 111_320.0
                val ky = 110_540.0
                val bx = (bLon - aLon) * kx
                val by = (bLat - aLat) * ky
                val len2 = bx * bx + by * by
                val t = if (len2 <= 0.0) 0.0
                else (((lon - aLon) * kx * bx + (lat - aLat) * ky * by) / len2).coerceIn(0.0, 1.0)
                bestAlong = cum[i] + (cum[i + 1] - cum[i]) * t
            }
        }
        if (bestOff > maxOffRouteM || bestAlong < minAheadM) return@mapNotNull null
        CorridorHit(el, lat, lon, alongM = bestAlong, offRouteM = bestOff)
    }.sortedBy { it.alongM }
}

/** A stop further off-route than this stops being «по дорозі». */
const val CORRIDOR_FIND_MAX_OFF_M = 400.0
