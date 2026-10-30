package local.phantom.companion.core.data.transit

/**
 * The lat/lon box a feed actually has anything to say about. A synthesized
 * network derives it from its own stops: outside that box the model is not
 * «no vehicles right now», it is «this feed does not describe here» — and
 * the engine must go quiet rather than keep computing an absent city.
 */
data class FeedBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    fun contains(lat: Double, lon: Double, padDeg: Double = 0.0): Boolean =
        lat in (south - padDeg)..(north + padDeg) && lon in (west - padDeg)..(east + padDeg)

    fun union(other: FeedBounds): FeedBounds = FeedBounds(
        south = minOf(south, other.south),
        west = minOf(west, other.west),
        north = maxOf(north, other.north),
        east = maxOf(east, other.east),
    )

    companion object {
        /** Null for an empty network — «nowhere», never a degenerate box at 0,0. */
        fun around(points: Iterable<Pair<Double, Double>>): FeedBounds? {
            var south = Double.MAX_VALUE
            var west = Double.MAX_VALUE
            var north = -Double.MAX_VALUE
            var east = -Double.MAX_VALUE
            var any = false
            for ((lat, lon) in points) {
                any = true
                if (lat < south) south = lat
                if (lat > north) north = lat
                if (lon < west) west = lon
                if (lon > east) east = lon
            }
            return if (any) FeedBounds(south, west, north, east) else null
        }
    }
}
