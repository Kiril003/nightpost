package local.phantom.companion.core.net.maplayer

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.net.pair.PinnedTrust

/**
 * Map-symbiosis — PC national-layer wire, phone side.
 *
 * Talks to the PC's already-shipped `/api/v1/map/layers` catalog and the
 * per-layer `/api/v1/map/hazards/{id}` bbox endpoint over the existing
 * paired link: same pinned TLS ([PinnedTrust]) and device-JWT bearer
 * pattern as [local.phantom.companion.core.net.roadpack.RoadPackLinkClient].
 *
 * `/map/hazards/cliff_scree` and `/map/hazards/power_towers` are the two
 * shipped instances of one shape (commit `9c1fe50` on the PC side): a
 * locally baked SQLite store queried by viewport bbox, zero upstream call
 * per request. Everything this client offers is filtered to that same
 * shape ([PcLayerManifest.isPullable]) — a layer that needs a live internet
 * fetch on the PC (`rest_polling`, `ws_stream`, …) is not something a
 * disconnected phone can usefully cache, so it is never offered here even
 * if the PC's catalog lists it.
 *
 * KNOWN GAP, stated rather than hidden: as of this writing `/map/layers`
 * and `/map/hazards/…` are gated by the PC's human-only `require_auth`
 * dependency, not the `get_user_or_device_user` dependency `routes_bake.py`
 * already uses for road packs — a phone calling either endpoint with its
 * device JWT gets a real 401 today. This client still targets the real,
 * shipped URL shape rather than inventing a placeholder one; the 401 comes
 * back as an honest [PcLayerLinkException] with the PC's status code, not a
 * crash, so the UI degrades the same way it would for any other refusal.
 */
class PcLayerCatalogClient(
    pinnedCertSha256: () -> String,
    engine: HttpClientEngine = pinnedOkHttpEngine(pinnedCertSha256),
) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val client = HttpClient(engine) {
        install(ContentNegotiation) { json(this@PcLayerCatalogClient.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    @Serializable
    data class PcLayerSource(
        val type: String = "",
        val bbox_required: Boolean = false,
    )

    @Serializable
    data class PcLayerManifest(
        val id: String,
        val name_ua: String,
        val name_en: String = name_ua,
        val category: String = "generic",
        val available_offline: Boolean = false,
        val source: PcLayerSource = PcLayerSource(),
    ) {
        /**
         * The shape commit `9c1fe50` established: a local SQLite store,
         * queried by bbox, no upstream call on the request path. Everything
         * else in the catalog (`rest_polling`, `ws_stream`, raster tiles, …)
         * needs the PC to reach the internet on every request, which
         * defeats the point of a phone pull — those layers never appear in
         * [listPullableLayers] even when `available_offline` is set for
         * some other reason (e.g. a cached tile pyramid).
         */
        val isPullable: Boolean
            get() = available_offline && source.type == "local_db" && source.bbox_required
    }

    @Serializable
    private data class ListLayersResponse(val layers: List<PcLayerManifest> = emptyList())

    /**
     * The PC's catalog, kept to only the layers this phone can actually pull
     * without a code change per layer: `local_db` source, bbox-queried, and
     * marked `available_offline` in the manifest.
     */
    suspend fun listPullableLayers(baseUrl: String, deviceJwt: String): List<PcLayerManifest> {
        val res = client.get("${baseUrl.trimEnd('/')}/api/v1/map/layers") {
            bearerAuth(deviceJwt)
            parameter("offline", "true")
        }
        if (res.status != HttpStatusCode.OK) {
            throw PcLayerLinkException(catalogErrorMessage(res.status))
        }
        val body = res.bodyAsText()
        val parsed = runCatching {
            json.decodeFromString(ListLayersResponse.serializer(), body)
        }.getOrElse { t ->
            throw PcLayerLinkException("зіпсована відповідь ПК: ${t.message}")
        }
        return parsed.layers.filter { it.isPullable }
    }

    sealed interface ExtractResult {
        /** [rawJson] is the PC's untouched `FeatureCollection` body — stored
         *  as-is so a later parser improvement can re-read the same bytes,
         *  the same doctrine `BuildingTileStore` uses for crystal tiles. */
        data class Success(val rawJson: String, val featureCount: Int, val bytes: Long) : ExtractResult
        data class Failure(val message: String) : ExtractResult
    }

    /**
     * One viewport's worth of a layer — "the answers are kilobytes", never
     * the national bake. Never throws: every failure mode (network, auth,
     * a layer the PC's catalog names but has not wired an endpoint for yet,
     * a malformed body) resolves to [ExtractResult.Failure] with an honest
     * Ukrainian reason, so a caller never needs a second try/catch to stay
     * safe.
     */
    suspend fun fetchExtract(
        baseUrl: String,
        deviceJwt: String,
        layerId: String,
        bounds: PcLayerBounds,
        limit: Int = DEFAULT_LIMIT,
    ): ExtractResult = try {
        val res = client.get("${baseUrl.trimEnd('/')}/api/v1/map/hazards/$layerId") {
            bearerAuth(deviceJwt)
            parameter(
                "bounds",
                "${bounds.southLat},${bounds.westLon},${bounds.northLat},${bounds.eastLon}",
            )
            parameter("limit", limit)
        }
        if (!res.status.isSuccess()) {
            ExtractResult.Failure(extractErrorMessage(res.status))
        } else {
            val body = res.bodyAsText()
            val count = runCatching { featureCountOf(body) }.getOrNull()
            if (count == null) {
                ExtractResult.Failure("зіпсована відповідь ПК — не вдалося розібрати шар «$layerId».")
            } else {
                ExtractResult.Success(
                    rawJson = body,
                    featureCount = count,
                    bytes = body.toByteArray(Charsets.UTF_8).size.toLong(),
                )
            }
        }
    } catch (t: Throwable) {
        ExtractResult.Failure(t.message ?: "Помилка звʼязку з ПК.")
    }

    private fun featureCountOf(body: String): Int {
        val obj = Json.parseToJsonElement(body).jsonObject
        obj["total"]?.jsonPrimitive?.intOrNull?.let { return it }
        return obj.getValue("features").jsonArray.size
    }

    private fun catalogErrorMessage(status: HttpStatusCode): String = when (status) {
        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
            "ПК відмовив у доступі до каталогу шарів (${status.value})."
        else -> "ПК не віддав каталог шарів: ${status.value}."
    }

    private fun extractErrorMessage(status: HttpStatusCode): String = when (status) {
        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
            "ПК відмовив у доступі до цього шару (${status.value})."
        HttpStatusCode.NotFound ->
            "ПК ще не ввімкнув передачу цього шару."
        else -> "ПК відповів ${status.value}."
    }

    companion object {
        const val DEFAULT_LIMIT = 5000
    }
}

/** A latitude/longitude viewport — kept local to this client so `:core-net`
 *  never needs to depend on `:feature-stream`'s `OfflineCoverage.Area`. */
data class PcLayerBounds(
    val southLat: Double,
    val westLon: Double,
    val northLat: Double,
    val eastLon: Double,
)

class PcLayerLinkException(message: String) : RuntimeException(message)

private fun pinnedOkHttpEngine(pinnedCertSha256: () -> String): HttpClientEngine =
    OkHttp.create {
        preconfigured = PinnedTrust
            .apply(okhttp3.OkHttpClient.Builder(), pinnedCertSha256)
            .build()
    }
