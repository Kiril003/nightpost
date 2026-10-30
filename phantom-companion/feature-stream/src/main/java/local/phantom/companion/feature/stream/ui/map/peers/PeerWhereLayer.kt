package local.phantom.companion.feature.stream.ui.map.peers

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import local.phantom.companion.core.design.theme.PhantomPalette
import local.phantom.companion.core.data.peers.PeerBox
import local.phantom.companion.core.data.peers.PeerMotion
import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.PeerPrecision
import local.phantom.companion.core.data.peers.ShareMode
import local.phantom.companion.core.data.peers.expiredAt
import local.phantom.companion.core.data.peers.freshAt
import local.phantom.companion.feature.stream.ui.map.EMPTY_FC
import local.phantom.companion.feature.stream.ui.map.MAP_TEXT_FONT
import local.phantom.companion.feature.stream.ui.map.MapSymbolPalette
import local.phantom.companion.feature.stream.ui.map.escapeJsonString
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Друзі, що поділились положенням. Район і квартал — розмита пляма на всю
 * клітинку, а не точка: точки в них немає, і малювати її означало б вигадати.
 * Точно — точка з колом похибки. Маркер — обличчя або ініціал тоном людини з
 * месенджера; хто в одному місці — одна група з лічильником. Метрів ніде немає.
 */
internal const val PEER_WHERE_SOURCE = "phantom-peer-where-source"
internal const val PEER_WHERE_FILL = "phantom-peer-where-fill"
internal const val PEER_WHERE_EDGE = "phantom-peer-where-edge"
internal const val PEER_WHERE_MARK = "phantom-peer-where-mark"
internal const val PEER_WHERE_WORLD = "phantom-peer-where-world"
private val WORLD_AMBER = PhantomPalette.WorldAmber.toArgb()

internal val TONE = PhantomPalette.PeerPlate.toArgb()
private const val MIN_ACCURACY_M = 25.0
private const val TOGETHER_M = 80.0

internal fun PeerPoint.isWhere(): Boolean = share.mode == ShareMode.WHERE

internal fun peerInitial(name: String): String =
    name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

/** Значок на аватарі: сідає телефон — важливіше за те, як людина рухається. */
internal fun peerBadge(p: PeerPoint): String = when {
    p.lowBattery -> "low"
    p.motion == PeerMotion.VEHICLE -> "car"
    p.motion == PeerMotion.FOOT -> "foot"
    else -> ""
}

/**
 * Хто в одному місці — одна група. Район чи квартал — та сама клітинка;
 * точні — ближче за [TOGETHER_M] до першого в групі. Змішувати пляму й точку не можна:
 * центр плями — не місце людини.
 */
internal fun peerWhereGroups(live: List<PeerPoint>): List<List<PeerPoint>> {
    val out = ArrayList<MutableList<PeerPoint>>()
    live.sortedBy { it.nodeId }.forEach { p ->
        val home = out.firstOrNull { g ->
            val head = g.first()
            when {
                p.box != null || head.box != null -> p.box == head.box
                else -> metres(head.lat, head.lon, p.lat, p.lon) <= TOGETHER_M
            }
        }
        if (home != null) home += p else out += mutableListOf(p)
    }
    return out
}

internal fun peerWhereGeoJson(points: List<PeerPoint>, nowMs: Long, world: List<PeerBox> = emptyList(), meet: Pair<Double, Double>? = null): String {
    val live = points.filter { it.isWhere() && !it.expiredAt(nowMs) }
    val features = ArrayList<String>()
    // «Мій світ» — лише коли людина сама ввімкнула його на мапі; під людьми, тож першим.
    world.forEach { b ->
        features += "{\"type\":\"Feature\",\"properties\":{\"world\":true},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[" +
            "[${b.west},${b.south}],[${b.east},${b.south}],[${b.east},${b.north}],[${b.west},${b.north}],[${b.west},${b.south}]]]}}"
    }
    live.forEach { p ->
        val ring = p.box?.let { b ->
            listOf(b.west to b.south, b.east to b.south, b.east to b.north, b.west to b.north, b.west to b.south)
        } ?: if (p.precision == PeerPrecision.EXACT) circle(p.lat, p.lon, maxOf(MIN_ACCURACY_M, p.accuracyM?.toDouble() ?: 0.0)) else null
        if (ring != null) {
            features += "{\"type\":\"Feature\",\"properties\":{${personProps(p, nowMs)}},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[" +
                ring.joinToString(",") { "[${it.first},${it.second}]" } + "]]}}"
        }
    }
    peerWhereGroups(live).forEach { g ->
        val props = if (g.size == 1) personProps(g.single(), nowMs) else groupProps(g, nowMs)
        val lat = g.map { it.lat }.average()
        val lon = g.map { it.lon }.average()
        features += "{\"type\":\"Feature\",\"properties\":{$props},\"geometry\":{\"type\":\"Point\",\"coordinates\":[$lon,$lat]}}"
    }
    if (meet != null) {
        features += "{\"type\":\"Feature\",\"properties\":{\"nodeId\":\"meet\",\"img\":\"pwm\",\"count\":1,\"members\":\"\",\"fresh\":true,\"name\":\"\"}," +
            "\"geometry\":{\"type\":\"Point\",\"coordinates\":[${meet.second},${meet.first}]}}"
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[${features.joinToString(",")}]}"
}

