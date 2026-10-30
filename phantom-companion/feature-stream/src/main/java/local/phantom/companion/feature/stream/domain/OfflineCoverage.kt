package local.phantom.companion.feature.stream.domain

/** Whether the view is already stored, and in the style now on screen. */
object OfflineCoverage {

    data class Area(
        val northLat: Double,
        val eastLon: Double,
        val southLat: Double,
        val westLon: Double,
    ) {
        val valid: Boolean get() = northLat > southLat && eastLon > westLon
    }

    enum class Verdict {
        /** Stored, for the basemap now on screen. */
        Covered,

        /** Stored, but against a different style — the tiles here will be blank. */
        WrongStyle,

        /** Some of the view is stored; the edges are not. */
        Partial,

        /** Nothing stored here. */
        None,
    }

    /** A stored pack, reduced to what a coverage question needs. */
    data class Stored(val area: Area, val styleUrl: String, val complete: Boolean)

    fun verdict(view: Area, styleUrl: String, stored: List<Stored>): Verdict {
        if (!view.valid) return Verdict.None
        val usable = stored.filter { it.complete && it.area.valid }
        if (usable.isEmpty()) return Verdict.None

        val sameStyle = usable.filter { it.styleUrl == styleUrl }
        if (sameStyle.any { contains(it.area, view) }) return Verdict.Covered
        if (sameStyle.any { overlaps(it.area, view) }) return Verdict.Partial
        if (usable.any { overlaps(it.area, view) }) return Verdict.WrongStyle
        return Verdict.None
    }

    fun contains(outer: Area, inner: Area): Boolean =
        outer.northLat >= inner.northLat && outer.southLat <= inner.southLat &&
            outer.eastLon >= inner.eastLon && outer.westLon <= inner.westLon

    fun overlaps(a: Area, b: Area): Boolean =
        a.southLat < b.northLat && a.northLat > b.southLat &&
            a.westLon < b.eastLon && a.eastLon > b.westLon

    /** Whether (lat, lon) falls inside [area] — the point-query form of [contains]. */
    fun covers(area: Area, lat: Double, lon: Double): Boolean =
        area.valid && lat <= area.northLat && lat >= area.southLat &&
            lon <= area.eastLon && lon >= area.westLon

    /**
     * [view] minus every rectangle in [covered], as rectangles.
     *
     * Rectangles rather than one polygon-with-holes: MapLibre fills a hole
     * by winding order, and a ring wound the wrong way paints the hole
     * solid instead of cutting it — the failure looks like "the whole map
     * is hatched", which reads as «нічого не завантажено» when the truth is
     * the opposite. Disjoint rectangles cannot express that mistake.
     *
     * Each covered area splits every surviving piece into up to four bands
     * around the overlap: south, north, then west and east of what is left.
     * Empty result means the viewport is fully covered — draw no hatch.
     */
    fun uncoveredRects(view: Area, covered: List<Area>): List<Area> {
        if (!view.valid) return emptyList()
        var pieces = listOf(view)
        for (c in covered) {
            if (!c.valid) continue
            pieces = pieces.flatMap { p -> subtract(p, c) }
            if (pieces.isEmpty()) break
        }
        return pieces
    }

    private fun subtract(p: Area, c: Area): List<Area> {
        if (!overlaps(p, c)) return listOf(p)
        val out = ArrayList<Area>(4)
        // Below the cut.
        if (c.southLat > p.southLat) {
            out += p.copy(northLat = c.southLat)
        }
        // Above it.
        if (c.northLat < p.northLat) {
            out += p.copy(southLat = c.northLat)
        }
        // The band level with the cut, west and east of it.
        val bandSouth = maxOf(p.southLat, c.southLat)
        val bandNorth = minOf(p.northLat, c.northLat)
        if (bandNorth > bandSouth) {
            if (c.westLon > p.westLon) {
                out += p.copy(southLat = bandSouth, northLat = bandNorth, eastLon = c.westLon)
            }
            if (c.eastLon < p.eastLon) {
                out += p.copy(southLat = bandSouth, northLat = bandNorth, westLon = c.eastLon)
            }
        }
        return out.filter { it.valid }
    }
}
