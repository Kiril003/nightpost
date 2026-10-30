package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.data.spatial.GeoPoint
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import java.util.Locale

/**
 * R4.4 — public-transport intelligence on the living map, from REAL OSM data
 * via Overpass (Law 1 — no invented lines, no fake schedules):
 *
 *  - [TransitNearbyTool] `transit.nearby` — stops/stations around a point,
 *    tagged by mode (автобус/трамвай/тролейбус/метро/потяг) with the routes
 *    that serve them where OSM records `route_ref`.
 *  - [TransitRoutesTool] `transit.routes` — the actual route relations
 *    passing nearby, drawn as polylines so "головні маршрути і додатково
 *    інші" don't blur together — each line is one named route.
 *
 * Live timetables ("коли") are NOT in OSM; when asked, the tool is honest
 * that departure times need the operator's transit app — it never fabricates.
 */

private val transitJson = Json { ignoreUnknownKeys = true }

private fun fmtCoord(v: Double): String = String.format(Locale.US, "%.6f", v)

/** Resolve lat/lon from args, else the operator's current location. */
internal fun resolveWorldCoords(
    args: JsonObject,
    locationProvider: LocationContextProvider?,
): Pair<Double, Double>? {
    val lat = args["lat"]?.jsonPrimitive?.doubleOrNull
    val lon = args["lon"]?.jsonPrimitive?.doubleOrNull
    if (lat != null && lon != null) return lat to lon
    val fix = runCatching { locationProvider?.current() }.getOrNull() ?: return null
    return fix.lat to fix.lon
}

private fun transitGlyph(tags: JsonObject?): Pair<String, String> {
    // returns (glyph, human-mode)
    val railway = tags?.get("railway")?.jsonPrimitive?.content
    val station = tags?.get("station")?.jsonPrimitive?.content
    val highway = tags?.get("highway")?.jsonPrimitive?.content
    val amenity = tags?.get("amenity")?.jsonPrimitive?.content
    return when {
        station == "subway" || railway == "subway_entrance" -> "метро" to "метро"
        railway == "tram_stop" -> "трамвай" to "трамвай"
        railway == "station" || railway == "halt" -> "потяг" to "залізниця"
        amenity == "bus_station" -> "автовокзал" to "автовокзал"
        highway == "bus_stop" -> "автобус" to "автобус"
        tags?.get("trolleybus")?.jsonPrimitive?.content == "yes" -> "тролейбус" to "тролейбус"
        else -> "зупинка" to "зупинка"
    }
}

