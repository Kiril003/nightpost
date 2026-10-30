package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.feature.stream.ui.map.crystal.CityTileData
import local.phantom.companion.feature.stream.ui.map.crystal.CrystalGeo
import local.phantom.companion.feature.stream.ui.map.crystal.CrystalTileKey

/**
 * design-critic.md §S5 — worst-register-wins governs the ONE summary
 * sentence (see [worstKnown]); per-segment grading governs the drawn line
 * (see [degradedSegments]). Conflating them makes every route render
 * degraded, since ~85% of national ground is GUESSED (map-oblast-census.md)
 * — habituated to within one trip, and identical on the route where it
 * actually matters.
 *
 * No tile-level reg/fresh manifest exists in this app yet (checked before
 * writing this — map-register-schema.md is still design-only). The one
 * real per-feature signal already flowing through the app is
 * [local.phantom.companion.feature.stream.ui.map.crystal.CityBuilding]'s
 * OSM tag presence, behind the 3D crystal-city fetch. [classify] reads
 * ONLY tiles already cached — it never fetches, so a route corridor never
 * turns into an unbounded Overpass crawl (the PC-bakes/phone-receives
 * doctrine in map-oblast-census.md). This means most of a route will
 * classify as unknown (null) rather than any register at all — that is the
 * honest answer until the real baked schema lands, not a bug to paper over
 * with a guess.
 */
data class RouteRegisterPoint(val lat: Double, val lon: Double, val register: GroundRegister?)

object RouteRegisterProfile {

    /**
     * One register per route point: null where nothing is known, forced to
     * REMEMBERED where [isOccupied] says the ground is occupied (checked
     * once per crystal tile the route crosses, not once per point — an
     * oblast polygon test is real point-in-polygon math and a long route
     * can carry thousands of points but only crosses a few dozen tiles),
     * otherwise the worst tag-presence register among cached buildings
     * there. Occupation always wins over presence — see
     * [GroundRegister.downgradeIfOccupied].
     */
    fun classify(
        points: List<Pair<Double, Double>>,
        tiles: Map<CrystalTileKey, CityTileData>,
        isOccupied: ((lat: Double, lon: Double) -> Boolean)? = null,
    ): List<RouteRegisterPoint> {
        if (tiles.isEmpty() && isOccupied == null) {
            return points.map { (lat, lon) -> RouteRegisterPoint(lat, lon, null) }
        }
        val occupiedByTile = HashMap<CrystalTileKey, Boolean>()
        return points.map { (lat, lon) ->
            val key = CrystalGeo.tileFor(lat, lon)
            val occupied = isOccupied != null && occupiedByTile.getOrPut(key) { isOccupied(lat, lon) }
            if (occupied) return@map RouteRegisterPoint(lat, lon, GroundRegister.REMEMBERED)
            val worst = tiles[key]?.buildings?.map { it.register }?.worstOrNull()
            RouteRegisterPoint(lat, lon, worst)
        }
    }

    /**
     * The one summary sentence's value: worst register any KNOWN point
     * carries. Unknown (null) points don't count against it — absence of
     * data is not itself a claim of low confidence, and treating it as one
     * would make the chip fire on every route regardless of what is
     * actually known.
     */
    fun worstKnown(profile: List<RouteRegisterPoint>): GroundRegister? =
        profile.mapNotNull { it.register }.worstOrNull()

    /**
     * Contiguous runs of GUESSED/REMEMBERED ground, as polylines ready for
     * an overlay layer. MEASURED/DERIVED and unknown segments are left out
     * entirely — the base route line already reads as solid/confident for
     * those, so this only ever adds ink where the ground actually degrades.
     */
    fun degradedSegments(profile: List<RouteRegisterPoint>): List<Pair<List<Pair<Double, Double>>, GroundRegister>> {
        if (profile.size < 2) return emptyList()
        val out = mutableListOf<Pair<MutableList<Pair<Double, Double>>, GroundRegister>>()
        var prevDegraded: GroundRegister? = null
        for (p in profile) {
            val pt = p.lat to p.lon
            val degraded = p.register?.takeIf { it.severity >= GroundRegister.GUESSED.severity }
            if (degraded != null) {
                if (degraded == prevDegraded) out.last().first += pt else out += mutableListOf(pt) to degraded
            }
            prevDegraded = degraded
        }
        return out.map { it.first to it.second }
    }
}
