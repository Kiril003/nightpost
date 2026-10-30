package local.phantom.companion.core.net.transit

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import local.phantom.companion.core.net.geosrc.GeoBBox
import local.phantom.companion.core.net.geosrc.GeoClock
import local.phantom.companion.core.net.geosrc.GeoHttp
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.GeoSourceIds
import local.phantom.companion.core.net.geosrc.GeoSourceObserver
import local.phantom.companion.core.net.geosrc.MinIntervalLimiter
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.net.geosrc.SystemGeoClock
import local.phantom.companion.core.net.geosrc.TtlCache
import local.phantom.companion.core.net.geosrc.decodeOrMalformed
import local.phantom.companion.core.net.geosrc.reportTo

/**
 * Зупинка з глобальним Onestop ID.
 *
 * [onestopId] — головна цінність Transitland. Локальний `stop_id` у фідах
 * різних перевізників збігається постійно («1», «A», «101»), і в Києві,
 * де метро, трамвай і приміські маршрути живуть у різних фідах, зшивання
 * за локальним ідентифікатором дає злиття чужих зупинок. Onestop ID
 * глобально унікальний і стабільний між версіями фіда.
 */
data class TransitStop(
    val onestopId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** Локальний `stop_id` перевізника — лише для звірки з сирим GTFS. */
    val localStopId: String?,
    val platformCode: String?,
    /** Onestop ID маршрутів, що зупиняються тут (порожній, якщо фід їх не віддав). */
    val servedByRouteOnestopIds: List<String>,
)

/** Маршрут із Onestop ID і вже нормалізованим видом транспорту. */
data class TransitRoute(
    val onestopId: String,
    val shortName: String?,
    val longName: String?,
    /** Сирий GTFS `route_type` — збережений, щоб евристику можна було перевірити. */
    val routeType: Int?,
    /** Результат [TransitModeHeuristic.classify] для цього маршруту. */
    val mode: TransitMode,
    val operatorOnestopId: String?,
    val operatorName: String?,
    /** Колір лінії у форматі RRGGBB без «#», як його віддає GTFS. */
    val colorHex: String?,
)

/** Перевізник (агенція) регіону. */
data class TransitOperator(
    val onestopId: String,
    val name: String,
    val shortName: String?,
    /** Onestop ID агенцій, що входять до оператора. */
    val agencyOnestopIds: List<String>,
)

/**
 * Клієнт Transitland v2 REST — агрегатора офіційних GTFS-фідів.
 *
 * Поведінка при відмові дзеркалить `map/OverpassClient`: жодного винятку
 * назовні, порожній результат відрізняється від недоступного джерела, а
 * повторний запит того самого прямокутника йде з кеша.
 *
 * Ключ приймається параметром і передається заголовком `apikey`, а не
 * параметром URL: URL потрапляє і в журнал, і в ключ кеша, і ключ звідти
 * витікає.
 *
 * @param apiKey ключ Transitland; `null` означає «джерело недоступне»
 *   (сервіс не обслуговує анонімні запити до v2 REST).
 */
