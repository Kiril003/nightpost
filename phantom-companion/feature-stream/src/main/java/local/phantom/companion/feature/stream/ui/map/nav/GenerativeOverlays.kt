package local.phantom.companion.feature.stream.ui.map.nav

import local.phantom.companion.core.data.spatial.GeoPoint
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.data.spatial.SpatialLine
import local.phantom.companion.core.data.spatial.SpatialRegion
import local.phantom.companion.core.sensor.nav.RoadGraph
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapText

/**
 * HORIZON III.2 — generative tactical overlays. Parametric fields PHANTOM (or
 * the operator) can conjure onto the living map, each computed from real data
 * and rendered through the EXISTING phantom-layer pipeline (`SpatialLayerSpec`
 * → regions/lines) — no new renderer, the net-negative-LoC bias kept.
 *
 *  - [shelterIsochrone] — «скільки встигну»: the road network coloured by how
 *    long it takes to walk there from a point, over the offline [RoadGraph].
 *    A reachability map, not a guess: streets the operator genuinely cannot
 *    reach in the time budget simply are not drawn.
 *  - [dangerField] — a graduated wash around hazards whose intensity falls
 *    with distance, so a threat reads as a gradient of caution rather than a
 *    hard on/off circle.
 *
 * Pure: no Android, no MapLibre. Every output is a [SpatialLayerSpec] the map
 * already knows how to draw.
 */
object GenerativeOverlays {

    /** One reachability band: everything reachable within [ceilingSec]. */
    data class Band(val ceilingSec: Int, val colorHex: String)

    /** The default three-band walk isochrone: near / medium / edge. */
    val DEFAULT_WALK_BANDS = listOf(
        Band(180, "#22C55E"),   // ≤ 3 min — green
        Band(360, "#EAB308"),   // ≤ 6 min — amber
        Band(600, "#F97316"),   // ≤ 10 min — orange
    )

    /**
     * Colour the reachable road network by walk-time band from (originLat,
     * originLon). Returns null when nothing is reachable (empty/absent graph)
     * — an honest «нема куди йти звідси в межах карти», never an empty layer.
     */
    fun shelterIsochrone(
        graph: RoadGraph,
        originLat: Double,
        originLon: Double,
        bands: List<Band> = DEFAULT_WALK_BANDS,
        footSpeedMps: Double = 1.3,
        title: String = MapText.s(R.string.mapl_g_isochrone_title),
        ttlMs: Long? = 30 * 60_000L,
    ): SpatialLayerSpec? {
        if (bands.isEmpty()) return null
        val sorted = bands.sortedBy { it.ceilingSec }
        val maxCostM = sorted.last().ceilingSec * footSpeedMps
        val edges = graph.reachable(originLat, originLon, maxCostM)
        if (edges.isEmpty()) return null

        val lines = edges.mapNotNull { e ->
            val sec = e.costM / footSpeedMps
            val band = sorted.firstOrNull { sec <= it.ceilingSec } ?: return@mapNotNull null
            SpatialLine(
                points = listOf(GeoPoint(e.aLat, e.aLon), GeoPoint(e.bLat, e.bLon)),
                color = band.colorHex,
                width = 5f,
            )
        }
        if (lines.isEmpty()) return null
        return SpatialLayerSpec(
            title = title,
            origin = LayerOrigin.PHANTOM,
            kind = "isochrone",
            lines = lines,
            color = sorted.first().colorHex,
            glyph = MapText.s(R.string.mapl_g_isochrone_glyph),
            ttlMs = ttlMs,
        )
    }

    /** One hazard for the danger field. */
    data class Hazard(val lat: Double, val lon: Double, val radiusM: Double, val label: String? = null)

    /**
     * A graduated danger wash: [rings] concentric circles per hazard, the
     * inner ones a more intense red than the outer — a discrete gradient the
     * single-opacity fill layer can actually render (overlapping same-opacity
     * fills don't stack, so intensity is carried by COLOUR, not alpha).
     */
    fun dangerField(
        hazards: List<Hazard>,
        rings: Int = 3,
        title: String = MapText.s(R.string.mapl_g_danger_field_title),
        ttlMs: Long? = 2 * 60 * 60_000L,
    ): SpatialLayerSpec? {
        if (hazards.isEmpty() || rings < 1) return null
        // Intense core → faint edge. Fixed-hue red family (the sanctioned
        // ChatTints-style exception for coded danger).
        val intensity = intArrayOf(0xEF4444, 0xF87171, 0xFCA5A5, 0xFECACA, 0xFEE2E2)
        val regions = ArrayList<SpatialRegion>(hazards.size * rings)
        for (h in hazards) {
            for (i in 0 until rings) {
                val frac = 1.0 - i.toDouble() / rings // outer→inner: 1, .66, .33
                val hex = "#%06X".format(intensity[i.coerceAtMost(intensity.size - 1)])
                regions.add(
                    SpatialRegion(
                        lat = h.lat,
                        lon = h.lon,
                        radiusM = h.radiusM * (i + 1) / rings, // smallest = innermost
                        color = hex,
                        label = if (i == 0) h.label else null,
                    ),
                )
            }
        }
        // Draw largest (faintest) first so the intense core lands on top.
        regions.sortByDescending { it.radiusM }
        return SpatialLayerSpec(
            title = title,
            origin = LayerOrigin.PHANTOM,
            kind = "danger-field",
            regions = regions,
            color = "#EF4444",
            glyph = MapText.s(R.string.mapl_g_danger_field_glyph),
            ttlMs = ttlMs,
        )
    }
}
