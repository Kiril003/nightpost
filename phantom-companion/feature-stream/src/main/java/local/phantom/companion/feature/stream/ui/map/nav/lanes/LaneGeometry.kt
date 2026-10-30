package local.phantom.companion.feature.stream.ui.map.nav.lanes

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * PRESENCE wave 1 — the road, widened into its real lanes.
 *
 * Everything here is pure geometry over (lat, lon) pairs: no MapLibre, no
 * Compose, no clock. The band is baked ONCE per maneuver at plan time and then
 * only swapped into a source, so this file may be slow and exact rather than
 * fast and approximate — and on a curve exactness is the whole point. A lane
 * offset by a flat per-vertex normal pinches on the inside of a bend and gaps
 * on the outside; the miter below is what keeps four parallel lines parallel
 * through a motorway ramp.
 */

/** Standard traffic lane, metres. Wider than a marked city lane on purpose:
 *  the band reads as asphalt, and asphalt includes the paint. */
internal const val LANE_WIDTH_M = 3.5

/** Metres per degree of latitude — the local frame's only constant. */
private const val M_PER_DEG = 111_320.0

/** A miter longer than this is a spike, not a corner: clamp and accept the
 *  hairline gap on a hairpin the router should not have sent us into. */
private const val MITER_LIMIT = 2.0

/** Lane centre offsets from the route line, leftmost first; negative = left. */
internal fun laneCenterOffsets(count: Int, widthM: Double = LANE_WIDTH_M): List<Double> =
    (0 until count).map { (it - (count - 1) / 2.0) * widthM }

/** Offsets of the painted separators BETWEEN lanes — one fewer than lanes. */
internal fun laneSeparatorOffsets(count: Int, widthM: Double = LANE_WIDTH_M): List<Double> =
    (0 until count - 1).map { (it + 1 - count / 2.0) * widthM }

/**
 * The last [lengthM] of route before [maneuver], ordered start → maneuver.
 *
 * The maneuver point is snapped onto the polyline first (OSRM's maneuver
 * location is a junction node, which is on the line, but a trimmed or
 * re-published line may have dropped that vertex), then the walk runs
 * backwards accumulating true ground distance. Fewer than two points back
 * means there is no approach to draw — an empty list, never a stub.
 */
internal fun approachPath(
    points: List<Pair<Double, Double>>,
    maneuver: Pair<Double, Double>,
    lengthM: Double,
): List<Pair<Double, Double>> {
    if (points.size < 2 || lengthM <= 0.0) return emptyList()
    // Anchored at the MANEUVER, not at the route's start: the flat frame is
    // exact where it is anchored, and a cross-country route would otherwise
    // measure its last 250 m in the scale of a different latitude.
    val frame = LocalFrame(maneuver)
    val xy = points.map(frame::to)
    val target = frame.to(maneuver)

    var bestSeg = -1
    var bestT = 0.0
    var bestD2 = Double.MAX_VALUE
    for (i in 0 until xy.size - 1) {
        val (t, d2) = projectOnSegment(target, xy[i], xy[i + 1])
        if (d2 < bestD2) {
            bestD2 = d2
            bestSeg = i
            bestT = t
        }
    }
    if (bestSeg < 0) return emptyList()

    val end = lerp(xy[bestSeg], xy[bestSeg + 1], bestT)
    val back = ArrayList<Pair<Double, Double>>()
    back += end
    var left = lengthM
    var v = bestSeg
    var from = end
    while (left > 0.0 && v >= 0) {
        val prev = xy[v]
        val seg = dist(prev, from)
        if (seg <= 0.0) {
            v--
            continue
        }
        if (seg >= left) {
            val t = left / seg
            back += Pair(from.first + (prev.first - from.first) * t, from.second + (prev.second - from.second) * t)
            left = 0.0
        } else {
            back += prev
            left -= seg
            from = prev
            v--
        }
    }
    if (back.size < 2) return emptyList()
    return back.asReversed().map(frame::from)
}

/**
 * [path] shifted [offsetM] metres sideways — positive to the RIGHT of travel.
 * Vertex count is preserved, so lane *i* and the centreline stay index-aligned
 * and a test can measure them against each other.
 */
