package local.phantom.companion.core.net.map

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.net.geosrc.valueOrNull

@Serializable
data class OverpassResponse(
    val elements: List<OverpassElement>
)

@Serializable
data class OverpassElement(
    val id: Long,
    /** "node" / "way" / "relation" — present on every real response. */
    val type: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    /** Present on `way`/`relation` results fetched with `out center`. */
    val center: OverpassCenter? = null,
    /** Present on `way` results fetched with `out geom` (NAV-1 corridor). */
    val geometry: List<OverpassCenter>? = null,
    /**
     * Вершини шляху за id — приходять із `out geom` разом із геометрією.
     * Без них вузол барʼєра в списку вершин не знайти інакше, ніж збігом
     * координат, а це вгадування: два різні вузли можуть стояти в одній точці.
     */
    val nodes: List<Long>? = null,
    val tags: Map<String, String>? = null
) {
    /** Node coordinate if present, else the way/relation centre. */
    val effectiveLat: Double? get() = lat ?: center?.lat
    val effectiveLon: Double? get() = lon ?: center?.lon
}

@Serializable
data class OverpassCenter(val lat: Double, val lon: Double)

/**
 * Overpass API client for OSM POI density, corridors and indoor queries.
 *
 * Кожен запит проходить крізь [OverpassBudget.shared] — спільний на процес,
 * тож усі клієнти Overpass у застосунку витрачають один бюджет незалежно
 * від того, хто з них першим прокинувся.
 *
 * Дві паралельні відповіді навмисно. `fetchX` віддають список і потрібні
 * старим викликачам; `...Result` віддають [GeoResult], де «джерело мовчить»
 * і «в радіусі нічого немає» — різні стани (закон чесності D8.2).
 */