class TransitlandClient(
    private val apiKey: String? = null,
    engine: HttpClientEngine? = null,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val clock: GeoClock = SystemGeoClock,
    private val observer: GeoSourceObserver? = null,
    cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    burst: Int = 5,
) {
    private val http = GeoHttp.newClient(engine)
    private val cache = TtlCache<String, String>(cacheTtlMs, maxEntries = 24, clock = clock)
    private val limiter = MinIntervalLimiter(minIntervalMs, burst, clock)

    /** Чи ключ налаштований — реєстр показує це до першого запиту. */
    val hasApiKey: Boolean get() = !apiKey.isNullOrBlank()

    /**
     * Зупинки в прямокутнику.
     *
     * @param limit скільки зупинок максимум віддати; Transitland жорстко
     *   обмежує сторінку сотнями, і більше однієї сторінки тут свідомо не
     *   тягнеться — карті потрібне те, що видно, а не весь фід міста.
     */
    suspend fun stopsInBbox(bbox: GeoBBox, limit: Int = 100): GeoResult<List<TransitStop>> {
        val text = fetch("stops", bbox = bbox, limit = limit)
        if (text is GeoResult.Unavailable) return text
        val body = (text as GeoResult.Ok).value
        return when (val parsed = decodeOrMalformed<StopsEnvelope>(body)) {
            is GeoResult.Unavailable -> parsed.reportTo(observer, GeoSourceIds.TRANSITLAND)
            is GeoResult.Ok -> GeoResult.Ok(
                parsed.value.stops.orEmpty().mapNotNull { it.toDomain() },
                text.fromCache,
            ).reportTo(observer, GeoSourceIds.TRANSITLAND)
        }
    }

    /**
     * Маршрути, що проходять через зупинку.
     *
     * Transitland вкладає посилання на маршрути в саму сутність зупинки
     * (`route_stops[].route`), тому запитується зупинка, а не окремий
     * список маршрутів. Розбір навмисно приймає й пласку форму
     * (`routes[]`) — так відповідь однієї версії API не робить екран
     * порожнім.
     */
    suspend fun routesThroughStop(stopOnestopId: String): GeoResult<List<TransitRoute>> {
        if (stopOnestopId.isBlank()) {
            return GeoResult.Unavailable(SourceUnavailable.RequestRejected("порожній Onestop ID зупинки"))
                .reportTo(observer, GeoSourceIds.TRANSITLAND)
        }
        val text = fetch("stops/${stopOnestopId.trim()}", bbox = null, limit = null)
        if (text is GeoResult.Unavailable) return text
        val body = (text as GeoResult.Ok).value
        return when (val parsed = decodeOrMalformed<StopsEnvelope>(body)) {
            is GeoResult.Unavailable -> parsed.reportTo(observer, GeoSourceIds.TRANSITLAND)
            is GeoResult.Ok -> {
                val fromStops = parsed.value.stops.orEmpty()
                    .flatMap { stop -> stop.routeStops.orEmpty().mapNotNull { it.route } }
                val flat = parsed.value.routes.orEmpty()
                val routes = (fromStops + flat)
                    .mapNotNull { it.toDomain() }
                    .distinctBy { it.onestopId }
                GeoResult.Ok(routes, text.fromCache).reportTo(observer, GeoSourceIds.TRANSITLAND)
            }
        }
    }

    /**
     * Відправлення від зупинки за розкладом.
     *
     * @param withinSeconds горизонт табло вперед від «зараз». Без нього
     *   сервіс віддає залишок службової доби — для табло це сотні рейсів
     *   і зайва квота.
     */
    suspend fun departuresAtStop(
        stopOnestopId: String,
        limit: Int = 20,
        withinSeconds: Int = DEFAULT_DEPARTURE_HORIZON_SEC,
    ): GeoResult<List<TransitDeparture>> {
        if (stopOnestopId.isBlank()) {
            return GeoResult.Unavailable(SourceUnavailable.RequestRejected("порожній Onestop ID зупинки"))
                .reportTo(observer, GeoSourceIds.TRANSITLAND)
        }
        val id = stopOnestopId.trim()
        val text = fetch(
            "stops/$id/departures",
            bbox = null,
            limit = limit,
            extraParams = mapOf("next" to withinSeconds.coerceIn(60, MAX_DEPARTURE_HORIZON_SEC).toString()),
        )
        if (text is GeoResult.Unavailable) return text
        val body = (text as GeoResult.Ok).value
        return when (val parsed = decodeOrMalformed<DeparturesEnvelope>(body)) {
            is GeoResult.Unavailable -> parsed.reportTo(observer, GeoSourceIds.TRANSITLAND)
            is GeoResult.Ok -> {
                val departures = parsed.value.stops.orEmpty().flatMap { stop ->
                    // Айді зупинки беремо з відповіді, а не з запиту: сервіс
                    // розвʼязує псевдоніми, і збережений запитаний айді потім
                    // не зійшовся б із айді в решті даних.
                    val stopId = stop.onestopId?.takeIf { it.isNotBlank() } ?: id
                    stop.departures.orEmpty().mapNotNull { it.toDomain(stopId) }
                }.sortedBy { it.effectiveSecondOfDay ?: Int.MAX_VALUE }
                GeoResult.Ok(departures, text.fromCache).reportTo(observer, GeoSourceIds.TRANSITLAND)
            }
        }
    }

    /** Перевізники, чия зона обслуговування перетинає прямокутник. */
    suspend fun operatorsInBbox(bbox: GeoBBox, limit: Int = 50): GeoResult<List<TransitOperator>> {
        val text = fetch("operators", bbox = bbox, limit = limit)
        if (text is GeoResult.Unavailable) return text
        val body = (text as GeoResult.Ok).value
        return when (val parsed = decodeOrMalformed<OperatorsEnvelope>(body)) {
            is GeoResult.Unavailable -> parsed.reportTo(observer, GeoSourceIds.TRANSITLAND)
            is GeoResult.Ok -> GeoResult.Ok(
                parsed.value.operators.orEmpty().mapNotNull { it.toDomain() },
                text.fromCache,
            ).reportTo(observer, GeoSourceIds.TRANSITLAND)
        }
    }

    /** Спільний шлях запиту: ключ → bbox → кеш → обмежувач → мережа. */
    private suspend fun fetch(
        path: String,
        bbox: GeoBBox?,
        limit: Int?,
        extraParams: Map<String, String> = emptyMap(),
    ): GeoResult<String> {
        val key = apiKey
        if (key.isNullOrBlank()) {
            return GeoResult.Unavailable(SourceUnavailable.MissingApiKey)
                .reportTo(observer, GeoSourceIds.TRANSITLAND)
        }
        if (bbox != null && !bbox.isValid) {
            return GeoResult.Unavailable(SourceUnavailable.RequestRejected("непридатний bbox"))
                .reportTo(observer, GeoSourceIds.TRANSITLAND)
        }
        if (bbox != null && bbox.areaSquareDegrees > MAX_BBOX_SQ_DEG) {
            return GeoResult.Unavailable(
                SourceUnavailable.RequestRejected("bbox ${bbox.areaSquareDegrees} кв.° > $MAX_BBOX_SQ_DEG")
            ).reportTo(observer, GeoSourceIds.TRANSITLAND)
        }

        val extras = extraParams.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
        val cacheKey = "$path|${bbox?.canonical() ?: "-"}|${limit ?: "-"}|$extras"
        // Успіх звітується вже розібраним, у публічних методах — інакше
        // реєстр отримав би дві події на один виклик.
        cache.get(cacheKey)?.let { return GeoResult.Ok(it, fromCache = true) }
        if (!limiter.tryAcquire()) {
            return GeoResult.Unavailable(SourceUnavailable.RateLimited(limiter.retryAfterMs()))
                .reportTo(observer, GeoSourceIds.TRANSITLAND)
        }

        val result = GeoHttp.getText(http, "$baseUrl/$path", TAG) {
            header("apikey", key)
            header("User-Agent", GeoHttp.USER_AGENT)
            // Transitland чекає довготу першою. Переставлені координати не
            // дають помилки — вони дають тихо порожню відповідь, і це
            // найдорожчий різновид збою.
            bbox?.let { parameter("bbox", "${it.minLon},${it.minLat},${it.maxLon},${it.maxLat}") }
            limit?.let { parameter("limit", it.coerceIn(1, MAX_LIMIT)) }
            extraParams.forEach { (name, value) -> parameter(name, value) }
        }
        if (result is GeoResult.Ok) cache.put(cacheKey, result.value)
        return if (result is GeoResult.Unavailable) result.reportTo(observer, GeoSourceIds.TRANSITLAND) else result
    }

    /** Закриває HTTP-клієнт. */
    fun close() = http.close()

    companion object {
        /**
         * Створює клієнт із ключем, не називаючи ktor-типів у сигнатурі:
         * `:core-data` тягне `:core-net` як `implementation`, тож рушій із
         * основного конструктора для нього недоступний.
         */
        fun withKey(apiKey: String): TransitlandClient = TransitlandClient(apiKey = apiKey)

        /** Базовий шлях v2 REST. */
        const val DEFAULT_BASE_URL: String = "https://transit.land/api/v2/rest"

        /**
         * Шість годин. Склад зупинок і маршрутів змінюється разом із
         * публікацією нового фіда — тобто тижнями, а не хвилинами;
         * коротший TTL витрачав би квоту без жодної нової інформації.
         */
        const val DEFAULT_CACHE_TTL_MS: Long = 6L * 60 * 60 * 1000

        /** Не частіше одного запиту на секунду, зі сплеском на 5. */
        const val DEFAULT_MIN_INTERVAL_MS: Long = 1_000L

        /** Понад це — уже не «екран карти», а вивантаження країни. */
        const val MAX_BBOX_SQ_DEG: Double = 4.0

        /** Дві години вперед — стільки табло відправлень має сенс показувати. */
        const val DEFAULT_DEPARTURE_HORIZON_SEC: Int = 2 * 60 * 60

        /** Доба вперед — далі це вже не табло, а вивантаження розкладу. */
        const val MAX_DEPARTURE_HORIZON_SEC: Int = 24 * 60 * 60

        private const val MAX_LIMIT = 500
        private const val TAG = "TransitlandClient"
    }
}