internal fun offsetPolyline(
    path: List<Pair<Double, Double>>,
    offsetM: Double,
): List<Pair<Double, Double>> {
    if (path.size < 2) return emptyList()
    val frame = LocalFrame(path.first())
    // A repeated vertex has no direction, and one of those in the middle of the
    // spine would leave a lane with no normal to be offset along.
    val xy = ArrayList<Pair<Double, Double>>(path.size)
    for (p in path.map(frame::to)) {
        if (xy.isEmpty() || dist(xy.last(), p) > 1e-6) xy += p
    }
    if (xy.size < 2) return emptyList()
    val out = ArrayList<Pair<Double, Double>>(xy.size)
    for (i in xy.indices) {
        val before = normalOf(xy.getOrNull(i - 1), xy[i])
        val after = normalOf(xy[i], xy.getOrNull(i + 1))
        val n = when {
            before == null -> after ?: return emptyList()
            after == null -> before
            else -> miter(before, after)
        }
        out += frame.from(Pair(xy[i].first + n.first * offsetM, xy[i].second + n.second * offsetM))
    }
    return out
}

/** Right-hand unit normal of a→b, null on a zero-length segment. */
private fun normalOf(a: Pair<Double, Double>?, b: Pair<Double, Double>?): Pair<Double, Double>? {
    if (a == null || b == null) return null
    val dx = b.first - a.first
    val dy = b.second - a.second
    val len = hypot(dx, dy)
    if (len <= 0.0) return null
    return Pair(dy / len, -dx / len)
}

/**
 * The bisector of two segment normals, lengthened by 1/cos(half-angle) so the
 * offset line stays [offsetM] from BOTH segments instead of cutting the corner.
 */
private fun miter(a: Pair<Double, Double>, b: Pair<Double, Double>): Pair<Double, Double> {
    val sx = a.first + b.first
    val sy = a.second + b.second
    val len = hypot(sx, sy)
    if (len <= 1e-9) return a // a fold-back: no bisector exists, keep the entry side
    val ux = sx / len
    val uy = sy / len
    val cos = (ux * a.first + uy * a.second).coerceAtLeast(1.0 / MITER_LIMIT)
    return Pair(ux / cos, uy / cos)
}

/** Equirectangular metres around one anchor — exact enough over 250 m. */
private class LocalFrame(anchor: Pair<Double, Double>) {
    private val lat0 = anchor.first
    private val lon0 = anchor.second
    private val kx = M_PER_DEG * cos(Math.toRadians(lat0))

    fun to(p: Pair<Double, Double>): Pair<Double, Double> =
        Pair((p.second - lon0) * kx, (p.first - lat0) * M_PER_DEG)

    fun from(p: Pair<Double, Double>): Pair<Double, Double> =
        Pair(lat0 + p.second / M_PER_DEG, lon0 + (if (abs(kx) < 1e-6) 0.0 else p.first / kx))
}

private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>) =
    hypot(b.first - a.first, b.second - a.second)

private fun lerp(a: Pair<Double, Double>, b: Pair<Double, Double>, t: Double) =
    Pair(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t)

/** (clamped t along a→b, squared distance from p) of p's projection. */
private fun projectOnSegment(
    p: Pair<Double, Double>,
    a: Pair<Double, Double>,
    b: Pair<Double, Double>,
): Pair<Double, Double> {
    val dx = b.first - a.first
    val dy = b.second - a.second
    val len2 = dx * dx + dy * dy
    val t = if (len2 <= 0.0) 0.0 else (((p.first - a.first) * dx + (p.second - a.second) * dy) / len2).coerceIn(0.0, 1.0)
    val px = a.first + dx * t
    val py = a.second + dy * t
    val ex = p.first - px
    val ey = p.second - py
    return Pair(t, ex * ex + ey * ey)
}

/** Metres between two (lat, lon) pairs — the tests measure the band with it. */
internal fun laneMetersBetween(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val kx = M_PER_DEG * cos(Math.toRadians(a.first))
    val dx = (b.second - a.second) * kx
    val dy = (b.first - a.first) * M_PER_DEG
    return sqrt(dx * dx + dy * dy)
}
