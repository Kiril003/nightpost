package local.phantom.companion.core.nav

/**
 * FIELD FIX (Kyiv, 2026-07-24) — the road already walked.
 *
 * The route polyline was drawn whole, from origin to destination, for the
 * entire drive. Every navigator on earth consumes it: the line behind you
 * disappears, because a line that stays is an instruction you have already
 * carried out, and at a junction it is worse than clutter — the operator has
 * to work out which of the two blue strands leaving the dot is the future.
 *
 * The cut is made at the operator's own projection onto the line, so the
 * remaining polyline starts EXACTLY under the dot rather than at the next
 * shape point — otherwise the line's head would jump from vertex to vertex,
 * which reads as the route stuttering.
 *
 * Off-route is deliberately not a trim: if the operator is farther than
 * [MAX_OFF_ROUTE_M] from the line, there is no honest projection, so the
 * whole route is returned. A trim computed from a bad snap would erase the
 * road ahead — the one failure mode worse than never trimming at all.
 */
object RouteTrim {

    /** Beyond this the projection is a guess, and the route stays whole. */
    const val MAX_OFF_ROUTE_M = 60.0

    /** A polyline needs two points; one is a dot and MapLibre draws nothing. */
    private const val MIN_LINE_POINTS = 2

    /**
     * Shorter than this and the line is not a road ahead, it is a smear under
     * the dot: at the destination the cut leaves two coincident points, which
     * still count as a polyline but render as a blob that never goes away.
     */
    const val MIN_DRAWN_M = 1.0

    fun cumulative(points: List<Pair<Double, Double>>): DoubleArray {
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + haversineM(points[i - 1], points[i])
        }
        return cum
    }

    /**
     * The part of [points] still ahead of ([lat], [lon]).
     *
     * Returns the whole line when the operator has no believable projection
     * onto it, and an empty list once the road ahead is shorter than a
     * drawable segment — at which point the route has been consumed and
     * should vanish rather than linger as a stub under the dot.
     */
    fun remaining(
        points: List<Pair<Double, Double>>,
        lat: Double,
        lon: Double,
        maxOffM: Double = MAX_OFF_ROUTE_M,
    ): List<Pair<Double, Double>> {
        if (points.size < MIN_LINE_POINTS) return points
        val snap = snapToPolyline(points, cumulative(points), lat, lon)
        if (snap.offMeters > maxOffM) return points

        val ahead = ArrayList<Pair<Double, Double>>(points.size - snap.segIndex + 1)
        ahead.add(snap.lat to snap.lon)
        for (i in (snap.segIndex + 1) until points.size) ahead.add(points[i])
        return if (drawable(ahead)) ahead else emptyList()
    }

    /**
     * The part already covered — the same cut from the other side. Kept for
     * a caller that wants to ghost it rather than drop it; the split is made
     * once so the two halves can never disagree about where the dot is.
     */
    fun travelled(
        points: List<Pair<Double, Double>>,
        lat: Double,
        lon: Double,
        maxOffM: Double = MAX_OFF_ROUTE_M,
    ): List<Pair<Double, Double>> {
        if (points.size < MIN_LINE_POINTS) return emptyList()
        val snap = snapToPolyline(points, cumulative(points), lat, lon)
        if (snap.offMeters > maxOffM) return emptyList()

        val behind = ArrayList<Pair<Double, Double>>(snap.segIndex + 2)
        for (i in 0..snap.segIndex) behind.add(points[i])
        behind.add(snap.lat to snap.lon)
        return if (drawable(behind)) behind else emptyList()
    }

    private fun drawable(line: List<Pair<Double, Double>>): Boolean =
        line.size >= MIN_LINE_POINTS && cumulative(line).last() >= MIN_DRAWN_M
}
