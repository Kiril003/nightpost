package local.phantom.companion.feature.stream.ui.map

import android.graphics.RectF
import local.phantom.companion.feature.stream.ui.map.atlas.ContributionRegistry
import local.phantom.companion.feature.stream.ui.map.atlas.LensFrame
import local.phantom.companion.feature.stream.ui.map.atlas.MapContribution
import local.phantom.companion.feature.stream.ui.map.atlas.TapGeometry
import local.phantom.companion.feature.stream.ui.map.atlas.TapHit
import local.phantom.companion.feature.stream.ui.map.atlas.TapSpec
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Feature

/**
 * Півсторона вікна, яким тап шукає позначку, у точках.
 *
 * 24 — половина порогу дотику: палець накриває приблизно стільки. Число довго
 * стояло в СИРИХ пікселях під коментарем, що обіцяв density-independent —
 * на щільності 2.625 це було 13.7dp, утричі менше за поріг дотику.
 */
internal const val TAP_TOLERANCE_DP = 12f

/**
 * ATLAS-0b (D3) — ONE tap dispatcher.
 *
 * Five independent `queryRenderedFeatures` chains used to race for the same
 * finger, each with its own early return and none of them aware of the others.
 * Now the registry states the priority ([TapRank]) and the rules
 * ([TapSpec.geometry], [TapSpec.excludesLayers], [TapSpec.prefers]); this only
 * asks the engine and hands the winner to a handler.
 *
 * Ranks are queried in order and the walk stops at the first rank that hits,
 * because rank strictly outranks distance — a lower rank can no longer win, so
 * querying it would be a main-thread render pass spent on a foregone answer.
 */
internal class MapTapDispatcher(
    private val handlers: Map<String, (Feature, LatLng) -> Unit>,
) {

    fun onTap(
        map: MapLibreMap,
        registry: ContributionRegistry,
        frame: LensFrame,
        point: LatLng,
        tolerancePx: Float,
    ): Boolean {
        val pixel = map.projection.toScreenLocation(point)
        val box = RectF(
            pixel.x - tolerancePx, pixel.y - tolerancePx,
            pixel.x + tolerancePx, pixel.y + tolerancePx,
        )
        val candidates = registry.tappableInOrder(frame)

        for ((_, group) in candidates.groupBy { it.tap!!.rank }) {
            val hits = ArrayList<TapHit>()
            val features = HashMap<String, Feature>()
            for (c in group) {
                val spec = c.tap!!
                val raw = if (spec.catchAll) {
                    runCatching { map.queryRenderedFeatures(box) }.getOrNull().orEmpty()
                } else {
                    runCatching {
                        map.queryRenderedFeatures(box, *spec.layerIds.toTypedArray())
                    }.getOrNull().orEmpty()
                }
                raw.forEachIndexed { idx, f ->
                    val layerId = if (spec.catchAll) featureLayerId(f) else spec.layerIds.first()
                    val hit = evaluate(spec, f, layerId, map, pixel.x, pixel.y, idx) ?: return@forEachIndexed
                    hits += hit
                    features[hit.key()] = f
                }
            }
            val resolved = registry.resolveTap(hits, frame) ?: continue
            val feature = features[resolved.hit.key()] ?: continue
            val handler = handlers[resolved.contributionId] ?: continue
            handler(feature, point)
            return true
        }
        return false
    }

    /** Distance is the tie-break inside a rank, so it has to identify the hit. */
    private fun TapHit.key(): String = "$layerId#$preference#$distancePx"

    private fun evaluate(
        spec: TapSpec,
        f: Feature,
        layerId: String,
        map: MapLibreMap,
        tapX: Float,
        tapY: Float,
        ordinal: Int,
    ): TapHit? {
        if (spec.excludesLayers.any { layerId.contains(it) }) return null
        val geom = f.geometry()
        val kind = when (geom) {
            is org.maplibre.geojson.Point -> TapGeometry.POINT
            is org.maplibre.geojson.LineString, is org.maplibre.geojson.MultiLineString -> TapGeometry.LINE
            else -> TapGeometry.AREA
        }
        if (kind !in spec.geometry) return null

        val preference = if (spec.prefers.isEmpty()) {
            0
        } else {
            val group = spec.prefers.indexOfFirst { keys ->
                keys.any { k -> f.getStringOr(k).isNotBlank() || f.hasProperty(k) }
            }
            if (group < 0) return null
            group
        }

        val distance = if (geom is org.maplibre.geojson.Point) {
            val pt = map.projection.toScreenLocation(LatLng(geom.latitude(), geom.longitude()))
            val dx = pt.x - tapX
            val dy = pt.y - tapY
            dx * dx + dy * dy
        } else {
            // A polygon has no meaningful point distance; the engine's own
            // order is the only signal left, so keep it stable.
            TapHit.AREA_DISTANCE_PX + ordinal
        }
        return TapHit(layerId = layerId, distancePx = distance, preference = preference)
    }

    /** The base style's own layer, when the engine bothered to attach it. */
    private fun featureLayerId(f: Feature): String =
        f.getStringOr("layer_id").ifBlank { f.getStringOr("_layer") }
}

/** Tap-eligible contributions for this frame, highest priority first. */
internal fun ContributionRegistry.tappableInOrder(frame: LensFrame): List<MapContribution> =
    installOrder()
        .filter { it.tap != null && it.visibleIn(frame) }
        .sortedWith(compareBy({ it.tap!!.rank.ordinal }, { it.tier.ordinal }))
