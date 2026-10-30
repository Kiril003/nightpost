package local.phantom.companion.feature.stream.ui.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.map.GeoLang
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * GEO-LAW — Photon (komoot/photon, Apache-2.0) geocoding client. Photon is
 * meant to be self-hosted, so — unlike [NominatimClient] — this speaks to
 * whatever [baseUrl] the operator configured with no 1-Hz rate limiter of
 * its own (the relay/self-host is responsible for its own capacity).
 *
 * Same shape as [NominatimClient]: HttpURLConnection + kotlinx.serialization,
 * no Ktor, same User-Agent etiquette. Response is a GeoJSON FeatureCollection
 * ([PhotonFeatureCollection]); [parsePhotonFeatures] does the mapping into
 * the shared [NominatimResult] DTO so it's unit-testable without a network.
 */
class PhotonClient(
    internal val baseUrl: String,
    private val userAgent: String = NominatimClient.DEFAULT_USER_AGENT,
    private val json: Json = Json { ignoreUnknownKeys = true },
    locale: Locale = Locale.getDefault(),
    internal val reverseAllowed: Boolean = true,
) : GeocodeClient {

    private val lang = GeoLang.photonLang(locale)

    override suspend fun search(query: String, limit: Int, near: Pair<Double, Double>?): List<NominatimResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        // "UTF-8" рядком: перевантаження з Charset — API 33, а minSdk 29 і
        // десугарування вимкнено. Інакше Android 10-12 падає з NoSuchMethodError
        // на першому ж пошуку. Див. той самий коментар у NominatimClient.
        val q = URLEncoder.encode(query, "UTF-8")
        val url = photonSearchUrl(baseUrl, q, limit, lang, near)
        runCatching { fetchBody(url) }
            .getOrNull()
            ?.let { body -> parsePhotonFeatures(body) }
            ?: emptyList()
    }

    override suspend fun reverse(lat: Double, lon: Double): NominatimResult? = withContext(Dispatchers.IO) {
        if (!reverseAllowed) return@withContext null
        val url = "$baseUrl/reverse?lon=$lon&lat=$lat&lang=$lang"
        runCatching { fetchBody(url) }
            .getOrNull()
            ?.let { body -> parsePhotonFeatures(body).firstOrNull() }
    }

    private fun fetchBody(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 8_000
            readTimeout = 12_000
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Body-to-results mapping split out from transport so the Photon GeoJSON
 * shape is unit-testable (mirrors `parseRouteVariants` in core-net). Garbage
 * or unparsable bodies return an empty list rather than throwing — same
 * honest-failure contract as [NominatimClient.search].
 */
internal fun parsePhotonFeatures(body: String): List<NominatimResult> {
    val collection = runCatching { photonJson.decodeFromString<PhotonFeatureCollection>(body) }
        .getOrNull() ?: return emptyList()
    return collection.features.mapNotNull { feature ->
        val coords = feature.geometry?.coordinates ?: return@mapNotNull null
        val lon = coords.getOrNull(0) ?: return@mapNotNull null
        val lat = coords.getOrNull(1) ?: return@mapNotNull null
        val p = feature.properties ?: PhotonProperties()
        val displayName = listOfNotNull(
            p.name,
            listOfNotNull(p.street, p.housenumber).joinToString(" ").ifBlank { null },
            p.city,
            p.state,
            p.country,
        ).joinToString(", ")
        val address = NominatimAddress(
            road = p.street,
            houseNumber = p.housenumber,
            city = p.city,
            state = p.state,
            country = p.country,
            amenity = p.osmValue?.takeIf { p.osmKey == "amenity" },
            shop = p.osmValue?.takeIf { p.osmKey == "shop" },
        )
        NominatimResult(
            lat = lat.toString(),
            lon = lon.toString(),
            displayName = displayName,
            type = p.osmValue ?: p.osmKey,
            address = address,
        ).inUkraine()
    }
}

private val photonJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class PhotonFeatureCollection(
    val features: List<PhotonFeature> = emptyList(),
)

@Serializable
internal data class PhotonFeature(
    val geometry: PhotonGeometry? = null,
    val properties: PhotonProperties? = null,
)

@Serializable
internal data class PhotonGeometry(
    val coordinates: List<Double> = emptyList(),
)

@Serializable
internal data class PhotonProperties(
    val name: String? = null,
    val street: String? = null,
    val housenumber: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    @kotlinx.serialization.SerialName("osm_key")
    val osmKey: String? = null,
    @kotlinx.serialization.SerialName("osm_value")
    val osmValue: String? = null,
)