private fun personProps(p: PeerPoint, nowMs: Long): String = buildString {
    val tone = PersonTone.index(p.threadId)
    val badge = peerBadge(p)
    val glyph = peerInitial(p.name).codePointAt(0)
    val face = p.face.orEmpty()
    val img = if (face.isNotEmpty()) "pwf-${face.hashCode().toUInt()}-$tone-$badge" else "pw-$tone-$glyph-$badge"
    append("\"nodeId\":\"").append(escapeJsonString(p.nodeId)).append("\",")
    append("\"threadId\":\"").append(escapeJsonString(p.threadId)).append("\",")
    append("\"name\":\"").append(escapeJsonString(p.name)).append("\",")
    append("\"img\":\"").append(img).append("\",")
    append("\"face\":\"").append(escapeJsonString(face)).append("\",")
    append("\"tone\":").append(tone).append(",")
    append("\"glyph\":").append(glyph).append(",")
    append("\"badge\":\"").append(badge).append("\",")
    append("\"count\":1,")
    append("\"members\":\"\",")
    append("\"precision\":\"").append(p.precision?.name ?: "").append("\",")
    append("\"near\":\"").append(p.near?.name ?: "").append("\",")
    append("\"motion\":\"").append(p.motion?.name ?: "").append("\",")
    append("\"spot\":\"").append(p.spot?.name ?: "").append("\",")
    append("\"low\":").append(p.lowBattery).append(",")
    append("\"lat\":").append(p.lat).append(",")
    append("\"lon\":").append(p.lon).append(",")
    append("\"verified\":").append(p.verified).append(",")
    append("\"fresh\":").append(p.freshAt(nowMs)).append(",")
    append("\"atMs\":").append(p.atMs)
}

private fun groupProps(g: List<PeerPoint>, nowMs: Long): String = buildString {
    val t1 = PersonTone.index(g[0].threadId)
    val t2 = PersonTone.index(g[1].threadId)
    val count = g.size.coerceAtMost(99)
    append("\"nodeId\":\"").append(escapeJsonString("group:" + g.joinToString("|") { it.nodeId })).append("\",")
    append("\"threadId\":\"\",")
    append("\"name\":\"").append(escapeJsonString(g.joinToString(", ") { it.name.substringBefore(' ') })).append("\",")
    append("\"img\":\"pwg-$t1-$t2-$count\",")
    append("\"count\":").append(g.size).append(",")
    append("\"members\":\"").append(escapeJsonString(g.joinToString("|") { it.nodeId })).append("\",")
    append("\"fresh\":").append(g.any { it.freshAt(nowMs) }).append(",")
    append("\"atMs\":").append(g.maxOf { it.atMs })
}

private fun metres(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val dLat = (bLat - aLat) * 111_320.0
    val dLon = (bLon - aLon) * 111_320.0 * kotlin.math.cos(Math.toRadians((aLat + bLat) / 2))
    return kotlin.math.hypot(dLat, dLon)
}

private fun circle(lat: Double, lon: Double, radiusM: Double): List<Pair<Double, Double>> {
    val dLat = radiusM / 111_320.0
    val dLon = dLat / kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.01)
    return (0..32).map { i ->
        val a = 2 * Math.PI * i / 32
        (lon + dLon * kotlin.math.cos(a)) to (lat + dLat * kotlin.math.sin(a))
    }
}

