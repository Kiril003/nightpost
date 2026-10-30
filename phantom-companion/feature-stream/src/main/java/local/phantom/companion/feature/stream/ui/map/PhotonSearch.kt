package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.net.map.GeoEndpoints
import local.phantom.companion.core.net.map.GeoEndpointsConfig
import java.util.Locale

internal fun kmBias(near: Pair<Double, Double>): Pair<Double, Double> =
    Math.round(near.first * 100.0) / 100.0 to Math.round(near.second * 100.0) / 100.0

internal fun photonSearchUrl(
    base: String,
    encodedQuery: String,
    limit: Int,
    lang: String,
    near: Pair<Double, Double>?,
): String {
    val head = "$base/api?q=$encodedQuery&limit=$limit&lang=$lang"
    if (near == null) return head
    val (lat, lon) = kmBias(near)
    return "$head&lat=${"%.2f".format(Locale.ROOT, lat)}&lon=${"%.2f".format(Locale.ROOT, lon)}"
}

fun placeSearchFor(config: GeoEndpointsConfig?): GeocodeClient =
    geocodeClientFor(config) ?: OfflineOnlyGeocode

object OfflineOnlyGeocode : GeocodeClient {
    override suspend fun search(query: String, limit: Int, near: Pair<Double, Double>?): List<NominatimResult> = emptyList()
    override suspend fun reverse(lat: Double, lon: Double): NominatimResult? = null
}

private val currentClient = java.util.concurrent.atomic.AtomicReference<Pair<GeoEndpointsConfig, GeocodeClient>?>(null)

/** Follows Settings changes; one instance per config keeps the per-client rate limit. */
fun currentPlaceSearch(config: GeoEndpointsConfig = GeoEndpoints.config): GeocodeClient {
    currentClient.get()?.takeIf { it.first == config }?.let { return it.second }
    return placeSearchFor(config).also { currentClient.set(config to it) }
}
