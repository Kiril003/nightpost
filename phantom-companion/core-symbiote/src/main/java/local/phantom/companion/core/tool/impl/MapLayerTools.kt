package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.GeoPoint
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.data.spatial.SpatialLine
import local.phantom.companion.core.data.spatial.SpatialMarker
import local.phantom.companion.core.data.spatial.SpatialRegion
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * R4.1 — the spatial spine's tool family. The model stops "drawing" a
 * throwaway map widget and instead pushes NAMED, PERSISTENT layers the
 * operator can list, toggle, and clear:
 *
 *  - [MapShowTool]  `map.show`  — push a named layer (markers/lines/regions).
 *  - [MapFocusTool] `map.focus` — fly the open map's camera to a layer/point.
 *  - [MapClearTool] `map.clear` — remove a layer (or all this thread's phantom
 *    layers).
 *
 * Layers are thread-scoped for real via [currentThreadId] (the same
 * dependency-injected-lambda pattern LocationTool uses for GPS), so opening
 * the map from a thread shows that thread's layers first. `map.show` also
 * echoes the widget-ready `map` object as structured output, so the inline
 * MapWidget in the stream and the full MapScreen render the same truth.
 */

private fun mapLayerManifest(id: String, name: String) = FeatureManifest(
    id = id,
    tier = 0,
    displayName = name,
    erodesOperatorAgency = false,
    exfiltratesPrivate = false,
    actsWithoutConsent = false,
    abusableAgainstWeak = false,
    sunsetAfterDaysIdle = 90,
    surfaces = setOf(FeatureManifest.Surface.STREAM),
)

private fun parseOrigin(token: String?): LayerOrigin = when (token?.lowercase()?.trim()) {
    "world" -> LayerOrigin.WORLD
    "operator" -> LayerOrigin.OPERATOR
    "system" -> LayerOrigin.SYSTEM
    else -> LayerOrigin.PHANTOM
}

/**
 * Reads the widget-ready `map` object (same shape as the MapWidget payload:
 * latitude/longitude/zoom + markers/circles/polylines) into a
 * [SpatialLayerSpec]. Missing or malformed collections degrade to empty
 * rather than throwing (Law 1 — an honest partial layer beats a crash).
 */
