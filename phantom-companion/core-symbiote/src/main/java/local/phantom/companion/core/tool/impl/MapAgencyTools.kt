package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.data.evac.ShelterRegistryVerdict
import local.phantom.companion.core.data.evac.ShelterTruth
import local.phantom.companion.core.data.poi.PoiRepository
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.GeoPoint
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.data.spatial.SpatialLine
import local.phantom.companion.core.data.spatial.SpatialMarker
import local.phantom.companion.core.net.map.GeoEndpoints
import local.phantom.companion.core.net.map.GeocodeDialect
import local.phantom.companion.core.net.map.OsrmClient
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import java.util.Locale

/**
 * M-I01 — map agency: the same three things the operator's finger can do on
 * the live full-screen map (long-press a point, route to it, flood "поруч"
 * categories — see MAP_MASTERPLAN.md Wave 1) are now Fantom tool-verbs too
 * (Law 2 — dual control). Unlike [GeoSearchTool]/[GeoNearbyTool]/[GeoRouteTool]
 * (which answer INTO the chat bubble's inline MapWidget), these three push
 * onto [SpatialLayerSink] — the same living-layer stack MapScreen already
 * renders — and (M-I01 fix) fire `requestMapOpen` so the app actually
 * navigates to the map when the operator asked from chat, instead of writing
 * a layer nobody was looking at.
 *
 *  - [MapPointTool]  `map.point`  — drop a pin (with reverse-geocoded label).
 *  - [MapRouteLiveTool] `map.route` — OSRM route, drawn on the open map.
 *  - [MapNearbyTool] `map.nearby` — flood a category, same 8 kinds as the
 *    "поруч" chip rail.
 */