/** `transit.nearby` — stops & stations around a point. */
class TransitNearbyTool(
    private val http: GeoHttp,
    private val sink: SpatialLayerSink,
    private val locationProvider: LocationContextProvider?,
    private val currentThreadId: () -> String?,
    private val endpoint: String = GeoNearbyTool.OVERPASS_ENDPOINT,
) : Tool {

    override val id = "transit.nearby"
    override val description =
        "Зупинки і станції громадського транспорту поруч (реальні дані OSM/Overpass): автобус, трамвай, тролейбус, метро, потяг. За замовчуванням навколо поточної локації. Кладе живий шар «транспорт поруч» на мапу і повертає map для віджета. Розкладу (коли саме) в OSM немає — не вигадуй час."
    override val argsSchema: JsonObject = buildJsonObject {
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
        put("radius_m", JsonPrimitive("number?"))
    }
    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = worldManifest("tool.transit.nearby", "Транспорт поруч")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - start }
        val (lat, lon) = resolveWorldCoords(args, locationProvider)
            ?: return ToolResult.Err(
                "no_location",
                "немає координат: локація вимкнена і lat/lon не передані. " +
                    "Наступний крок — виклич geo.search з назвою місця і передай його lat/lon сюди.",
                elapsed(),
            )
        val radius = (args["radius_m"]?.jsonPrimitive?.intOrNull ?: 600).coerceIn(100, 3_000)

        val ql = "[out:json][timeout:20];(" +
            "node[\"highway\"=\"bus_stop\"](around:$radius,${fmtCoord(lat)},${fmtCoord(lon)});" +
            "node[\"railway\"~\"^(tram_stop|station|halt|subway_entrance)\$\"](around:$radius,${fmtCoord(lat)},${fmtCoord(lon)});" +
            "node[\"amenity\"=\"bus_station\"](around:$radius,${fmtCoord(lat)},${fmtCoord(lon)});" +
            "node[\"public_transport\"=\"station\"](around:$radius,${fmtCoord(lat)},${fmtCoord(lon)});" +
            ");out body 80;"
        val body = http.postText(endpoint, "data=" + java.net.URLEncoder.encode(ql, "UTF-8"))
            ?: return ToolResult.Err(
                "geo_unavailable",
                "Мапа не відповіла — списку зупинок зараз немає. Скажи це оператору прямо " +
                    "і спробуй ще раз за хвилину; вигадувати зупинки не можна.",
                elapsed(),
            )

        // A parse failure used to read back as "зупинок не знайдено" — the same
        // sentence as an honestly empty radius. They are not the same answer.
        val parsed = runCatching {
            transitJson.parseToJsonElement(body).jsonObject["elements"]?.jsonArray
                ?.mapNotNull { el ->
                    val o = el.jsonObject
                    val pLat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    val pLon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    val tags = o["tags"]?.jsonObject
                    val (glyph, mode) = transitGlyph(tags)
                    val name = tags?.get("name")?.jsonPrimitive?.content ?: mode
                    val routes = tags?.get("route_ref")?.jsonPrimitive?.content
                        ?: tags?.get("ref")?.jsonPrimitive?.content
                    WorldPoint(
                        lat = pLat, lon = pLon, label = name, glyph = glyph,
                        detail = buildString {
                            append(mode)
                            if (!routes.isNullOrBlank()) append(" · №").append(routes)
                        },
                        color = TRANSIT_COLOR,
                    )
                }.orEmpty()
        }
        val points = parsed.getOrElse {
            return ToolResult.Err(
                "geo_unreadable",
                "Відповідь мапи не прочиталась — це НЕ означає, що зупинок нема. " +
                    "Спробуй ще раз або звузь радіус.",
                elapsed(),
            )
        }

        if (points.isEmpty()) {
            return ToolResult.Ok(
                "У радіусі $radius м на мапі немає жодної зупинки. Це прогалина мапи, а не доказ, " +
                    "що зупинок нема — можна повторити з більшим radius_m.",
                buildJsonObject {
                    put("results", buildJsonArray {})
                    put("radius_m", radius)
                    putProvenance("OSM · Overpass", start, "розкладів OSM не містить")
                },
                elapsed(),
            )
        }

        val layerId = sink.pushWorldLayer(
            title = "транспорт поруч", kind = "transit", points = points,
            color = TRANSIT_COLOR, glyph = "транспорт",
            ownerThreadId = currentThreadId(), ttlMs = 30 * 60_000L,
        )
        val byModeCounts = points.groupingBy { it.glyph ?: "зупинка" }.eachCount()
        val structured = buildJsonObject {
            layerId?.let { put("layer_id", it) }
            put("results", worldResults(points))
            put("count", points.size)
            put("radius_m", radius)
            put("by_mode", buildJsonObject { byModeCounts.forEach { (k, v) -> put(k, v) } })
            put("schedule", "невідомий")
            putProvenance("OSM · Overpass", start, "розкладів OSM не містить")
            put("map", worldMapObject(lat, lon, 15.0, points))
        }
        val byMode = byModeCounts.entries.joinToString { "${it.key}: ${it.value}" }
        return ToolResult.Ok(
            "Знайшов ${points.size} зупинок у радіусі $radius м ($byMode). Поклав шар «транспорт поруч» на мапу. " +
                "Розкладу мапа не знає — часу відправлення не називай.",
            structured,
            elapsed(),
        )
    }

    companion object {
        const val TRANSIT_COLOR = "#22C55E" // transit green
    }
}