internal fun mapObjectToSpec(
    map: JsonObject,
    title: String,
    kind: String,
    origin: LayerOrigin,
    ownerThreadId: String?,
    color: String?,
    glyph: String?,
    ttlMs: Long?,
): SpatialLayerSpec {
    val markers = map["markers"]?.jsonArray?.mapNotNull { el ->
        val o = el.jsonObject
        val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        SpatialMarker(
            lat = lat,
            lon = lon,
            label = o["label"]?.jsonPrimitive?.contentOrNull(),
            glyph = o["glyph"]?.jsonPrimitive?.contentOrNull(),
            color = o["color"]?.jsonPrimitive?.contentOrNull(),
            detail = o["detail"]?.jsonPrimitive?.contentOrNull(),
            bearing = o["bearing"]?.jsonPrimitive?.doubleOrNull,
        )
    }.orEmpty()

    val regions = map["circles"]?.jsonArray?.mapNotNull { el ->
        val o = el.jsonObject
        val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val r = o["radiusM"]?.jsonPrimitive?.doubleOrNull
            ?: o["radius_m"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        SpatialRegion(lat, lon, r, o["color"]?.jsonPrimitive?.contentOrNull(), o["label"]?.jsonPrimitive?.contentOrNull())
    }.orEmpty()

    val lines = map["polylines"]?.jsonArray?.mapNotNull { el ->
        val o = el.jsonObject
        val pts = o["points"]?.jsonArray?.mapNotNull { p ->
            val po = p.jsonObject
            val la = po["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lo = po["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            GeoPoint(la, lo)
        }.orEmpty()
        if (pts.size < 2) return@mapNotNull null
        SpatialLine(
            points = pts,
            color = o["color"]?.jsonPrimitive?.contentOrNull(),
            width = o["width"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 4f,
            label = o["label"]?.jsonPrimitive?.contentOrNull(),
        )
    }.orEmpty()

    return SpatialLayerSpec(
        title = title,
        origin = origin,
        kind = kind,
        ownerThreadId = ownerThreadId,
        markers = markers,
        lines = lines,
        regions = regions,
        color = color,
        glyph = glyph,
        ttlMs = ttlMs,
    )
}

private fun JsonPrimitive.contentOrNull(): String? = content.takeIf { it.isNotBlank() }

/** `map.show` — push a named, persistent layer onto the map. */
class MapShowTool(
    private val sink: SpatialLayerSink,
    private val currentThreadId: () -> String?,
) : Tool {

    override val id = "map.show"

    override val description =
        "Показати на мапі іменований шар (маркери/лінії/кола) від Nightpost. Передай title (назва шару) і map — обʼєкт як для віджета мапи (latitude, longitude, zoom, markers[], circles[], polylines[]). Шар зберігається, його видно у списку «від Nightpost», його можна перемкнути чи прибрати. ttl_min — авто-зникнення для живих даних."

    override val argsSchema: JsonObject = buildJsonObject {
        put("title", JsonPrimitive("string"))
        put("map", JsonPrimitive("object"))
        put("kind", JsonPrimitive("string?"))
        put("origin", JsonPrimitive("string?"))
        put("color", JsonPrimitive("string?"))
        put("ttl_min", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = mapLayerManifest("tool.map.show", "Шар на мапі")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        val title = args["title"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Err(
                "bad_args",
                "шар не показав: не передано title. Шар без назви оператор не впізнає у списку — дай коротку назву.",
                dur(),
            )
        val map = args["map"]?.let { it as? JsonObject }
            ?: return ToolResult.Err(
                "bad_args",
                "шар не показав: не передано обʼєкт map. Потрібен {markers:[{lat,lon,label}], " +
                    "circles:[{lat,lon,radius_m}], polylines:[{points:[{lat,lon}]}]} — хоч щось одне.",
                dur(),
            )
        val kind = args["kind"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() } ?: "custom"
        val origin = parseOrigin(args["origin"]?.jsonPrimitive?.content)
        val color = args["color"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val ttlMs = args["ttl_min"]?.jsonPrimitive?.doubleOrNull?.let { (it * 60_000).toLong() }
            ?.takeIf { it > 0 }

        val spec = mapObjectToSpec(
            map = map,
            title = title,
            kind = kind,
            origin = origin,
            ownerThreadId = currentThreadId(),
            color = color,
            glyph = null,
            ttlMs = ttlMs,
        )
        if (spec.elementCount == 0) {
            return ToolResult.Err(
                code = "empty_layer",
                message = "шару «$title» не показав: у map не розібралось жодного маркера, кола чи лінії. " +
                    "Кожен маркер потребує lat і lon числами, коло — ще radius_m, лінія — щонайменше " +
                    "дві точки. Порожній шар я не малюю.",
                durationMs = dur(),
            )
        }

        val layerId = sink.push(spec)
        // Nudge the open map to frame the new layer.
        val centre = centroid(spec)
        centre?.let { (lat, lon) ->
            runCatching { sink.requestFocus(FocusTarget(lat, lon, layerId = layerId)) }
        }

        val structured = buildJsonObject {
            put("layer_id", layerId)
            put("title", title)
            put("elements", spec.elementCount)
            put("markers", spec.markers.size)
            put("lines", spec.lines.size)
            put("regions", spec.regions.size)
            put("origin", origin.name.lowercase())
            centre?.let { put("centre_lat", it.first); put("centre_lon", it.second) }
            ttlMs?.let { put("ttl_min", it / 60_000) }
            put("permanent", ttlMs == null)
            // Echo the widget-ready map so the inline MapWidget renders too.
            put("map", map)
        }
        return ToolResult.Ok(
            output = "Показав шар «$title» (${spec.elementCount} обʼєктів) на мапі. " +
                (if (ttlMs != null) "Зникне за ${ttlMs / 60_000} хв. " else "Лишиться, доки не прибереш. ") +
                "Він у списку «від Nightpost», id=$layerId.",
            structured = structured,
            durationMs = dur(),
        )
    }
}

/** `map.focus` — move the open map camera to a saved layer or a point. */
class MapFocusTool(
    private val sink: SpatialLayerSink,
    private val currentThreadId: () -> String?,
) : Tool {

    override val id = "map.focus"

    override val description =
        "Навести камеру відкритої мапи на точку (lat/lon, опційно zoom) або на збережений шар (layer_id — камера стане на його центр)."

    override val argsSchema: JsonObject = buildJsonObject {
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
        put("zoom", JsonPrimitive("number?"))
        put("layer_id", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = mapLayerManifest("tool.map.focus", "Фокус мапи")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        val zoom = args["zoom"]?.jsonPrimitive?.doubleOrNull
        val layerId = args["layer_id"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val lat = args["lat"]?.jsonPrimitive?.doubleOrNull
        val lon = args["lon"]?.jsonPrimitive?.doubleOrNull

        var aimedAt = "координати"
        val target = when {
            lat != null && lon != null -> FocusTarget(lat, lon, zoom, layerId)
            layerId != null -> {
                val known = runCatching { sink.list(null) }.getOrDefault(emptyList())
                val layer = sink.list(currentThreadId()).firstOrNull { it.id == layerId }
                    ?: known.firstOrNull { it.id == layerId }
                    ?: return ToolResult.Err(
                        "unknown_layer",
                        "камеру не рухав: шару $layerId немає — його прибрали або він встиг зникнути за TTL. " +
                            (if (known.isEmpty()) {
                                "Живих шарів зараз узагалі немає."
                            } else {
                                "Живі зараз: ${known.take(5).joinToString(", ") { "${it.spec.title} (${it.id})" }}."
                            }),
                        dur(),
                    )
                val c = centroid(layer.spec)
                    ?: return ToolResult.Err(
                        "empty_layer",
                        "камеру не рухав: у шарі «${layer.spec.title}» немає жодного обʼєкта з координатами, " +
                            "тож наводити нема на що. Передай lat/lon напряму.",
                        dur(),
                    )
                aimedAt = "шар «${layer.spec.title}»"
                FocusTarget(c.first, c.second, zoom, layerId)
            }
            else -> return ToolResult.Err(
                "bad_args",
                "камеру не рухав: не сказано куди. Передай lat і lon, або layer_id збереженого шару.",
                dur(),
            )
        }
        sink.requestFocus(target)
        return ToolResult.Ok(
            // Locale.US: uk_UA prints %.5f with a comma and the pair reads as four numbers.
            output = "Навів камеру мапи на $aimedAt — " +
                String.format(java.util.Locale.US, "%.5f, %.5f", target.lat, target.lon) + ".",
            structured = buildJsonObject {
                put("lat", target.lat)
                put("lon", target.lon)
                zoom?.let { put("zoom", it) }
                layerId?.let { put("layer_id", it) }
                put("aimed_at", aimedAt)
            },
            durationMs = dur(),
        )
    }
}

/** `map.clear` — remove one layer, or all this thread's phantom+world layers. */
class MapClearTool(
    private val sink: SpatialLayerSink,
    private val currentThreadId: () -> String?,
) : Tool {

    override val id = "map.clear"

    override val description =
        "Прибрати шар з мапи. Передай layer_id для конкретного шару; без нього — прибирає всі шари Nightpost (свої й зі світу) цього діалогу."

    override val argsSchema: JsonObject = buildJsonObject {
        put("layer_id", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = mapLayerManifest("tool.map.clear", "Прибрати шар")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        val layerId = args["layer_id"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        return if (layerId != null) {
            val removed = sink.clear(layerId)
            ToolResult.Ok(
                output = if (removed) {
                    "Прибрав шар $layerId з мапи."
                } else {
                    "Нічого не прибирав: шару $layerId на мапі немає — його вже прибрали або він зник за TTL."
                },
                structured = buildJsonObject {
                    put("removed", if (removed) 1 else 0)
                    put("layer_id", layerId)
                    put("scope", "один шар")
                },
                durationMs = dur(),
            )
        } else {
            val thread = currentThreadId()
            val removedPhantom = sink.clearThread(thread, LayerOrigin.PHANTOM)
            val removedWorld = sink.clearThread(thread, LayerOrigin.WORLD)
            val total = removedPhantom + removedWorld
            ToolResult.Ok(
                // «Прибрав 0 шар(и)» read as an action taken; nothing happened.
                output = if (total == 0) {
                    "Нічого не прибирав: шарів Nightpost у цьому діалозі й не було. " +
                        "Шари оператора й системні я не чіпаю."
                } else {
                    "Прибрав $total ${plural(total)} Nightpost з мапи цього діалогу " +
                        "($removedPhantom від Nightpost, $removedWorld зі світу). " +
                        "Шари оператора й системні лишились."
                },
                structured = buildJsonObject {
                    put("removed", total)
                    put("removed_phantom", removedPhantom)
                    put("removed_world", removedWorld)
                    put("scope", "шари Nightpost цього діалогу")
                },
                durationMs = dur(),
            )
        }
    }
}

/** «1 шар», «2 шари», «5 шарів» — a count read aloud must decline. */
private fun plural(n: Int): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> "шарів"
        mod10 == 1 -> "шар"
        mod10 in 2..4 -> "шари"
        else -> "шарів"
    }
}

/** Rough geometric centre of a spec's elements — for auto-focus. */
private fun centroid(spec: SpatialLayerSpec): Pair<Double, Double>? {
    val pts = buildList {
        spec.markers.forEach { add(it.lat to it.lon) }
        spec.regions.forEach { add(it.lat to it.lon) }
        spec.lines.forEach { l -> l.points.forEach { add(it.lat to it.lon) } }
    }
    if (pts.isEmpty()) return null
    return pts.sumOf { it.first } / pts.size to pts.sumOf { it.second } / pts.size
}
