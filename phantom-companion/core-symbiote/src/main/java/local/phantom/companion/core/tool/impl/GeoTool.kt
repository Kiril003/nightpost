package local.phantom.companion.core.tool.impl

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.net.map.GeoEndpoints
import local.phantom.companion.core.net.map.GeocodeDialect
import local.phantom.companion.core.net.map.GeocoderKind
import local.phantom.companion.core.net.map.RouteBasis
import local.phantom.companion.core.net.map.RouteVariant
import local.phantom.companion.core.net.map.routingBaseFor
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R1.4 — the geo stack: the map stops being empty tiles.
 *
 * Three tools over public HTTPS services, all with honest degradation
 * (Law 1) and a proper User-Agent:
 *
 *  - [GeoSearchTool] `geo.search` — forward geocoding via Nominatim
 *    (1 req/s policy enforced with a shared throttle).
 *  - [GeoNearbyTool] `geo.nearby` — POI radius search via Overpass.
 *  - [GeoRouteTool]  `geo.route` — walking/driving route via public
 *    OSRM (demo server may throttle → honest "routing unavailable").
 *
 * Every Ok result carries a ready-made `map` object shaped EXACTLY
 * like the `map` widget payload (latitude/longitude/zoom/markers/
 * polylines) — the model shows results on the map by emitting
 * `{"type":"map","payload":<map>}` verbatim.
 */

private const val GEO_USER_AGENT =
    "PhantomCompanion/3.0 (personal on-device assistant; contact: operator)"

private fun geoManifest(id: String, name: String) = FeatureManifest(
    id = id,
    tier = 1,
    displayName = name,
    erodesOperatorAgency = false,
    exfiltratesPrivate = false,
    actsWithoutConsent = false,
    abusableAgainstWeak = false,
    sunsetAfterDaysIdle = 90,
    surfaces = setOf(FeatureManifest.Surface.STREAM),
)

private val geoJson = Json { ignoreUnknownKeys = true }

/**
 * Shared HTTP runner: User-Agent on every call, Nominatim throttled to
 * 1 req/s (usage policy), all failures folded to null so each tool can
 * answer with its own honest error code.
 */
class GeoHttp(
    private val httpClient: HttpClient,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val nominatimMutex = Mutex()
    private var lastNominatimMs = 0L

    suspend fun getNominatim(url: String): String? = nominatimMutex.withLock {
        if (lastNominatimMs != 0L) {
            val since = clockMs() - lastNominatimMs
            if (since in 0 until NOMINATIM_MIN_INTERVAL_MS) {
                sleep(NOMINATIM_MIN_INTERVAL_MS - since)
            }
        }
        lastNominatimMs = clockMs()
        getText(url)
    }

    suspend fun getText(url: String): String? = runCatching {
        val resp: HttpResponse = httpClient.get(url) {
            header("User-Agent", GEO_USER_AGENT)
        }
        if (!resp.status.isSuccess()) return@runCatching null
        resp.bodyAsText()
    }.getOrNull()

    suspend fun postText(url: String, body: String): String? = runCatching {
        val resp: HttpResponse = httpClient.post(url) {
            header("User-Agent", GEO_USER_AGENT)
            setBody(body)
        }
        if (!resp.status.isSuccess()) return@runCatching null
        resp.bodyAsText()
    }.getOrNull()

    companion object {
        const val NOMINATIM_MIN_INTERVAL_MS = 1_100L
    }
}

private fun coord(v: Double): String = String.format(Locale.US, "%.6f", v)

/** Метри по прямій. Потрібні як чесний контрольний вимір поруч із маршрутом:
 *  дорога коротша за пряму — це помилка, а не швидкий шлях. */
private fun straightLineM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

private fun coordsInRange(lat: Double, lon: Double): Boolean =
    lat in -90.0..90.0 && lon in -180.0..180.0

private fun metresWord(m: Double): String =
    if (m >= 1000) String.format(Locale.US, "%.1f км", m / 1000.0) else "${m.toInt()} м"

private fun geocoderLabel(kind: GeocoderKind): String = when (kind) {
    GeocoderKind.NOMINATIM -> "Nominatim/OSM"
    GeocoderKind.PHOTON -> "Photon/OSM"
}