// --- DTO. Усі поля необовʼязкові: Transitland додає й прибирає їх між
// мінорними версіями, і сувора схема тут означала б порожній екран.

@Serializable
private data class StopsEnvelope(
    val stops: List<StopDto>? = null,
    val routes: List<RouteDto>? = null,
)

@Serializable
private data class StopDto(
    @SerialName("onestop_id") val onestopId: String? = null,
    @SerialName("stop_id") val stopId: String? = null,
    @SerialName("stop_name") val stopName: String? = null,
    @SerialName("platform_code") val platformCode: String? = null,
    val geometry: PointGeometry? = null,
    @SerialName("route_stops") val routeStops: List<RouteStopDto>? = null,
) {
    fun toDomain(): TransitStop? {
        val id = onestopId?.takeIf { it.isNotBlank() } ?: return null
        val coords = geometry?.coordinates ?: return null
        // GeoJSON — [довгота, широта]. Переставлення тут дало б зупинки
        // посеред Індійського океану замість Києва.
        if (coords.size < 2) return null
        val lon = coords[0]
        val lat = coords[1]
        if (!lat.isFinite() || !lon.isFinite()) return null
        return TransitStop(
            onestopId = id,
            name = stopName.orEmpty(),
            lat = lat,
            lon = lon,
            localStopId = stopId,
            platformCode = platformCode,
            servedByRouteOnestopIds = routeStops.orEmpty()
                .mapNotNull { it.route?.onestopId }
                .distinct(),
        )
    }
}