open class OverpassClient(
    private val endpoints: List<String> = OverpassMirrors.ENDPOINTS,
    private val budget: OverpassBudget = OverpassBudget.shared,
) {
    private val client: HttpClient = HttpClient {
        install(UserAgent) { agent = OverpassMirrors.USER_AGENT }
        install(HttpTimeout) {
            connectTimeoutMillis = 4_000
            socketTimeoutMillis = 8_000
            requestTimeoutMillis = 10_000
        }
        // Код поза 2xx має дійти сюди значенням: 429 і 504 означають різне
        // для наступного дзеркала й для бюджету.
        expectSuccess = false
    }

    /** Run one Overpass QL query across the mirrors; first success wins. */
    open suspend fun query(query: String): List<OverpassElement> =
        queryResult(query).valueOrNull() ?: emptyList()

    /** Той самий запит, але з причиною відмови замість порожнечі. */
    open suspend fun queryResult(query: String): GeoResult<List<OverpassElement>> = runQuery(query)

    private suspend fun runQuery(query: String): GeoResult<List<OverpassElement>> {
        val data = query.replace("\n", " ").trim()
        var last: SourceUnavailable = SourceUnavailable.RequestRejected("список дзеркал порожній")
        for (base in endpoints) {
            when (val outcome = attempt(base, data)) {
                is GeoResult.Ok -> {
                    Log.i(TAG, "overpass ${OverpassMirrors.hostOf(base)} -> ${outcome.value.size} elements")
                    return outcome
                }
                is GeoResult.Unavailable -> {
                    last = outcome.reason
                    Log.w(TAG, "overpass ${OverpassMirrors.hostOf(base)}: ${last.detail}")
                }
            }
        }
        Log.w(TAG, "overpass НЕДОСТУПНИЙ (не порожньо): усі ${endpoints.size} дзеркал — ${last.detail}")
        return GeoResult.Unavailable(last)
    }

    private suspend fun attempt(base: String, data: String): GeoResult<List<OverpassElement>> {
        budget.acquire(base)?.let { return GeoResult.Unavailable(it) }
        val body = try {
            val response = client.get(base) { parameter("data", data) }
            val code = response.status.value
            when {
                code == 429 -> return GeoResult.Unavailable(SourceUnavailable.RateLimited(RETRY_AFTER_MS))
                code !in 200..299 -> return GeoResult.Unavailable(SourceUnavailable.HttpError(code))
                else -> response.bodyAsText()
            }
        } catch (ce: CancellationException) {
            // Скасування — не збій джерела: воно мусить дійти до того, хто
            // скасував, інакше корутина «успішно» повернула б порожнечу.
            throw ce
        } catch (t: Throwable) {
            return GeoResult.Unavailable(
                SourceUnavailable.Transport("${t.javaClass.simpleName}: ${t.message ?: "без деталей"}")
            )
        }
        if (body.isBlank()) return GeoResult.Unavailable(SourceUnavailable.EmptyPayload)
        return try {
            GeoResult.Ok(json.decodeFromString<OverpassResponse>(body).elements)
        } catch (t: Throwable) {
            GeoResult.Unavailable(
                SourceUnavailable.Malformed("${t.javaClass.simpleName}: ${t.message?.take(160) ?: "без деталей"}")
            )
        }
    }

    suspend fun fetchPois(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<OverpassElement> =
        runQuery(OverpassQueries.pois(minLat, minLon, maxLat, maxLon)).valueOrNull() ?: emptyList()

    open suspend fun fetchByNameResult(
        query: String,
        lat: Double,
        lon: Double,
        radiusMeters: Int,
    ): GeoResult<List<OverpassElement>> {
        val q = OverpassQueries.byName(query, lat, lon, radiusMeters) ?: return GeoResult.Ok(emptyList())
        return runQuery(q)
    }

    open suspend fun fetchAroundResult(
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        selector: String,
    ): GeoResult<List<OverpassElement>> = runQuery(OverpassQueries.around(lat, lon, radiusMeters, selector))

    suspend fun fetchAroundAny(
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        selectors: List<String>,
    ): List<OverpassElement> =
        fetchAroundAnyResult(lat, lon, radiusMeters, selectors).valueOrNull() ?: emptyList()

    /**
     * Обʼєднаний запит по кількох селекторах із типізованою відмовою.
     * [limit] — стеля `out center`; ділиться між усіма селекторами разом.
     */
    open suspend fun fetchAroundAnyResult(
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        selectors: List<String>,
        limit: Int = DEFAULT_AROUND_ANY_LIMIT,
    ): GeoResult<List<OverpassElement>> {
        if (selectors.isEmpty()) return GeoResult.Ok(emptyList())
        return runQuery(OverpassQueries.aroundAny(lat, lon, radiusMeters, selectors, limit))
    }

    suspend fun fetchRoads(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): List<OverpassElement> =
        runQuery(OverpassQueries.roads(minLat, minLon, maxLat, maxLon)).valueOrNull() ?: emptyList()

    suspend fun fetchAlong(
        polyline: List<Pair<Double, Double>>,
        radiusMeters: Int,
        selector: String,
    ): List<OverpassElement> = fetchAlongResult(polyline, radiusMeters, selector).valueOrNull() ?: emptyList()

    open suspend fun fetchAlongResult(
        polyline: List<Pair<Double, Double>>,
        radiusMeters: Int,
        selector: String,
    ): GeoResult<List<OverpassElement>> {
        if (polyline.size < 2) return GeoResult.Ok(emptyList())
        return runQuery(OverpassQueries.along(polyline, radiusMeters, selector))
    }

    suspend fun fetchBuildings(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): List<OverpassElement> =
        runQuery(OverpassQueries.buildings(minLat, minLon, maxLat, maxLon)).valueOrNull() ?: emptyList()

    suspend fun fetchCorridor(
        polyline: List<Pair<Double, Double>>,
        wayRadiusMeters: Int,
        cameraRadiusMeters: Int,
    ): List<OverpassElement> {
        if (polyline.size < 2) return emptyList()
        return runQuery(OverpassQueries.corridor(polyline, wayRadiusMeters, cameraRadiusMeters))
            .valueOrNull() ?: emptyList()
    }

    /**
     * Шляхи, що проходять КРІЗЬ задані вузли — рекурсія `way(bn:…)`.
     *
     * Єдиний спосіб дізнатись курс шлагбаума: сам вузол `barrier=*` не несе
     * напрямку, він його ЗАЙМАЄ на дорозі. Порожній список ідентифікаторів —
     * порожня відповідь без походу в мережу.
     */
    suspend fun fetchWaysThroughNodes(nodeIds: List<Long>): List<OverpassElement> {
        if (nodeIds.isEmpty()) return emptyList()
        return runQuery(OverpassQueries.waysThroughNodes(nodeIds)).valueOrNull() ?: emptyList()
    }

    private companion object {
        const val TAG = "OverpassClient"
        const val DEFAULT_AROUND_ANY_LIMIT = 150
        const val RETRY_AFTER_MS = 60_000L

        val json = Json { ignoreUnknownKeys = true }
    }
}