private fun toneColour(mapIsDark: Boolean): Expression {
    val stops = (0 until PersonTone.COUNT).flatMap { i ->
        val t = PersonTone.of(i, mapIsDark)
        listOf(Expression.literal(i), Expression.color(t.fill))
    }.toTypedArray()
    return Expression.match(Expression.get("tone"), Expression.color(TONE), *stops)
}

internal fun installPeerWhere(style: Style, mapIsDark: Boolean) {
    if (style.getSource(PEER_WHERE_SOURCE) == null) style.addSource(GeoJsonSource(PEER_WHERE_SOURCE, EMPTY_FC))
    if (style.getLayer(PEER_WHERE_MARK) != null) return
    val world = Expression.has("world")
    val polygons = Expression.all(Expression.eq(Expression.geometryType(), Expression.literal("Polygon")), Expression.not(world))
    val points = Expression.eq(Expression.geometryType(), Expression.literal("Point"))
    // Моє — бурштином: єдиний акцент застосунку й колір «я» на мапі.
    style.addLayer(
        FillLayer(PEER_WHERE_WORLD, PEER_WHERE_SOURCE).withFilter(world).withProperties(
            PropertyFactory.fillColor(Expression.color(WORLD_AMBER)),
            PropertyFactory.fillOpacity(if (mapIsDark) 0.22f else 0.28f),
            PropertyFactory.fillOutlineColor(Expression.color(WORLD_AMBER)),
        ),
    )
    style.addLayer(
        FillLayer(PEER_WHERE_FILL, PEER_WHERE_SOURCE).withFilter(polygons).withProperties(
            PropertyFactory.fillColor(toneColour(mapIsDark)),
            PropertyFactory.fillOpacity(Expression.switchCase(Expression.get("fresh"), Expression.literal(0.14f), Expression.literal(0.06f))),
        ),
    )
    style.addLayer(
        LineLayer(PEER_WHERE_EDGE, PEER_WHERE_SOURCE).withFilter(polygons).withProperties(
            PropertyFactory.lineColor(toneColour(mapIsDark)),
            PropertyFactory.lineWidth(10f),
            PropertyFactory.lineBlur(9f),
            PropertyFactory.lineOpacity(0.35f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    val palette = MapSymbolPalette.resolve(mapIsDark)
    style.addLayer(
        SymbolLayer(PEER_WHERE_MARK, PEER_WHERE_SOURCE).withFilter(points).withProperties(
            PropertyFactory.iconImage(Expression.concat(Expression.get("img"), Expression.literal(if (mapIsDark) "-n" else "-d"))),
            // Давно не оновлювалось — блідне, а не зникає: людина бачить, що це минуле.
            PropertyFactory.iconOpacity(Expression.switchCase(Expression.get("fresh"), Expression.literal(1f), Expression.literal(0.55f))),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(MAP_TEXT_FONT),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor(palette.textInk),
            PropertyFactory.textHaloColor(palette.textHalo),
            PropertyFactory.textHaloWidth(1f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 1.6f)),
            PropertyFactory.textOptional(true),
        ),
    )
}

internal fun updatePeerWhere(style: Style, context: Context, geoJson: String, visible: Boolean, mapIsDark: Boolean) {
    val source = style.getSourceAs<GeoJsonSource>(PEER_WHERE_SOURCE) ?: return
    if (visible) {
        val density = context.resources.displayMetrics.density
        val suffix = if (mapIsDark) "-n" else "-d"
        val face = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(context, local.phantom.companion.core.design.R.font.manrope_bold)
        }.getOrNull()
        runCatching { org.json.JSONObject(geoJson).getJSONArray("features") }.getOrNull()?.let { fs ->
            for (i in 0 until fs.length()) {
                val props = fs.getJSONObject(i).optJSONObject("properties") ?: continue
                val img = props.optString("img").takeIf { it.isNotEmpty() } ?: continue
                if (style.getImage(img + suffix) != null) continue
                PeerMarks.bake(props, mapIsDark, density, face)?.let { style.addImage(img + suffix, it) }
            }
        }
    }
    val v = if (visible) Property.VISIBLE else Property.NONE
    listOf(PEER_WHERE_WORLD, PEER_WHERE_FILL, PEER_WHERE_EDGE, PEER_WHERE_MARK).forEach { style.getLayer(it)?.setProperties(PropertyFactory.visibility(v)) }
    source.setGeoJson(if (visible) geoJson else EMPTY_FC)
}
