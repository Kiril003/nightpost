package local.phantom.companion.feature.car

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Turns route coordinates into pixels on the car display — heading-up, with
 * the driver's position anchored low so most of the surface is the road ahead.
 *
 * Pure so the one thing that must not be wrong on a car screen — where the
 * line goes — is provable without a head unit.
 */
internal object CarMapProjection {

    /** How far up the surface the puck sits: 0 = top edge, 1 = bottom. */
    const val PUCK_Y_FRACTION = 0.78f

    /** Metres of road ahead the default view covers. */
    const val DEFAULT_SPAN_M = 800.0

    /** How near the edge of the guaranteed-visible area the puck may sit. */
    private const val PUCK_CLEARANCE_PX = 30f

    /** Under this a reported rectangle is not a viewport, it is noise. */
    private const val MIN_AREA_PX = 48

    private const val M_PER_DEG_LAT = 111_320.0

    /** A rectangle of the car surface, in pixels. */
    data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    data class Camera(
        val lat: Double,
        val lon: Double,
        val bearingDeg: Double,
        val widthPx: Int,
        val heightPx: Int,
        val spanM: Double = DEFAULT_SPAN_M,
        /** What the host says is currently free of its own chrome. */
        val visible: Area? = null,
        /** What the host guarantees it never covers. */
        val stable: Area? = null,
    )

    data class Pt(val x: Float, val y: Float)

    /**
     * The part of the surface worth drawing into.
     *
     * Falls back to the whole surface whenever the host reports something that
     * is not a usable rectangle — a bad number from a head unit must not be
     * able to shrink the map to a sliver.
     */
    fun drawArea(widthPx: Int, heightPx: Int, visible: Area?): Area {
        val full = Area(0, 0, widthPx, heightPx)
        if (visible == null) return full
        val clipped = clip(visible, widthPx, heightPx)
        return if (clipped.width < MIN_AREA_PX || clipped.height < MIN_AREA_PX) full else clipped
    }

    fun drawArea(cam: Camera): Area = drawArea(cam.widthPx, cam.heightPx, cam.visible)

    private fun clip(a: Area, widthPx: Int, heightPx: Int) = Area(
        left = a.left.coerceIn(0, widthPx),
        top = a.top.coerceIn(0, heightPx),
        right = a.right.coerceIn(0, widthPx),
        bottom = a.bottom.coerceIn(0, heightPx),
    )

    /**
     * Where the driver sits on the surface. Everything is drawn around this one
     * point, so the puck and the route cannot come apart.
     *
     * Centred on what is actually visible — a routing card down one side used
     * to put the triangle underneath it — then pulled inside the rectangle the
     * host promises never to cover.
     */
    fun anchor(cam: Camera): Pt {
        val area = drawArea(cam)
        val x = area.left + area.width / 2f
        val y = area.top + area.height * PUCK_Y_FRACTION
        // Clipped first: a stable rectangle reported below the surface would
        // otherwise clamp the puck off the bottom of the screen entirely.
        val s = cam.stable?.let { clip(it, cam.widthPx, cam.heightPx) } ?: return Pt(x, y)
        val lo = s.top + PUCK_CLEARANCE_PX
        val hi = s.bottom - PUCK_CLEARANCE_PX
        if (lo > hi) return Pt(x, y)
        val clamped = y.coerceIn(lo, hi)
        // Pulling the puck up eats the road ahead of it; past a point, ignoring
        // the stable area beats a map with no distance left in it.
        return if (clamped - area.top < MIN_AREA_PX) Pt(x, y) else Pt(x, clamped)
    }

    /**
     * Metres east/north of the camera centre. Fine at road scale: the local
     * flat-earth error over a few hundred metres is well under a pixel.
     */
    private fun offsetsM(cam: Camera, lat: Double, lon: Double): Pair<Double, Double> {
        val north = (lat - cam.lat) * M_PER_DEG_LAT
        val east = (lon - cam.lon) * M_PER_DEG_LAT * cos(Math.toRadians(cam.lat))
        return east to north
    }

    fun project(cam: Camera, lat: Double, lon: Double): Pt {
        val (east, north) = offsetsM(cam, lat, lon)
        // Rotate so the bearing points up the screen: rx is how far right of
        // the driver the point is, ry how far ahead.
        val rad = Math.toRadians(cam.bearingDeg)
        val rx = east * cos(rad) - north * kotlin.math.sin(rad)
        val ry = east * kotlin.math.sin(rad) + north * cos(rad)

        val pxPerM = pixelsPerMetre(cam)
        val at = anchor(cam)
        return Pt(
            x = (at.x + rx * pxPerM).toFloat(),
            y = (at.y - ry * pxPerM).toFloat(),
        )
    }

    /** The span is what fits ABOVE the puck; that is the road being driven. */
    fun pixelsPerMetre(cam: Camera): Double {
        val ahead = anchor(cam).y - drawArea(cam).top
        return if (cam.spanM <= 0 || ahead <= 0f) 0.0 else ahead / cam.spanM
    }

    /**
     * Keeps only what can be on screen, plus one point either side so a line
     * crossing the view still enters and leaves at the right edge.
     *
     * Generic over the point type: the route-register work needs this same
     * clip applied to [local.phantom.companion.core.nav.RoutePoint], and
     * re-deriving the margin math a second time is exactly the kind of
     * near-duplicate this codebase has already been bitten by once.
     */
    fun <T> visibleSlice(cam: Camera, points: List<T>, latLon: (T) -> Pair<Double, Double>): List<T> {
        if (points.size < 2) return points
        val margin = cam.spanM * 1.5
        val inside = points.map { p ->
            val (lat, lon) = latLon(p)
            val (east, north) = offsetsM(cam, lat, lon)
            abs(east) < margin && abs(north) < margin
        }
        val first = inside.indexOfFirst { it }
        if (first < 0) return emptyList()
        val last = inside.indexOfLast { it }
        return points.subList(max(0, first - 1), min(points.size, last + 2))
    }

    fun visibleSlice(cam: Camera, points: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
        visibleSlice(cam, points) { it }

    /**
     * Splits the line at the point nearest the driver: what is behind is drawn
     * dim, what is ahead is the route. Returns the index to split at.
     */
    fun <T> nearestIndex(points: List<T>, lat: Double, lon: Double, latLon: (T) -> Pair<Double, Double>): Int {
        if (points.isEmpty()) return 0
        var bestAt = 0
        var best = Double.MAX_VALUE
        points.forEachIndexed { i, p ->
            val (pLat, pLon) = latLon(p)
            val dLat = (pLat - lat) * M_PER_DEG_LAT
            val dLon = (pLon - lon) * M_PER_DEG_LAT * cos(Math.toRadians(lat))
            val d = dLat * dLat + dLon * dLon
            if (d < best) {
                best = d
                bestAt = i
            }
        }
        return bestAt
    }

    fun nearestIndex(points: List<Pair<Double, Double>>, lat: Double, lon: Double): Int =
        nearestIndex(points, lat, lon) { it }

    /**
     * Zoom follows speed: at a walking pace 400 m of road is plenty, on a
     * motorway 2.2 km ahead is needed to prepare for exits and forks comfortably.
     */
    fun spanForSpeed(speedKmh: Int?): Double = when {
        speedKmh == null -> DEFAULT_SPAN_M
        speedKmh < 20 -> 400.0
        speedKmh < 60 -> 750.0
        speedKmh < 100 -> 1_300.0
        else -> 2_200.0
    }
}