private fun agencyManifest(id: String, name: String) = FeatureManifest(
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

private fun fmtKm(meters: Double): String =
    if (meters >= 1000) String.format(Locale.US, "%.1f км", meters / 1000.0)
    else "${meters.toInt()} м"

private fun fmtMin(seconds: Double): String {
    val min = (seconds / 60.0).toInt()
    return if (min < 60) "$min хв" else String.format(Locale.US, "%dг %02dхв", min / 60, min % 60)
}

internal fun resolveProfile(raw: String?): RouteProfile = when (raw?.lowercase()?.trim()) {
    "авто", "car", "driving", "машина" -> RouteProfile.CAR
    "вело", "bike", "cycling", "велосипед" -> RouteProfile.BIKE
    else -> RouteProfile.FOOT
}

/** The same 8 "поруч" kinds as MapScreen's NearbyRail — one source of truth for synonyms. */
internal data class NearbyKind(val label: String, val glyph: String, val selector: String, val synonyms: Set<String>)

internal val NEARBY_KINDS = listOf(
    NearbyKind("укриття", "🛡", ShelterTruth.BOMB_SHELTER_SELECTOR, setOf("укриття", "shelter", "бомбосховище")),
    NearbyKind("аптеки", "💊", "amenity=pharmacy", setOf("аптеки", "аптека", "pharmacy")),
    NearbyKind("заправки", "⛽", "amenity=fuel", setOf("заправки", "заправка", "fuel", "gas", "petrol")),
    NearbyKind("банкомати", "🏧", "amenity=atm", setOf("банкомати", "банкомат", "atm", "cash")),
    NearbyKind("магазини", "🛒", "shop~\"supermarket|convenience\"", setOf("магазини", "магазин", "shop", "supermarket", "store")),
    NearbyKind("їжа", "☕", "amenity~\"cafe|restaurant|fast_food\"", setOf("їжа", "кафе", "ресторан", "food", "cafe", "restaurant")),
    NearbyKind("лікарні", "🏥", "amenity~\"hospital|clinic\"", setOf("лікарні", "лікарня", "hospital", "clinic", "медицина")),
    NearbyKind("вода", "🚰", "amenity=drinking_water", setOf("вода", "питна вода", "water", "drinking_water")),
)

internal fun resolveNearbyKind(raw: String): NearbyKind? {
    val q = raw.trim().lowercase()
    return NEARBY_KINDS.firstOrNull { q == it.label || q in it.synonyms }
}

/**
 * Назва → координата, за правилом [GeoSearchTool]: або налаштована оператором
 * основа, або чесне «не знаю».
 *
 * Тут стояли ще два тіри, зашиті повз конфігурацію — `photon.komoot.io` і
 * публічний Nominatim, — і вони спрацьовували БЕЗУМОВНО, навіть коли
 * геокодер вимкнено. Кличе це не людина: інструменти нижче викликає ШІ,
 * розбираючи репліку в чаті, тож назва місця, яку оператор згадав уголос,
 * їхала на чужий сервер без жодного натиску. Вимикач у налаштуваннях при
 * цьому нічого не вимикав.
 */
internal suspend fun resolveSmartCoordinate(
    latArg: Double?,
    lonArg: Double?,
    queryArg: String?,
    http: GeoHttp?,
): Pair<Double, Double>? {
    if (latArg != null && lonArg != null && latArg in -90.0..90.0 && lonArg in -180.0..180.0) {
        return latArg to lonArg
    }
    if (queryArg.isNullOrBlank() || http == null) return null
    val config = GeoEndpoints.config
    val endpoint = config.geocodeBaseUrl ?: return null
    val url = GeocodeDialect.searchUrl(endpoint, queryArg.trim(), 1, config.geocoderKind)
    val body = runCatching { http.getNominatim(url) }.getOrNull() ?: return null
    val first = GeocodeDialect.parseSearch(body, config.geocoderKind).firstOrNull() ?: return null
    return first.lat to first.lon
}

/** Чому назву не вдалось перетворити на точку — різні хвороби, різні ліки. */
internal fun smartCoordinateRefusal(queryArg: String?): String =
    when {
        queryArg.isNullOrBlank() ->
            "потрібні координати (lat/lon) або назва чи адреса місця."
        GeoEndpoints.config.geocodeBaseUrl == null ->
            "онлайн-геокодер не налаштовано, а назву «${queryArg.trim()}» я сам у координату " +
                "не перетворю. Скажи оператору прямо: Налаштування → Мапа → Геосервіси, " +
                "або дай координати. Чужий сервер я про його місця не питаю."
        else ->
            "геокодер не впізнав «${queryArg.trim()}». Це збій або невідома назва, " +
                "а НЕ «такого місця немає» — уточни назву чи передай координати."
    }

private fun extractTargetQuery(args: JsonObject, vararg keys: String): String? {
    for (k in keys) {
        val el = args[k]
        if (el is JsonPrimitive && el.isString && el.content.isNotBlank()) {
            return el.content.trim()
        }
        if (el is JsonObject) {
            val name = el["name"]?.jsonPrimitive?.content
                ?: el["address"]?.jsonPrimitive?.content
                ?: el["query"]?.jsonPrimitive?.content
                ?: el["title"]?.jsonPrimitive?.content
            if (!name.isNullOrBlank()) return name.trim()
        }
    }
    return null
}

private fun extractCoordPair(args: JsonObject, latKey: String, lonKey: String, objKey: String): Pair<Double?, Double?> {
    val lat = args[latKey]?.jsonPrimitive?.doubleOrNull
    val lon = args[lonKey]?.jsonPrimitive?.doubleOrNull
    if (lat != null && lon != null) return lat to lon
    val obj = args[objKey]?.let { if (it is JsonObject) it else null }
    if (obj != null) {
        val objLat = obj["lat"]?.jsonPrimitive?.doubleOrNull ?: obj["latitude"]?.jsonPrimitive?.doubleOrNull
        val objLon = obj["lon"]?.jsonPrimitive?.doubleOrNull ?: obj["longitude"]?.jsonPrimitive?.doubleOrNull
        if (objLat != null && objLon != null) return objLat to objLon
    }
    return null to null
}

/** `map.point` — drop a pin on the OPEN map, mirroring a long-press. */
class MapPointTool(
    private val http: GeoHttp,
    private val sink: SpatialLayerSink,
    private val currentThreadId: () -> String?,
    private val requestMapOpen: () -> Unit = {},
) : Tool {

    override val id = "map.point"

    override val description =
        "Поставити мітку на живій повноекранній мапі оператора (те саме, що довгий тап пальцем) — і вивести мапу на екран, якщо зараз відкритий чат. Уживай це, а не geo.search, коли треба, щоб оператор ПОБАЧИВ точку на мапі, а не просто прочитав координати. Передай lat/lon або назву/адресу (query/address/place). Без label сам спробує підписати точку реверс-геокодингом. Мітка зʼявляється у списку «від Nightpost» — її можна прибрати map.clear."

    override val argsSchema: JsonObject = buildJsonObject {
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
        put("query", JsonPrimitive("string?"))
        put("address", JsonPrimitive("string?"))
        put("label", JsonPrimitive("string?"))
        put("color", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK)
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = agencyManifest("tool.map.point", "Мітка на мапі")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        val (explicitLat, explicitLon) = extractCoordPair(args, "lat", "lon", "point")
        val textQuery = extractTargetQuery(args, "query", "address", "place", "target", "label")
        val resolved = resolveSmartCoordinate(explicitLat, explicitLon, textQuery, http)
        if (resolved == null) {
            return ToolResult.Err(
                "bad_args",
                "Мітку не поставив: " + smartCoordinateRefusal(textQuery),
                dur(),
            )
        }
        val (lat, lon) = resolved
        val color = args["color"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val givenLabel = args["label"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() } ?: textQuery
        var label = givenLabel

        if (label == null) {
            val config = GeoEndpoints.config
            val geocodeBase = config.geocodeBaseUrl
            val body = geocodeBase?.let {
                http.getNominatim(GeocodeDialect.reverseUrl(it, lat, lon, config.geocoderKind))
            }
            label = body
                ?.let { GeocodeDialect.parseReverse(it, config.geocoderKind) }
                ?.name?.substringBefore(",")?.trim()?.takeIf { it.isNotEmpty() }
        }
        val title = label
            ?: "точка ${String.format(Locale.US, "%.5f", lat)}, ${String.format(Locale.US, "%.5f", lon)}"
        val labelSource = when {
            givenLabel != null -> "передано у виклику"
            label != null -> "зворотний геокодер"
            else -> "координати — назви місця немає"
        }

        val spec = SpatialLayerSpec(
            title = title,
            origin = LayerOrigin.PHANTOM,
            kind = "point",
            ownerThreadId = currentThreadId(),
            markers = listOf(SpatialMarker(lat = lat, lon = lon, label = title, color = color)),
            color = color,
            ttlMs = null,
        )
        val layerId = sink.push(spec)
        runCatching { sink.requestFocus(FocusTarget(lat, lon, layerId = layerId)) }
        requestMapOpen()

        return ToolResult.Ok(
            output = "Поставив мітку «$title» на мапі й навів на неї камеру." +
                (if (label == null) " Назви місця немає — підпис із координат." else ""),
            structured = buildJsonObject {
                put("layer_id", layerId)
                put("lat", lat)
                put("lon", lon)
                put("label", title)
                put("label_source", labelSource)
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }
}

/** `map.route` — OSRM route drawn as a live layer on the OPEN map. */
class MapRouteLiveTool(
    private val sink: SpatialLayerSink,
    private val locationProvider: LocationContextProvider?,
    private val currentThreadId: () -> String?,
    private val osrm: OsrmClient = OsrmClient(),
    private val http: GeoHttp? = null,
    private val requestMapOpen: () -> Unit = {},
    private val offlineRouteCalculator: (suspend (profile: RouteProfile, waypoints: List<Pair<Double, Double>>) -> List<RouteVariant>?)? = null,
) : Tool {

    override val id = "map.route"

    override val description =
        "Прокласти маршрут на живій повноекранній мапі оператора і вивести мапу на екран, якщо зараз відкритий чат (не в бульбашці чату — для короткої відповіді текстом є geo.route, але коли треба ПОКАЗАТИ маршрут — уживай саме цей; для порівняння варіантів є route.compare). Приймає координати (to_lat/to_lon) або назву/адресу цілі (to/destination/query). from за замовчуванням — поточна локація оператора. via — необовʼязкові проміжні точки формату \"lat,lon;lat,lon\". Профіль: пішки (default) / авто / вело. Малює лінію+старт/фініш на живій мапі; повертає покрокові маневри (structured.steps)."

    override val argsSchema: JsonObject = buildJsonObject {
        put("to", JsonPrimitive("string?"))
        put("to_lat", JsonPrimitive("number?"))
        put("to_lon", JsonPrimitive("number?"))
        put("from", JsonPrimitive("string?"))
        put("from_lat", JsonPrimitive("number?"))
        put("from_lon", JsonPrimitive("number?"))
        put("profile", JsonPrimitive("string?"))
        put("via", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = agencyManifest("tool.map.route", "Маршрут на мапі")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        val (rawToLat, rawToLon) = extractCoordPair(args, "to_lat", "to_lon", "to")
        val toQuery = extractTargetQuery(args, "to", "destination", "target", "address", "query")
        val resolvedTo = resolveSmartCoordinate(rawToLat, rawToLon, toQuery, http)
        if (resolvedTo == null) {
            return ToolResult.Err(
                "bad_args",
                "Маршрут не клав: " + smartCoordinateRefusal(toQuery),
                dur(),
            )
        }
        val (toLat, toLon) = resolvedTo

        val (rawFromLat, rawFromLon) = extractCoordPair(args, "from_lat", "from_lon", "from")
        val fromQuery = extractTargetQuery(args, "from", "origin", "start")
        val resolvedFrom = resolveSmartCoordinate(rawFromLat, rawFromLon, fromQuery, http)
        val startFromArgs = resolvedFrom != null
        val (lat0, lon0) = if (startFromArgs) resolvedFrom!!
        else runCatching { locationProvider?.current() }.getOrNull()?.let { it.lat to it.lon }
            ?: return ToolResult.Err(
                "no_location",
                "Маршрут не клав: невідомо, звідки йти. Передай from_lat/from_lon або увімкни геолокацію.",
                dur(),
            )
        val profile = resolveProfile(args["profile"]?.jsonPrimitive?.content ?: args["mode"]?.jsonPrimitive?.content)
        val via = parseViaWaypoints(args["via"]?.jsonPrimitive?.content)

        val waypoints = listOf(lat0 to lon0) + via + (toLat to toLon)
        // Граф на пристрої ПЕРШИЙ — той самий порядок, що й у головного
        // навігатора (`routeGraphFirst`). Тут він стояв другим, тобто кожна
        // репліка «проведи мене» спершу виносила пару координат у мережу.
        val res = offlineRouteCalculator?.invoke(profile, waypoints)?.firstOrNull()
            ?: osrm.routeDetailed(profile, waypoints, alternatives = false)?.firstOrNull()
            ?: return ToolResult.Err(
                "routing_unavailable",
                "маршруту (${profile.label}) немає: " +
                    if (GeoEndpoints.config.routingTemplate == null) {
                        "доріг цієї ділянки на пристрої немає, а свого сервера маршрутів не " +
                            "налаштовано. Скажи оператору: потрібен дорожній пакет цієї країни — " +
                            "Налаштування → Діагностика → Стан позиції → Хмарні пакети. " +
                            "У чужий роутер його координати не поїдуть."
                    } else {
                        "ні граф на пристрої, ні сервер маршрутів не дали шляху між цими точками. " +
                            "Це або немає звʼязку з ним, або дороги для цього профілю там нема. " +
                            "Спробуй інший профіль (авто/пішки/вело) або ближчу ціль."
                    } +
                    " Відстань по прямій НЕ називаю — нею ніхто не їде.",
                dur(),
            )

        val viaSuffix = if (via.isEmpty()) "" else " через ${via.size} тчк"
        val line = SpatialLine(
            points = res.points.map { GeoPoint(it.first, it.second) },
            color = "#3B82F6",
            width = 6f,
            label = "маршрут$viaSuffix: ${fmtKm(res.distanceMeters)} · ${fmtMin(res.durationSeconds)} (${profile.label})",
        )
        val spec = SpatialLayerSpec(
            title = line.label ?: "маршрут",
            origin = LayerOrigin.WORLD,
            kind = "route",
            ownerThreadId = currentThreadId(),
            markers = listOf(SpatialMarker(lat0, lon0, "старт")) +
                via.mapIndexed { i, (vLat, vLon) -> SpatialMarker(vLat, vLon, "точка ${i + 1}") } +
                listOf(SpatialMarker(toLat, toLon, "фініш")),
            lines = listOf(line),
            color = "#3B82F6",
            ttlMs = 60 * 60_000L,
        )
        val layerId = sink.push(spec)
        val centerLat = (lat0 + toLat) / 2
        val centerLon = (lon0 + toLon) / 2
        runCatching { sink.requestFocus(FocusTarget(centerLat, centerLon, layerId = layerId)) }
        requestMapOpen()

        return ToolResult.Ok(
            output = "Проклав маршрут$viaSuffix (${profile.label}): ${fmtKm(res.distanceMeters)}, " +
                "${fmtMin(res.durationSeconds)}, ${res.turnCount} поворотів. Показав на відкритій мапі.",
            structured = buildJsonObject {
                put("layer_id", layerId)
                put("name", spec.title)
                put("distance_m", res.distanceMeters)
                put("duration_s", res.durationSeconds)
                put("straight_line_m", greatCircleMeters(lat0, lon0, toLat, toLon))
                put("profile", profile.slug)
                put("turns", res.turnCount)
                put("from_lat", lat0)
                put("from_lon", lon0)
                put("to_lat", toLat)
                put("to_lon", toLon)
                put("start_source", if (startFromArgs) "аргументи виклику" else "поточна геолокація")
                put("via_points", via.size)
                put("source", "osrm_routed")
                put("steps_total", res.steps.size)
                put("steps_shown", minOf(res.steps.size, MAX_STEPS_OUT))
                // NAV-1 — turn-by-turn maneuvers so the model can narrate the
                // route or answer «а де повернути?» without another call.
                put("steps", buildJsonArray {
                    res.steps.take(MAX_STEPS_OUT).forEach { s ->
                        add(buildJsonObject {
                            put("type", s.type)
                            s.modifier?.let { put("modifier", it) }
                            if (s.roadName.isNotBlank()) put("road", s.roadName)
                            put("distance_m", s.distanceMeters.toInt())
                        })
                    }
                })
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }

    private companion object {
        /** Steps cap in structured output — context is a budget. */
        const val MAX_STEPS_OUT = 25
    }
}

/** `map.nearby` — flood the OPEN map with a "поруч" category, same 8 kinds as the chip rail. */
class MapNearbyTool(
    private val poiRepository: PoiRepository,
    private val sink: SpatialLayerSink,
    private val locationProvider: LocationContextProvider?,
    private val currentThreadId: () -> String?,
    private val requestMapOpen: () -> Unit = {},
) : Tool {

    override val id = "map.nearby"

    override val description =
        "Показати категорію «поруч» на живій мапі й вивести мапу на екран, якщо зараз відкритий чат — ті самі 8 категорій, що й чипи оператора: укриття, аптеки, заправки, банкомати, магазини, їжа, лікарні, вода (українською чи англійською). За замовчуванням навколо поточної локації."

    override val argsSchema: JsonObject = buildJsonObject {
        put("category", JsonPrimitive("string"))
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
        put("radius_m", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = agencyManifest("tool.map.nearby", "Поруч на мапі")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        val known = NEARBY_KINDS.joinToString(", ") { it.label }
        val rawCategory = args["category"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Err("bad_args", "нічого не шукав: не передано category. Відомі: $known.", dur())
        val kind = resolveNearbyKind(rawCategory)
        val selector = kind?.selector
            ?: rawCategory.lowercase().takeIf { it.matches(Regex("[a-z_]+")) }?.let { "amenity=$it" }
            ?: return ToolResult.Err(
                "bad_args",
                "категорію «$rawCategory» не знаю. Відомі: $known. " +
                    "Або передай англійську назву категорії латиницею (напр. fire_station).",
                dur(),
            )
        val label = kind?.label ?: rawCategory

        val lat = args["lat"]?.jsonPrimitive?.doubleOrNull
        val lon = args["lon"]?.jsonPrimitive?.doubleOrNull
        val centreFromArgs = lat != null && lon != null
        val (lat0, lon0) = if (centreFromArgs) lat!! to lon!!
        else runCatching { locationProvider?.current() }.getOrNull()?.let { it.lat to it.lon }
            ?: return ToolResult.Err(
                "no_location",
                "нічого не шукав: невідомо, навколо чого. Передай lat/lon або увімкни геолокацію.",
                dur(),
            )
        val radius = (args["radius_m"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 3000).coerceIn(200, 5_000)

        val pois = poiRepository.getNearby(lat0, lon0, radius, selector)
            .sortedBy { haversineMeters(lat0, lon0, it.lat, it.lon) }
        if (pois.isEmpty()) {
            return ToolResult.Ok(
                "Поруч (радіус $radius м) не знайшов: $label. Це значить «немає в наявних даних мапи», " +
                    "а не «немає в реальності» — розшир радіус (radius_m до 5000) або спитай інакше.",
                buildJsonObject {
                    put("results", buildJsonArray {})
                    put("count", 0)
                    put("category", label)
                    put("radius_m", radius)
                    put("lat", lat0)
                    put("lon", lon0)
                    put("source", "osm_poi_store")
                    put("absence_means", "немає в даних мапи, не доказ відсутності")
                },
                dur(),
            )
        }

        val points = pois.map { WorldPoint(lat = it.lat, lon = it.lon, label = it.name, glyph = kind?.glyph) }
        val layerId = sink.pushWorldLayer(
            title = "поруч: $label", kind = "nearby", points = points,
            glyph = kind?.glyph, ownerThreadId = currentThreadId(), ttlMs = 20 * 60_000L,
        )
        runCatching { sink.requestFocus(FocusTarget(lat0, lon0, zoom = 14.0, layerId = layerId)) }
        requestMapOpen()

        return ToolResult.Ok(
            output = "Знайшов ${pois.size} «$label» у радіусі $radius м. Порядок — ПО ПРЯМІЙ, " +
                "не за дорогою: " + pois.take(3).joinToString { it.name } +
                ". Показав на мапі. Кому справді найближче їхати — medevac.nearest / shelter.nearest.",
            structured = buildJsonObject {
                layerId?.let { put("layer_id", it) }
                put("count", pois.size)
                put("category", label)
                put("radius_m", radius)
                put("lat", lat0)
                put("lon", lon0)
                put("ordered_by", "great_circle")
                put("source", "osm_poi_store")
                // The places themselves were never in the payload: a card could
                // only read the count, so a found list drew nothing at all.
                put(
                    "results",
                    buildJsonArray {
                        pois.take(MAX_RESULTS_OUT).forEach { p ->
                            add(
                                buildJsonObject {
                                    put("name", p.name)
                                    put("lat", p.lat)
                                    put("lon", p.lon)
                                    put("kind", label)
                                    put("straight_line_m", haversineMeters(lat0, lon0, p.lat, p.lon))
                                },
                            )
                        }
                    },
                )
                put("results_shown", minOf(pois.size, MAX_RESULTS_OUT))
            },
            durationMs = dur(),
        )
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    private companion object {
        /** Context is a budget; the map layer already holds all of them. */
        const val MAX_RESULTS_OUT = 20
    }
}

/**
 * M-C01 — `threat.brief` — request the map to open the ThreatBriefSheet for a specific oblast,
 * or the current user location if not specified.
 */
class ThreatBriefTool(
    private val sink: SpatialLayerSink,
    private val locationProvider: LocationContextProvider?,
    private val requestMapOpen: () -> Unit = {},
    /**
     * Returns whether the request was actually accepted by the map surface.
     * The default is `false` on purpose: an unwired host must read as "not
     * delivered", never as an opened panel nobody ever showed.
     */
    private val requestThreatBrief: (String) -> Boolean = { false },
) : Tool {

    override val id = "threat.brief"

    override val description =
        "Відкрити на мапі панель деталей повітряної тривоги для вказаної області (oblast). " +
            "Без oblast область визначає сам екран мапи за позицією оператора — я її не вгадую. " +
            "Виводить мапу на екран; якщо мапа не прийме запит, чесно скажу, що панель не відкрилась."

    override val argsSchema: JsonObject = buildJsonObject {
        put("oblast", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = agencyManifest("tool.threat.brief", "Деталі тривоги")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val oblast = args["oblast"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

        // "auto" is resolved by MapScreen, which owns the oblast shapes; this
        // tool has none and must not guess an oblast from a coordinate.
        val target = oblast ?: "auto"
        val delivered = runCatching { requestThreatBrief(target) }.getOrDefault(false)
        requestMapOpen()

        if (!delivered) {
            return ToolResult.Err(
                "brief_not_delivered",
                "панель тривоги не відкрилась: мапа не прийняла запит (екран мапи не запущено " +
                    "або черга запитів зайнята). Відкрий мапу і повтори.",
                System.currentTimeMillis() - start,
            )
        }

        return ToolResult.Ok(
            // What is provable here is the request, not the pixels: the sheet
            // is drawn by MapScreen after it collects this.
            output = "Запросив панель тривоги на мапі" +
                (if (oblast != null) " для області «$oblast»" else " за поточною областю оператора") + ".",
            structured = buildJsonObject {
                put("oblast", target)
                put("oblast_resolved_by", if (oblast != null) "аргумент виклику" else "екран мапи")
                put("delivered", true)
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }
}

/**
 * M-C05 — `shelter.nearest` — the shelter you can actually WALK to.
 *
 * F-02 (spec-medevac-shelter §1.3): the pick is made on routed time, not on
 * great-circle distance. A shelter across a rail cutting is not the nearest
 * one, and when nothing routes the answer is «маршруту немає» rather than a
 * straight-line number wearing the word "distance".
 */
class MapNearestShelterTool(
    private val poiRepository: PoiRepository,
    private val sink: SpatialLayerSink,
    private val locationProvider: LocationContextProvider?,
    private val currentThreadId: () -> String?,
    // Lazy on purpose: the registry builds every tool at boot, and an eager
    // OsrmClient there means an OkHttp-backed ktor client per tool on the
    // startup thread. Injecting both seams below never touches it at all.
    private val osrm: Lazy<OsrmClient> = lazy { OsrmClient() },
    private val requestMapOpen: () -> Unit = {},
    private val routeMatrix: RouteMatrixFn = { profile, lat, lon, dest ->
        osrm.value.table(profile, lat, lon, dest)?.map { it?.toDouble() }
    },
    private val routeLeg: RouteLegFn = { profile, fLat, fLon, tLat, tLon ->
        osrm.value.route(profile, fLat, fLon, tLat, tLon)
            ?.let { RoutedLeg(it.distanceMeters, it.durationSeconds, it.points) }
    },
) : Tool {

    override val id = "shelter.nearest"

    override val description =
        "Знайти укриття, найближче ЗА ДОРОГОЮ (не по прямій), і прокласти до нього пішохідний маршрут на живій мапі оператора, вивівши мапу на екран. Шукає в радіусі до 3 км; якщо пішки не дійти — так і каже, без відстані по прямій."

    override val argsSchema: JsonObject = buildJsonObject {
    }

    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = agencyManifest("tool.shelter.nearest", "Найближче укриття")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        val loc = runCatching { locationProvider?.current() }.getOrNull()?.let { it.lat to it.lon }
            ?: return ToolResult.Err(
                "no_location",
                "укриття не шукав: невідомо, де оператор. Увімкни геолокацію — без позиції " +
                    "«найближче» не має змісту. Поки що: найближче укриття шукай очима, " +
                    "переважно це підвал або підземний перехід.",
                dur(),
            )

        val radius = 3000
        val selector = ShelterTruth.BOMB_SHELTER_SELECTOR
        val pois = poiRepository.getNearby(loc.first, loc.second, radius, selector)
            .filter { ShelterTruth.isBombShelter(it.tags) }
        if (pois.isEmpty()) {
            // «Тут їх немає» — твердження про світ. Пакет, що не нарахував собі
            // жодного сертифікованого сховища, такого твердження робити не може.
            val blind = poiRepository.offlineShelterRegistry() != ShelterRegistryVerdict.PRESENT
            return ToolResult.Ok(
                if (blind) {
                    "Не знаю, чи є поруч сховище: у моєму довіднику немає жодного " +
                        "сертифікованого. Це про мої дані, не про місто. Вигадувати не буду — " +
                        "правило 2-х стін, подалі від вікон, підвал або підземний перехід."
                } else {
                    "Серед сховищ, які я знаю, у радіусі $radius м немає жодного. " +
                        "Про решту міста сказати не можу — і це не доказ, що поруч немає підвалу чи переходу."
                },
                buildJsonObject {
                    put("found", false)
                    put("routed", false)
                    put("refusal", if (blind) "registry_cannot_answer" else "no_candidates")
                    put("candidates", 0)
                    put("radius_m", radius)
                    put("lat", loc.first)
                    put("lon", loc.second)
                    put("source", "osm_poi_store")
                    put(
                        "absence_means",
                        if (blind) "довідник не нарахував жодного сертифікованого" else "немає в даних мапи, не доказ відсутності",
                    )
                },
                dur(),
            )
        }

        // Straight-line order chooses whom to ASK the router about; it never
        // chooses the answer. Routed distance is always ≥ great-circle, so the
        // road-nearest is inside the nearest few by line — not guaranteed,
        // which is why the cap is stated in the output below.
        val candidates = pois
            .sortedBy { haversineMeters(loc.first, loc.second, it.lat, it.lon) }
            .take(MEDEVAC_CANDIDATE_CAP)
            .map { MedevacPoi(it.name, it.lat, it.lon, it.tags) }
        val pick = pickRoutedNearest(
            RouteProfile.FOOT, loc.first, loc.second, candidates, routeMatrix, routeLeg,
        )
        val nearest = pick.index?.let { candidates[it] }
        val res = nearest?.let { routeLeg(RouteProfile.FOOT, loc.first, loc.second, it.lat, it.lon) }
        if (nearest == null || res == null) {
            val why = if (pick.routerAnswered) {
                "маршрутизатор відповів, але пішки до жодного з ${candidates.size} укриттів шляху немає"
            } else {
                "маршрутизатор не відповів — чи є пішохідний шлях, невідомо"
            }
            val nextStep = if (pick.routerAnswered) {
                "Пішки туди шляху немає — шукай укриття очима або спитай map.nearby «укриття», " +
                    "щоб побачити всі точки на мапі й вирішити самому."
            } else {
                "Маршрутизатор мовчить — це схоже на відсутність звʼязку. Повтори, коли звʼязок буде, " +
                    "або дивись точки на мапі: map.nearby «укриття»."
            }
            return ToolResult.Ok(
                "МАРШРУТУ НЕМАЄ до укриття ($why). Відстань по прямій не називаю — " +
                    "нею ніхто не йде. Укриттів у радіусі $radius м знайдено: ${pois.size}. $nextStep",
                buildJsonObject {
                    put("found", true)
                    put("routed", false)
                    put("refusal", "no_route")
                    put("router_answered", pick.routerAnswered)
                    put("candidates", pois.size)
                    put("candidates_asked", pick.askedCount)
                    put("radius_m", radius)
                    put("profile", RouteProfile.FOOT.slug)
                    put("next_step", nextStep)
                },
                dur(),
            )
        }

        val line = SpatialLine(
            points = res.points.map { GeoPoint(it.first, it.second) },
            color = "#EF4444",
            width = 7f,
            label = "найближче укриття: ${nearest.name} (${fmtKm(res.distanceMeters)} · ${fmtMin(res.durationSeconds)})",
        )
        val spec = SpatialLayerSpec(
            title = "Маршрут до укриття",
            origin = LayerOrigin.WORLD,
            kind = "shelter_route",
            ownerThreadId = currentThreadId(),
            markers = listOf(
                SpatialMarker(loc.first, loc.second, "Ваша локація"),
                SpatialMarker(nearest.lat, nearest.lon, "Укриття: ${nearest.name}", color = "#EF4444"),
            ),
            lines = listOf(line),
            color = "#EF4444",
            ttlMs = 15 * 60_000L,
        )
        val layerId = sink.push(spec)
        val centerLat = (loc.first + nearest.lat) / 2
        val centerLon = (loc.second + nearest.lon) / 2
        runCatching { sink.requestFocus(FocusTarget(centerLat, centerLon, layerId = layerId)) }
        requestMapOpen()

        val straightM = haversineMeters(loc.first, loc.second, nearest.lat, nearest.lon)
        return ToolResult.Ok(
            output = "Найближче ЗА ДОРОГОЮ укриття «${nearest.name}»: пішки " +
                "${fmtKm(res.distanceMeters)}, ${fmtMin(res.durationSeconds)}. " +
                "Обрано за часом ходу серед ${pick.askedCount} кандидатів (маршрут дали ${pick.routedCount}); " +
                "усього в радіусі $radius м знайдено ${pois.size}. " +
                "По прямій ${fmtKm(straightM)} — це НЕ шлях, лише орієнтир.",
            structured = buildJsonObject {
                put("found", true)
                put("routed", true)
                put("layer_id", layerId)
                put("name", nearest.name)
                put("lat", nearest.lat)
                put("lon", nearest.lon)
                put("distance_m", res.distanceMeters)
                put("duration_s", res.durationSeconds)
                put("straight_line_m", straightM)
                put("radius_m", radius)
                put("profile", RouteProfile.FOOT.slug)
                put("candidates", pois.size)
                put("candidates_asked", pick.askedCount)
                put("candidates_routed", pick.routedCount)
                put("ranked_by", "routed_duration")
                put("source", "osm_poi_store + osrm_routed")
            },
            durationMs = dur(),
        )
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/**
 * NAV-Ω2 (M-H01) — `nav.retrace` — reverse the exact footsteps/breadcrumbs recorded locally.
 */
class NavRetraceTool(
    private val buildRetraceRoute: () -> List<Pair<Double, Double>>?,
    private val requestNavStart: (toLat: Double, toLon: Double, profile: RouteProfile, via: List<Pair<Double, Double>>) -> Unit,
    private val requestMapOpen: () -> Unit = {},
) : Tool {

    override val id = "nav.retrace"

    override val description =
        "Прокласти зворотний маршрут по власних слідах («виведи мене назад тим самим шляхом / по слідах») назад до вихідної точки і почати навігацію на мапі. Працює автономно по локальному журналу координат без інтернету."

    override val argsSchema: JsonObject = buildJsonObject {
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val streaming: Boolean = false
    override val manifest = agencyManifest("tool.nav.retrace", "Зворотний маршрут по слідах")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val pts = buildRetraceRoute()
        if (pts == null || pts.size < 2) {
            return ToolResult.Err(
                code = "insufficient_breadcrumbs",
                message = "Недостатньо записаних слідів переміщення для побудови зворотного маршруту.",
                durationMs = System.currentTimeMillis() - start,
            )
        }

        val from = pts.first()
        val to = pts.last()
        val via = pts.subList(1, pts.size - 1)

        requestNavStart(to.first, to.second, RouteProfile.FOOT, via)
        requestMapOpen()

        return ToolResult.Ok(
            output = "Прокладено зворотний маршрут по слідах (${pts.size} точок) назад до вихідної точки.",
            structured = buildJsonObject {
                put("points_count", JsonPrimitive(pts.size))
                put("to_lat", JsonPrimitive(to.first))
                put("to_lon", JsonPrimitive(to.second))
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }
}

/**
 * NAV-Ω2 — `nav.exfil` — urgent evacuation route away from nearest danger / threat centroid.
 */
class NavExfilTool(
    private val locationProvider: LocationContextProvider?,
    private val requestExfil: suspend () -> Boolean,
    private val requestMapOpen: () -> Unit = {},
) : Tool {

    override val id = "nav.exfil"

    override val description =
        "Терміново прокласти маршрут евакуації / виходу (Exfil) геть від найближчої загрози, епіцентру обстрілу чи небезпечної зони та вивести мапу."

    override val argsSchema: JsonObject = buildJsonObject {
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val streaming: Boolean = false
    override val manifest = agencyManifest("tool.nav.exfil", "Екстрений вихід / Exfil")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val loc = locationProvider?.current()
        if (loc == null) {
            return ToolResult.Err(
                code = "location_unavailable",
                message = "Поточне місцезнаходження невідоме — неможливо розрахувати вектор виходу.",
                durationMs = System.currentTimeMillis() - start,
            )
        }

        val success = runCatching { requestExfil() }.getOrDefault(false)
        requestMapOpen()

        return if (success) {
            ToolResult.Ok(
                output = "Екстрений маршрут виходу (Exfil) розраховано та запущено на мапі.",
                structured = buildJsonObject {
                    put("status", JsonPrimitive("exfil_active"))
                },
                durationMs = System.currentTimeMillis() - start,
            )
        } else {
            ToolResult.Err(
                code = "exfil_failed",
                message = "Не вдалося прокласти маршрут евакуації.",
                durationMs = System.currentTimeMillis() - start,
            )
        }
    }
}


