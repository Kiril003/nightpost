package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.data.spatial.GeoPoint
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.data.spatial.SpatialLine
import local.phantom.companion.core.data.spatial.SpatialMarker

/**
 * R4.5 — shared scaffolding for the "living map" world tools (transit,
 * cameras, aircraft, hazards). Each tool fetches REAL data from a public
 * source, then does two things with one marker list: pushes a self-expiring
 * WORLD layer onto the map (so the map is alive on its own), and echoes a
 * widget-ready `map` object into the stream. This file owns the plumbing so
 * each tool stays a thin fetch+parse over an honest source (Law 1).
 */

/** A parsed world point before it becomes both a layer marker and a widget. */
internal data class WorldPoint(
    val lat: Double,
    val lon: Double,
    val label: String,
    val glyph: String? = null,
    val detail: String? = null,
    val color: String? = null,
    val bearing: Double? = null,
)

/** A parsed world line (a transit route, a corridor). */
internal data class WorldLine(
    val points: List<GeoPoint>,
    val label: String? = null,
    val color: String? = null,
)

internal fun worldManifest(id: String, name: String, tier: Int = 1) = FeatureManifest(
    id = id,
    tier = tier,
    displayName = name,
    erodesOperatorAgency = false,
    exfiltratesPrivate = false,
    actsWithoutConsent = false,
    abusableAgainstWeak = false,
    sunsetAfterDaysIdle = 90,
    surfaces = setOf(FeatureManifest.Surface.STREAM),
)

/** Widget-ready `map` object (same shape MapWidget/geo.* use). */
internal fun worldMapObject(
    centerLat: Double,
    centerLon: Double,
    zoom: Double,
    points: List<WorldPoint>,
    lines: List<WorldLine> = emptyList(),
): JsonObject = buildJsonObject {
    put("latitude", centerLat)
    put("longitude", centerLon)
    put("zoom", zoom)
    if (points.isNotEmpty()) {
        put("markers", buildJsonArray {
            points.forEach { p ->
                add(buildJsonObject {
                    put("lat", p.lat)
                    put("lon", p.lon)
                    put("label", p.label)
                    p.glyph?.let { put("glyph", it) }
                    p.detail?.let { put("detail", it) }
                    p.color?.let { put("color", it) }
                    p.bearing?.let { put("bearing", it) }
                })
            }
        })
    }
    if (lines.isNotEmpty()) {
        put("polylines", buildJsonArray {
            lines.forEach { l ->
                add(buildJsonObject {
                    l.color?.let { put("color", it) }
                    put("points", buildJsonArray {
                        l.points.forEach { pt ->
                            add(buildJsonObject { put("lat", pt.lat); put("lon", pt.lon) })
                        }
                    })
                })
            }
        })
    }
}

/** Turn world points/lines into a persisted WORLD [SpatialLayerSpec]. */
internal fun worldLayerSpec(
    title: String,
    kind: String,
    points: List<WorldPoint>,
    lines: List<WorldLine>,
    color: String?,
    glyph: String?,
    ownerThreadId: String?,
    ttlMs: Long?,
): SpatialLayerSpec = SpatialLayerSpec(
    title = title,
    origin = LayerOrigin.WORLD,
    kind = kind,
    ownerThreadId = ownerThreadId,
    markers = points.map {
        SpatialMarker(it.lat, it.lon, it.label, it.glyph, it.color, it.detail, it.bearing)
    },
    lines = lines.map { SpatialLine(it.points, it.color, 4f, it.label) },
    color = color,
    glyph = glyph,
    ttlMs = ttlMs,
)

/**
 * Push a WORLD layer + return the widget `map`. Returns the layer id, or
 * null if there was nothing to draw (caller emits an honest empty result).
 */
internal suspend fun SpatialLayerSink.pushWorldLayer(
    title: String,
    kind: String,
    points: List<WorldPoint>,
    lines: List<WorldLine> = emptyList(),
    color: String? = null,
    glyph: String? = null,
    ownerThreadId: String?,
    ttlMs: Long?,
): String? {
    if (points.isEmpty() && lines.isEmpty()) return null
    return push(worldLayerSpec(title, kind, points, lines, color, glyph, ownerThreadId, ttlMs))
}

/**
 * The card layer reads `results` and nothing else for a tool it has no explicit
 * mapping for, so a world tool that answers with `count` + `map` alone draws
 * NOTHING on screen — the whole find reaches the operator as one sentence.
 * Field names here are the ones ToolResultCards actually looks up.
 */
internal fun worldResults(points: List<WorldPoint>): JsonArray = buildJsonArray {
    points.forEach { p ->
        add(
            buildJsonObject {
                put("name", p.label)
                put("lat", p.lat)
                put("lon", p.lon)
                p.glyph?.let { put("kind", it) }
                p.detail?.let { put("description", it) }
                p.bearing?.let { put("bearing_deg", it) }
            },
        )
    }
}

/** Same contract for line answers: lead reads `title`, sub reads `description`. */
internal fun worldLineResults(lines: List<WorldLine>): JsonArray = buildJsonArray {
    lines.forEach { l ->
        add(
            buildJsonObject {
                put("title", l.label ?: "маршрут")
                put("points", l.points.size)
                l.color?.let { put("colour", it) }
            },
        )
    }
}

/**
 * Where the answer came from and how old it is. Without this a four-minute-old
 * ADS-B position and a live one read identically to both the model and the card.
 */
internal fun JsonObjectBuilder.putProvenance(
    source: String,
    asOfMs: Long,
    note: String? = null,
) {
    put("source", source)
    put("as_of_ms", asOfMs)
    note?.let { put("source_note", it) }
}

/** Rough centre of a point/line set for the widget camera. */
internal fun worldCenter(
    fallbackLat: Double,
    fallbackLon: Double,
    points: List<WorldPoint>,
    lines: List<WorldLine> = emptyList(),
): Pair<Double, Double> {
    val all = buildList {
        points.forEach { add(it.lat to it.lon) }
        lines.forEach { l -> l.points.forEach { add(it.lat to it.lon) } }
    }
    if (all.isEmpty()) return fallbackLat to fallbackLon
    return all.sumOf { it.first } / all.size to all.sumOf { it.second } / all.size
}