/** Builds the widget-ready `map` object from markers (+ optional polyline). */
private fun mapObject(
    centerLat: Double,
    centerLon: Double,
    zoom: Double,
    markers: List<Triple<Double, Double, String?>>,
    polylinePoints: List<Pair<Double, Double>> = emptyList(),
): JsonObject = buildJsonObject {
    put("latitude", centerLat)
    put("longitude", centerLon)
    put("zoom", zoom)
    if (markers.isNotEmpty()) {
        put("markers", buildJsonArray {
            markers.forEach { (la, lo, label) ->
                add(buildJsonObject {
                    put("lat", la)
                    put("lon", lo)
                    label?.let { put("label", it) }
                })
            }
        })
    }
    if (polylinePoints.isNotEmpty()) {
        put("polylines", buildJsonArray {
            add(buildJsonObject {
                put("points", buildJsonArray {
                    polylinePoints.forEach { (la, lo) ->
                        add(buildJsonObject { put("lat", la); put("lon", lo) })
                    }
                })
            })
        })
    }
}

/** `geo.search` — name → places (Nominatim forward geocoding). */
class GeoSearchTool(
    private val http: GeoHttp,
) : Tool {

    override val id = "geo.search"

    override val description =
        "Знайти місце за назвою чи адресою (геокодинг OSM). Повертає results[name,lat,lon,kind], " +
            "source, count, ambiguous+spread_m (чи це різні місця з тією ж назвою) і готовий обʼєкт map. " +
            "Ранжування — за релевантністю геокодера, НЕ за відстанню."

    override val argsSchema: JsonObject = buildJsonObject {
        put("query", JsonPrimitive("string"))
        put("limit", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK)

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = false

    override val manifest = geoManifest("tool.geo.search", "Гео-пошук")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Err(
                code = "bad_args",
                message = "порожній query. Потрібна назва або адреса, напр. «Хрещатик 22, Київ». " +
                    "Наступний крок — перепитай оператора, що саме шукаємо.",
                durationMs = 0L,
            )
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 10)

        val config = GeoEndpoints.config
        val source = geocoderLabel(config.geocoderKind)
        val endpoint = config.geocodeBaseUrl
            ?: return ToolResult.Err(
                code = "geo_unavailable",
                message = "онлайн-геокодер не налаштовано. Скажи оператору прямо: пошук місць за назвою вимкнено, " +
                    "координат звідси не буде. Наступний крок — офлайн-довідник (place.list) або координати вручну.",
                durationMs = System.currentTimeMillis() - startMs,
            )
        // GEO-LAW — dialect-agnostic: Nominatim or Photon, whichever is
        // configured, resolved through the one shared helper.
        val url = GeocodeDialect.searchUrl(endpoint, query, limit, config.geocoderKind)
        val body = http.getNominatim(url)
            ?: return ToolResult.Err(
                code = "geo_unavailable",
                message = "геокодер $source не відповів (мережа або ліміт запитів). Це збій звʼязку, " +
                    "а НЕ «такого місця немає» — не вигадуй координат. " +
                    "Наступний крок — повтори за хвилину або попроси координати.",
                durationMs = System.currentTimeMillis() - startMs,
            )

        val places = GeocodeDialect.parseSearch(body, config.geocoderKind)
            .map { Place(it.name, it.lat, it.lon, it.kind) }

        if (places.isEmpty()) {
            return ToolResult.Ok(
                output = "Нічого не знайдено за запитом «$query» ($source). " +
                    "Наступний крок — додай місто чи область до назви або дай координати.",
                structured = buildJsonObject {
                    put("results", buildJsonArray {})
                    put("query", query)
                    put("count", 0)
                    put("source", source)
                    put("next_step", "додати місто/область до запиту або передати координати")
                },
                durationMs = System.currentTimeMillis() - startMs,
            )
        }

        val first = places.first()
        // Геокодер ранжує за власною релевантністю, не за близькістю: якщо збіги
        // розкидані по країні, «перше» — здогад, і про це треба сказати вголос.
        val spreadM = places.drop(1)
            .maxOfOrNull { straightLineM(first.lat, first.lon, it.lat, it.lon) } ?: 0.0
        val ambiguous = spreadM > AMBIGUOUS_SPREAD_M
        val structured = buildJsonObject {
            put("results", buildJsonArray {
                places.forEach { p ->
                    add(buildJsonObject {
                        put("name", p.name)
                        put("lat", p.lat)
                        put("lon", p.lon)
                        p.kind?.let { put("kind", it) }
                    })
                }
            })
            put("query", query)
            put("count", places.size)
            put("source", source)
            put("ranked_by", "релевантність геокодера, не відстань")
            put("spread_m", spreadM)
            put("ambiguous", ambiguous)
            put("map", mapObject(
                centerLat = first.lat,
                centerLon = first.lon,
                zoom = 13.0,
                markers = places.map { Triple(it.lat, it.lon, it.name.substringBefore(",")) },
            ))
        }
        val caveat = if (ambiguous) {
            " Збіги розкидані на ${metresWord(spreadM)} — це різні місця з тією ж назвою, уточни область."
        } else {
            ""
        }
        return ToolResult.Ok(
            output = "Знайдено ${places.size} ($source): перше — ${first.name} " +
                "(${coord(first.lat)}, ${coord(first.lon)}).$caveat",
            structured = structured,
            durationMs = System.currentTimeMillis() - startMs,
        )
    }

    private data class Place(val name: String, val lat: Double, val lon: Double, val kind: String?)

    private companion object {
        /** Далі цього збіги — вже різні населені пункти, а не сусідні будинки. */
        const val AMBIGUOUS_SPREAD_M = 25_000.0
    }
}

