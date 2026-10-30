package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.data.spatial.HAZARD_LAYER_KIND
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.feature.stream.ui.widget.MapPayload

/**
 * Shared GeoJSON builders for MapScreen and MapWidget.
 * Ensures consistent rendering across all map surfaces.
 */

internal fun buildMarkersGeoJson(markers: List<MapPayload.MarkerData>): String {
    val features = markers.joinToString(",") { m ->
        val labelEscaped = escapeJsonString(m.label ?: "")
        "{\"type\":\"Feature\"," +
            "\"properties\":{\"label\":\"$labelEscaped\"}," +
            "\"geometry\":{\"type\":\"Point\",\"coordinates\":[${m.lon},${m.lat}]}}"
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

internal fun buildPolylinesGeoJson(polylines: List<MapPayload.PolylineData>, isDark: Boolean): String {
    val features = polylines.joinToString(",") { p ->
        val coords = p.points.joinToString(",") { pt -> "[${pt.lon},${pt.lat}]" }
        val color = RouteRole.of(p.color)?.let { routeColor(it, isDark) } ?: p.color ?: MapTokens.routeInk(isDark)
        val width = p.width
        val profile = p.profile.orEmpty()
        val isDashed = p.isDashed
        val opacity = p.opacity
        val selected = p.selected
        """{"type":"Feature","properties":{"color":"$color","width":$width,"profile":"$profile","isDashed":$isDashed,"opacity":$opacity,"selected":$selected},"geometry":{"type":"LineString","coordinates":[$coords]}}"""
    }
    return """{"type":"FeatureCollection","features":[$features]}"""
}

// ── R4.1 — phantom-layer builders ───────────────────────────────────────
//
// The full MapScreen renders every VISIBLE phantom/world/system layer from
// the SpatialLayerStore into three shared sources (markers / lines /
// regions). Each feature carries its own `color` (marker → layer → default)
// and `label`/`detail` so a single CircleLayer/LineLayer/FillLayer can paint
// a heterogeneous stack of layers with per-feature tint, and a tap can read
// back which layer + element was hit. `layer_id` rides on every feature.

private const val DEFAULT_LAYER_HEX = "#8B5CF6" // phantom violet

/** All visible markers across [layers] as one FeatureCollection. */
internal fun buildLayerMarkersGeoJson(layers: List<SpatialLayer>): String {
    // Мітки шару «hazard» малює тир EVENT_MARKS фігурками (MARKS_SPEC);
    // крапка тут була б другим знаком тієї самої події.
    val features = layers.filter { it.visible && it.spec.kind != HAZARD_LAYER_KIND }.flatMap { layer ->
        val layerColor = layer.spec.color ?: DEFAULT_LAYER_HEX
        layer.spec.markers.map { m ->
            val color = m.color ?: layerColor
            val props = buildString {
                append("\"label\":\"").append(escapeJsonString(m.label ?: "")).append("\",")
                append("\"detail\":\"").append(escapeJsonString(m.detail ?: "")).append("\",")
                append("\"glyph\":\"").append(escapeJsonString(m.glyph ?: "")).append("\",")
                append("\"color\":\"").append(escapeJsonString(color)).append("\",")
                append("\"layer_id\":\"").append(escapeJsonString(layer.id)).append("\",")
                append("\"layer_title\":\"").append(escapeJsonString(layer.spec.title)).append("\",")
                // Провенанс мусить доїхати до шару: «спільнота» — це чужий
                // звіт, і мапа не має права показати його як власну мітку.
                append("\"source\":\"").append(escapeJsonString(m.source.orEmpty())).append("\",")
                append("\"bearing\":").append(m.bearing ?: 0.0)
            }
            "{\"type\":\"Feature\",\"properties\":{$props}," +
                "\"geometry\":{\"type\":\"Point\",\"coordinates\":[${m.lon},${m.lat}]}}"
        }
    }.joinToString(",")
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

/** All visible polylines across [layers] as one FeatureCollection. */
internal fun buildLayerLinesGeoJson(layers: List<SpatialLayer>): String {
    val features = layers.filter { it.visible }.flatMap { layer ->
        val layerColor = layer.spec.color ?: DEFAULT_LAYER_HEX
        layer.spec.lines.map { line ->
            val color = line.color ?: layerColor
            val coords = line.points.joinToString(",") { "[${it.lon},${it.lat}]" }
            "{\"type\":\"Feature\",\"properties\":{" +
                "\"color\":\"${escapeJsonString(color)}\"," +
                "\"width\":${line.width}," +
                "\"layer_id\":\"${escapeJsonString(layer.id)}\"}," +
                "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[$coords]}}"
        }
    }.joinToString(",")
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

/** All visible circle regions across [layers] as GeoJSON polygons. */
internal fun buildLayerRegionsGeoJson(layers: List<SpatialLayer>): String {
    val features = layers.filter { it.visible }.flatMap { layer ->
        val layerColor = layer.spec.color ?: DEFAULT_LAYER_HEX
        layer.spec.regions.map { r ->
            val color = r.color ?: layerColor
            val cosLat = kotlin.math.cos(Math.toRadians(r.lat))
            val ring = (0..64).joinToString(",") { i ->
                val a = 2.0 * Math.PI * i / 64
                val dLat = (r.radiusM / 111_320.0) * kotlin.math.cos(a)
                val dLon = (r.radiusM / (111_320.0 * cosLat)) * kotlin.math.sin(a)
                "[${r.lon + dLon},${r.lat + dLat}]"
            }
            "{\"type\":\"Feature\",\"properties\":{" +
                "\"color\":\"${escapeJsonString(color)}\"," +
                "\"label\":\"${escapeJsonString(r.label ?: layer.spec.title)}\"," +
                "\"layer_id\":\"${escapeJsonString(layer.id)}\"}," +
                "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[$ring]]}}"
        }
    }.joinToString(",")
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

internal fun escapeJsonString(s: String): String {
    val out = StringBuilder(s.length + 2)
    for (c in s) {
        when (c) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            else -> if (c.code < 0x20) out.append(' ') else out.append(c)
        }
    }
    return out.toString()
}