/** `transit.routes` — route relations passing near a point, as polylines. */
class TransitRoutesTool(
    private val http: GeoHttp,
    private val sink: SpatialLayerSink,
    private val locationProvider: LocationContextProvider?,
    private val currentThreadId: () -> String?,
    private val endpoint: String = GeoNearbyTool.OVERPASS_ENDPOINT,
) : Tool {

    override val id = "transit.routes"
    override val description =
        "Маршрути громадського транспорту, що проходять поруч (реальні route-відносини OSM), кожен окремою лінією зі своєю назвою/№ — головні і додаткові не зливаються. mode?: bus|tram|trolleybus|subway|train (без нього — всі). Кладе шар «маршрути транспорту» на мапу."
    override val argsSchema: JsonObject = buildJsonObject {
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
        put("radius_m", JsonPrimitive("number?"))
        put("mode", JsonPrimitive("string?"))
    }
    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = worldManifest("tool.transit.routes", "Маршрути транспорту")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - start }
        val (lat, lon) = resolveWorldCoords(args, locationProvider)
            ?: return ToolResult.Err(
                "no_location",
                "немає координат: локація вимкнена і lat/lon не передані. " +
                    "Наступний крок — знайди місце через geo.search і передай його lat/lon сюди.",
                elapsed(),
            )
        val radius = (args["radius_m"]?.jsonPrimitive?.intOrNull ?: 400).coerceIn(100, 2_000)
        val rawMode = args["mode"]?.jsonPrimitive?.content?.lowercase()?.trim()
        val modeFilter = when (rawMode) {
            "bus", "автобус" -> "bus"
            "tram", "трамвай" -> "tram"
            "trolleybus", "тролейбус" -> "trolleybus"
            "subway", "метро" -> "subway"
            "train", "потяг", "залізниця" -> "train"
            else -> null
        }
        val routeRegex = modeFilter ?: "bus|tram|trolleybus|subway|light_rail|train"

        val ql = "[out:json][timeout:25];" +
            "relation[\"route\"~\"^($routeRegex)\$\"](around:$radius,${fmtCoord(lat)},${fmtCoord(lon)});" +
            "out geom 40;"
        val body = http.postText(endpoint, "data=" + java.net.URLEncoder.encode(ql, "UTF-8"))
            ?: return ToolResult.Err(
                "geo_unavailable",
                "Мапа не відповіла — маршрутів зараз не дістати. Скажи це прямо і повтори за хвилину; " +
                    "маршрути з голови не називай.",
                elapsed(),
            )

        val lines = runCatching { parseRouteLines(body) }.getOrElse {
            return ToolResult.Err(
                "geo_unreadable",
                "Відповідь мапи не прочиталась — це не означає, що маршрутів нема. Повтори виклик.",
                elapsed(),
            )
        }
        if (lines.isEmpty()) {
            val scope = modeFilter?.let { " для режиму «$rawMode»" }.orEmpty()
            return ToolResult.Ok(
                "У радіусі $radius м$scope на мапі немає жодного маршруту транспорту. " +
                    "Можна повторити з більшим radius_m або без mode.",
                buildJsonObject {
                    put("results", buildJsonArray {})
                    put("radius_m", radius)
                    modeFilter?.let { put("mode", it) }
                    putProvenance("OSM · Overpass", start, "розкладів OSM не містить")
                },
                elapsed(),
            )
        }

        val layerId = sink.pushWorldLayer(
            title = "маршрути транспорту", kind = "transit", points = emptyList(), lines = lines,
            color = TransitNearbyTool.TRANSIT_COLOR, glyph = "маршрути",
            ownerThreadId = currentThreadId(), ttlMs = 60 * 60_000L,
        )
        val center = worldCenter(lat, lon, emptyList(), lines)
        val structured = buildJsonObject {
            layerId?.let { put("layer_id", it) }
            put("results", worldLineResults(lines))
            put("count", lines.size)
            put("radius_m", radius)
            modeFilter?.let { put("mode", it) }
            put("schedule", "невідомий")
            putProvenance("OSM · Overpass", start, "розкладів OSM не містить")
            put("routes", buildJsonArray {
                lines.take(20).forEach { l -> add(JsonPrimitive(l.label ?: "маршрут")) }
            })
            put("map", worldMapObject(center.first, center.second, 13.0, emptyList(), lines))
        }
        return ToolResult.Ok(
            "Знайшов ${lines.size} маршрутів поруч: " +
                lines.take(6).joinToString { it.label ?: "?" } +
                ". Поклав шар «маршрути транспорту» на мапу. Розкладу мапа не знає.",
            structured,
            elapsed(),
        )
    }

    /** Concatenate each route relation's member-way geometry into one line. */
    private fun parseRouteLines(body: String): List<WorldLine> {
        val elements = transitJson.parseToJsonElement(body).jsonObject["elements"]?.jsonArray
            ?: return emptyList()
        return elements.mapNotNull { el ->
            val o = el.jsonObject
            if (o["type"]?.jsonPrimitive?.content != "relation") return@mapNotNull null
            val tags = o["tags"]?.jsonObject
            // Both branches used to return "№$ref": the route name was read and
            // thrown away, so «№15» never said where №15 goes.
            val label = tags?.get("ref")?.jsonPrimitive?.content?.let { ref ->
                val name = tags["name"]?.jsonPrimitive?.content
                if (name != null && !name.contains(ref)) "№$ref · $name" else "№$ref"
            } ?: tags?.get("name")?.jsonPrimitive?.content ?: return@mapNotNull null
            val colorTag = tags?.get("colour")?.jsonPrimitive?.content?.takeIf { it.startsWith("#") }
            val pts = buildList {
                o["members"]?.jsonArray?.forEach { m ->
                    val mo = m.jsonObject
                    if (mo["type"]?.jsonPrimitive?.content != "way") return@forEach
                    mo["geometry"]?.jsonArray?.forEach { g ->
                        val go = g.jsonObject
                        val la = go["lat"]?.jsonPrimitive?.doubleOrNull
                        val lo = go["lon"]?.jsonPrimitive?.doubleOrNull
                        if (la != null && lo != null) add(GeoPoint(la, lo))
                    }
                }
            }
            if (pts.size < 2) return@mapNotNull null
            WorldLine(points = pts, label = label, color = colorTag)
        }
    }
}