/** `geo.nearby` — POI around a point (Overpass radius query). */
class GeoNearbyTool(
    private val http: GeoHttp,
    private val locationProvider: LocationContextProvider?,
    private val endpoint: String = OVERPASS_ENDPOINT,
) : Tool {

    override val id = "geo.nearby"

    override val description =
        "Знайти POI поруч (Overpass/OSM): category — значення OSM-тегу amenity або shop " +
            "(pharmacy, hospital, shelter, cafe, atm, fuel, supermarket…). За замовчуванням — навколо поточної локації. " +
            "Повертає results, впорядковані за відстанню, з distance_m і адресою, плюс radius_m, count і обʼєкт map. " +
            "Відстані по прямій, не за дорогою. Порожній список означає порожню мапу OSM, а не порожню місцевість."

    override val argsSchema: JsonObject = buildJsonObject {
        put("category", JsonPrimitive("string"))
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
        put("radius_m", JsonPrimitive("number?"))
        put("limit", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = false

    override val manifest = geoManifest("tool.geo.nearby", "POI поруч")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - startMs }
        val category = args["category"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?.takeIf { it.isNotEmpty() && it.matches(CATEGORY_SHAPE) }
            ?: return ToolResult.Err(
                code = "bad_args",
                message = "category — англійська назва категорії латиницею: pharmacy, hospital, doctors, clinic, " +
                    "shelter, fuel, atm, supermarket, cafe, drinking_water. " +
                    "Наступний крок — перекласти запит оператора в один із цих тегів і викликати ще раз.",
                durationMs = 0L,
            )
        val explicitCenter = args["lat"]?.jsonPrimitive?.doubleOrNull != null &&
            args["lon"]?.jsonPrimitive?.doubleOrNull != null
        val (lat, lon) = resolveCoords(args, locationProvider)
            ?: return ToolResult.Err(
                code = "no_location",
                message = "немає точки відліку: локація вимкнена і lat/lon не передані. " +
                    "Наступний крок — передай lat/lon явно або попроси оператора увімкнути геолокацію.",
                durationMs = elapsed(),
            )
        if (!coordsInRange(lat, lon)) {
            return ToolResult.Err(
                code = "bad_args",
                message = "координати центру поза межами світу (lat=$lat, lon=$lon). " +
                    "Наступний крок — перевір, чи не переплутано lat і lon місцями.",
                durationMs = elapsed(),
            )
        }
        val radius = (args["radius_m"]?.jsonPrimitive?.intOrNull ?: 800).coerceIn(50, 5_000)
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 20)

        // Сортуємо за відстанню вже в себе, тож просимо в Overpass із запасом:
        // «out N» ріже довільні N, а не найближчі N.
        val fetchCap = (limit * 4).coerceAtMost(120)
        val around = "around:$radius,${coord(lat)},${coord(lon)}"
        // Лікарня чи аптека часто змальована полігоном (way), а не точкою:
        // запит лише по node мовчки не бачив саме ті обʼєкти, заради яких кличуть.
        val ql = "[out:json][timeout:25];(" +
            "node[\"amenity\"=\"$category\"]($around);" +
            "way[\"amenity\"=\"$category\"]($around);" +
            "node[\"shop\"=\"$category\"]($around);" +
            "way[\"shop\"=\"$category\"]($around);" +
            ");out center $fetchCap;"
        val body = http.postText(endpoint, "data=" + java.net.URLEncoder.encode(ql, "UTF-8"))
            ?: return ToolResult.Err(
                code = "geo_unavailable",
                message = "Пошук по мапі зараз не відповів. Це збій звʼязку, а НЕ «поруч нічого немає» — " +
                    "так і скажи оператору. Наступний крок — повтори за хвилину або звузь радіус.",
                durationMs = elapsed(),
            )

        val root = runCatching { geoJson.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ToolResult.Err(
                code = "geo_unreadable",
                message = "відповідь пошуку по мапі не прочиталась. НЕ кажи, що поруч нічого немає — це невідомо. " +
                    "Наступний крок — повтори запит або звузь радіус.",
                durationMs = elapsed(),
            )
        val remark = root["remark"]?.jsonPrimitive?.contentOrNull
        val elements = root["elements"]?.jsonArray
            ?: return ToolResult.Err(
                code = "geo_unreadable",
                message = "Пошук по мапі не повернув списку місць" + (remark?.let { " ($it)" }.orEmpty()) +
                    ". Це збій сервера, не порожня місцевість. Наступний крок — повтори запит.",
                durationMs = elapsed(),
            )
        // Overpass віддає 200 OK із порожнім elements і remark, коли обірвав запит
        // за таймаутом. Мовчки прочитати це як «нічого нема» — брехня про місцевість.
        if (remark != null && elements.isEmpty()) {
            return ToolResult.Err(
                code = "geo_overloaded",
                message = "Пошук по мапі обірвався. Порожній список тут — збій, " +
                    "а не відсутність обʼєктів. Наступний крок — менший радіус або повтор за хвилину.",
                durationMs = elapsed(),
            )
        }

        val pois = runCatching {
            elements.mapNotNull { el ->
                val o = el.jsonObject
                val center = o["center"]?.jsonObject
                val pLat = (o["lat"] ?: center?.get("lat"))?.jsonPrimitive?.doubleOrNull
                    ?: return@mapNotNull null
                val pLon = (o["lon"] ?: center?.get("lon"))?.jsonPrimitive?.doubleOrNull
                    ?: return@mapNotNull null
                val tags = o["tags"]?.jsonObject
                val name = tags?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                Poi(
                    name = name ?: category,
                    lat = pLat,
                    lon = pLon,
                    distanceM = straightLineM(lat, lon, pLat, pLon),
                    named = name != null,
                    address = listOfNotNull(
                        tags?.get("addr:street")?.jsonPrimitive?.contentOrNull,
                        tags?.get("addr:housenumber")?.jsonPrimitive?.contentOrNull,
                    ).joinToString(" ").takeIf { it.isNotBlank() },
                    openingHours = tags?.get("opening_hours")?.jsonPrimitive?.contentOrNull,
                )
            }.sortedBy { it.distanceM }.take(limit)
        }.getOrElse {
            return ToolResult.Err(
                code = "geo_unreadable",
                message = "місця з пошуку не розібрались. " +
                    "НЕ кажи, що поруч нічого немає. Наступний крок — повтори запит або звузь радіус.",
                durationMs = elapsed(),
            )
        }

        val coverageNote = "місце, якого не внесли на мапу, тут не зʼявиться"

        if (pois.isEmpty()) {
            return ToolResult.Ok(
                output = "У радіусі $radius м на мапі немає жодного «$category». " +
                    "Це відповідь про мапу, не про місцевість — $coverageNote. " +
                    "Наступний крок — більший радіус або суміжна категорія.",
                structured = buildJsonObject {
                    put("results", buildJsonArray {})
                    put("category", category)
                    put("radius_m", radius)
                    put("count", 0)
                    put("source", "Overpass/OSM")
                    put("caveat", coverageNote)
                    put("next_step", "збільшити radius_m або спробувати суміжну категорію")
                },
                durationMs = elapsed(),
            )
        }

        val nearest = pois.first()
        val structured = buildJsonObject {
            put("results", buildJsonArray {
                pois.forEach { p ->
                    add(buildJsonObject {
                        put("name", p.name)
                        put("lat", p.lat)
                        put("lon", p.lon)
                        // `kind` — це підрядок картки; відстань там корисніша за латинський тег.
                        put("kind", "за ${metresWord(p.distanceM)}" + p.address?.let { ", $it" }.orEmpty())
                        put("distance_m", p.distanceM)
                        put("category", category)
                        put("named", p.named)
                        p.address?.let { put("address", it) }
                        p.openingHours?.let { put("opening_hours", it) }
                    })
                }
            })
            put("category", category)
            put("radius_m", radius)
            put("count", pois.size)
            put("source", "Overpass/OSM")
            put("ranked_by", "distance")
            put("distance_basis", "по прямій від центру пошуку, не за дорогою")
            put("center_lat", lat)
            put("center_lon", lon)
            put("center_source", if (explicitCenter) "координати з виклику" else "поточна локація")
            put("unnamed_count", pois.count { !it.named })
            put("caveat", coverageNote)
            put("map", mapObject(
                centerLat = lat,
                centerLon = lon,
                zoom = 15.0,
                markers = pois.map { Triple(it.lat, it.lon, it.name as String?) },
            ))
        }
        return ToolResult.Ok(
            output = "Знайдено ${pois.size} «$category» у радіусі $radius м. " +
                "Найближче — ${nearest.name} за ${metresWord(nearest.distanceM)} по прямій" +
                (nearest.address?.let { " ($it)" }.orEmpty()) + ". " +
                "Далі: " + pois.drop(1).take(2).joinToString { "${it.name} — ${metresWord(it.distanceM)}" }
                    .ifBlank { "більше нічого в радіусі" } + ". " +
                "Відстані по прямій, не за дорогою; $coverageNote.",
            structured = structured,
            durationMs = elapsed(),
        )
    }

    private data class Poi(
        val name: String,
        val lat: Double,
        val lon: Double,
        val distanceM: Double,
        val named: Boolean,
        val address: String?,
        val openingHours: String?,
    )

    companion object {
        const val OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"

        /** Тримає значення в межах OSM-тегу — і водночас закриває інʼєкцію в Overpass QL. */
        private val CATEGORY_SHAPE = Regex("[a-z_]+")
    }
}

/** `geo.route` — walking/driving route between two points (public OSRM with offline A* fallback). */
class GeoRouteTool(
    private val http: GeoHttp,
    private val locationProvider: LocationContextProvider?,
    // Був Triple(відстань, час, точки) — і саме тому три речі, які офлайн-роутер
    // знає про дорогу, не мали як доїхати до відповіді оператору.
    private val offlineRouteCalculator: (suspend (fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, isPedestrian: Boolean) -> RouteVariant?)? = null,
) : Tool {

    override val id = "geo.route"

    override val description =
        "Маршрут пішки/авто між двома точками (OSRM / офлайн-граф Nightpost). Повертає distance_m, duration_s, " +
            "straight_line_m (контроль по прямій), source, is_offline, basis[] — звідки взялися хвилини, " +
            "і полілінію для мапи. from за замовчуванням — поточна локація; mode лише walking або driving."

    override val argsSchema: JsonObject = buildJsonObject {
        put("to_lat", JsonPrimitive("number"))
        put("to_lon", JsonPrimitive("number"))
        put("from_lat", JsonPrimitive("number?"))
        put("from_lon", JsonPrimitive("number?"))
        put("mode", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = false

    override val manifest = geoManifest("tool.geo.route", "Маршрут")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - startMs }
        val toLat = args["to_lat"]?.jsonPrimitive?.doubleOrNull
        val toLon = args["to_lon"]?.jsonPrimitive?.doubleOrNull
        if (toLat == null || toLon == null) {
            return ToolResult.Err(
                code = "bad_args",
                message = "потрібні обидві координати цілі — to_lat і to_lon. " +
                    "Наступний крок — знайди точку через geo.search і підстав її координати.",
                durationMs = 0L,
            )
        }
        if (!coordsInRange(toLat, toLon)) {
            return ToolResult.Err(
                code = "bad_args",
                message = "координати цілі поза межами світу (to_lat=$toLat, to_lon=$toLon). " +
                    "Наступний крок — перевір, чи не переплутано широту з довготою.",
                durationMs = 0L,
            )
        }
        val fromLat = args["from_lat"]?.jsonPrimitive?.doubleOrNull
        val fromLon = args["from_lon"]?.jsonPrimitive?.doubleOrNull
        // Половина старту мовчки підмінялася поточною локацією — маршрут виходив
        // від зовсім іншої точки, ніж просив оператор.
        if ((fromLat == null) != (fromLon == null)) {
            return ToolResult.Err(
                code = "bad_args",
                message = "передано лише одну координату старту — потрібні обидві, from_lat і from_lon. " +
                    "Наступний крок — дай обидві, або жодної (тоді старт це поточна локація).",
                durationMs = 0L,
            )
        }
        if (fromLat != null && fromLon != null && !coordsInRange(fromLat, fromLon)) {
            return ToolResult.Err(
                code = "bad_args",
                message = "координати старту поза межами світу (from_lat=$fromLat, from_lon=$fromLon). " +
                    "Наступний крок — перевір порядок широти й довготи.",
                durationMs = 0L,
            )
        }
        val explicitStart = fromLat != null && fromLon != null
        val from = if (fromLat != null && fromLon != null) fromLat to fromLon
        else runCatching { locationProvider?.current() }.getOrNull()?.let { it.lat to it.lon }
            ?: return ToolResult.Err(
                code = "no_location",
                message = "невідома стартова точка: локація вимкнена і from_lat/from_lon не передані. " +
                    "Наступний крок — передай старт явно або попроси оператора увімкнути геолокацію.",
                durationMs = elapsed(),
            )
        val modeArg = args["mode"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val mode = when (modeArg) {
            null, "", "driving", "car", "auto", "авто", "автом", "машиною" -> "driving"
            "walking", "foot", "пішки", "пішком", "пішохід" -> "walking"
            // Велосипед та інші профілі не підняті на бекенді: мовчки віддати
            // автомаршрут велосипедисту — гірше, ніж відмовити.
            else -> return ToolResult.Err(
                code = "bad_args",
                message = "невідомий mode «$modeArg». Доступні лише walking (пішки) і driving (авто). " +
                    "Наступний крок — обери один із двох і виклич ще раз.",
                durationMs = 0L,
            )
        }
        val modeWord = if (mode == "walking") "пішки" else "авто"

        // Профіль вибирає ХОСТ, а не слово в шляху: пішохідний запит на автомобільний
        // сервер тихо повертав автомаршрут — з магістралями, де людині ходу немає.
        val endpoint = GeoEndpoints.config
            .routingBaseFor(if (mode == "walking") "routed-foot" else "routed-car")
        // Граф на пристрої ПЕРШИЙ: мережа тепер лише своя, і питати її раніше
        // за готовий граф означає чекати на те, що вже лежить у кишені.
        val offlineVariant = offlineRouteCalculator
            ?.invoke(from.first, from.second, toLat, toLon, mode == "walking")
        val onlineRoute: Triple<Double, Double, List<Pair<Double, Double>>>? = if (offlineVariant == null && endpoint != null) {
            val url = "$endpoint/route/v1/$mode/" +
                "${coord(from.second)},${coord(from.first)};${coord(toLon)},${coord(toLat)}" +
                "?overview=full&geometries=geojson"
            val body = http.getText(url)
            if (body != null) {
                runCatching {
                    val o = geoJson.parseToJsonElement(body).jsonObject
                    if (o["code"]?.jsonPrimitive?.content != "Ok") return@runCatching null
                    val r = o["routes"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching null
                    val dist = r["distance"]?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
                    val dur = r["duration"]?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
                    val coords = r["geometry"]?.jsonObject?.get("coordinates")?.jsonArray
                        ?.mapNotNull { pair ->
                            val a = pair.jsonArray
                            val lo = a.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                            val la = a.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                            la to lo
                        }
                        .orEmpty()
                    Triple(dist, dur, coords)
                }.getOrNull()
            } else null
        } else null

        val finalRoute: Triple<Double, Double, List<Pair<Double, Double>>> =
            offlineVariant?.let { Triple(it.distanceMeters, it.durationSeconds, it.points) }
                ?: onlineRoute
                ?: return ToolResult.Err(
                    code = "routing_unavailable",
                    message = "маршруту немає: доріг цієї ділянки на пристрої нема, а " +
                        (if (endpoint == null) {
                            "свого сервера маршрутів не налаштовано. Скажи це прямо, БЕЗ вигаданого " +
                                "маршруту: потрібен дорожній пакет країни — Налаштування → Діагностика → " +
                                "Стан позиції → Хмарні пакети. У чужий роутер точки оператора не поїдуть."
                        } else {
                            "свій роутер не відповів або не знайшов шляху. Скажи це прямо, БЕЗ " +
                                "вигаданого маршруту. Наступний крок — повтори, коли буде звʼязок."
                        }),
                    durationMs = elapsed(),
                )

        val (distanceM, durationS, rawPoints) = finalRoute
        // Decimate so a cross-city polyline doesn't blow up the widget payload.
        val step = (rawPoints.size / MAX_POLYLINE_POINTS).coerceAtLeast(1)
        val points = rawPoints.filterIndexed { i, _ ->
            i % step == 0 || i == rawPoints.lastIndex
        }

        val sourceLabel = if (onlineRoute == null) "офлайн-граф Nightpost" else "OSRM"
        val basisLines = offlineVariant?.let { RouteBasis.lines(it) }.orEmpty().ifEmpty {
            if (onlineRoute != null) {
                listOf(
                    if (mode == "walking") "час — модельна швидкість пішохода, без підйомів і черг"
                    else "час — вільний потік, затори не враховані",
                )
            } else {
                emptyList()
            }
        }
        val straightM = straightLineM(from.first, from.second, toLat, toLon)
        val detour = if (straightM > 1.0) distanceM / straightM else 1.0

        val structured = buildJsonObject {
            // `name` і `straight_line_m` — єдині поля, крізь які походження й
            // контрольний вимір доходять до картки вимірювання.
            put("name", "маршрут $modeWord · $sourceLabel")
            put("distance_m", distanceM)
            put("duration_s", durationS)
            put("straight_line_m", straightM)
            put("detour_factor", detour)
            put("mode", mode)
            put("source", sourceLabel)
            put("is_offline", onlineRoute == null)
            put("start_source", if (explicitStart) "координати з виклику" else "поточна локація")
            put("points_total", rawPoints.size)
            put("points_shown", points.size)
            put("points_decimated", step > 1)
            offlineVariant?.let { v ->
                put("speed_basis", v.speedBasis)
                put("posted_share", v.postedShare)
                put("trunk_shoulder_m", v.trunkShoulderMeters)
                // Позначені переправи. Ключ названо `marked`, а не `fords`, бо
                // модель, що прочитає «fords: 2», напише «два броди, проїдете»;
                // прочитавши «marked», вона мусить сказати те, що є.
                put("fords_marked", v.fordCrossings)
            }
            if (basisLines.isNotEmpty()) {
                put("basis", buildJsonArray { basisLines.forEach { add(JsonPrimitive(it)) } })
            }
            put("points", buildJsonArray {
                points.forEach { (la, lo) ->
                    add(buildJsonObject { put("lat", la); put("lon", lo) })
                }
            })
            put("map", mapObject(
                centerLat = (from.first + toLat) / 2,
                centerLon = (from.second + toLon) / 2,
                zoom = 13.0,
                markers = listOf(
                    Triple(from.first, from.second, "старт"),
                    Triple(toLat, toLon, "фініш"),
                ),
                polylinePoints = points,
            ))
        }
        val km = distanceM / 1000.0
        val minutes = (durationS / 60.0).toInt()
        val timeWord = if (minutes < 1) "менше хвилини" else "≈$minutes хв"
        val detourNote = if (detour >= DETOUR_NOTICE) {
            " Дорога вдвічі й більше довша за пряму (${metresWord(straightM)}) — попереду обхід."
        } else {
            ""
        }
        return ToolResult.Ok(
            output = String.format(
                Locale.US,
                "Маршрут %s (%s): %.1f км, %s, по прямій %s.",
                modeWord, sourceLabel, km, timeWord, metresWord(straightM),
            ) + detourNote + basisLines.joinToString("") { " $it." },
            structured = structured,
            durationMs = elapsed(),
        )
    }

    companion object {
        const val MAX_POLYLINE_POINTS = 100

        /** Вище цього відношення дороги до прямої — це вже обхід, і мовчати про нього не можна. */
        private const val DETOUR_NOTICE = 2.0
    }
}