@Serializable
private data class RouteStopDto(val route: RouteDto? = null)

@Serializable
private data class PointGeometry(
    val type: String? = null,
    val coordinates: List<Double>? = null,
)

@Serializable
private data class RouteDto(
    @SerialName("onestop_id") val onestopId: String? = null,
    @SerialName("route_short_name") val shortName: String? = null,
    @SerialName("route_long_name") val longName: String? = null,
    @SerialName("route_type") val routeType: Int? = null,
    @SerialName("route_color") val routeColor: String? = null,
    val agency: AgencyDto? = null,
    val operator: OperatorRefDto? = null,
) {
    fun toDomain(): TransitRoute? {
        val id = onestopId?.takeIf { it.isNotBlank() } ?: return null
        val operatorName = operator?.name ?: agency?.agencyName
        return TransitRoute(
            onestopId = id,
            shortName = LineLabel.of(shortName, null, id).ifEmpty { null },
            longName = longName?.takeIf { it.isNotBlank() },
            routeType = routeType,
            mode = TransitModeHeuristic.classify(routeType, shortName, longName, operatorName),
            operatorOnestopId = operator?.onestopId ?: agency?.onestopId,
            operatorName = operatorName,
            colorHex = routeColor?.trim()?.removePrefix("#")?.takeIf { it.length == 6 },
        )
    }
}

@Serializable
private data class AgencyDto(
    @SerialName("onestop_id") val onestopId: String? = null,
    @SerialName("agency_name") val agencyName: String? = null,
)

@Serializable
private data class OperatorRefDto(
    @SerialName("onestop_id") val onestopId: String? = null,
    val name: String? = null,
)

@Serializable
private data class OperatorsEnvelope(val operators: List<OperatorDto>? = null)

@Serializable
private data class OperatorDto(
    @SerialName("onestop_id") val onestopId: String? = null,
    val name: String? = null,
    @SerialName("short_name") val shortName: String? = null,
    val agencies: List<AgencyDto>? = null,
) {
    fun toDomain(): TransitOperator? {
        val id = onestopId?.takeIf { it.isNotBlank() } ?: return null
        return TransitOperator(
            onestopId = id,
            name = name.orEmpty(),
            shortName = shortName?.takeIf { it.isNotBlank() },
            agencyOnestopIds = agencies.orEmpty().mapNotNull { it.onestopId }.distinct(),
        )
    }
}
