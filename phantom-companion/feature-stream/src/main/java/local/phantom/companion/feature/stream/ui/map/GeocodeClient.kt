package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.net.map.GeocoderKind
import local.phantom.companion.core.net.map.GeoEndpointsConfig
import local.phantom.companion.core.net.map.geocodeIsEmulatorLoopback

/**
 * GEO-LAW — the dialect-agnostic face of online geocoding.
 */
interface GeocodeClient {
    suspend fun search(query: String, limit: Int = 5, near: Pair<Double, Double>? = null): List<NominatimResult>
    suspend fun reverse(lat: Double, lon: Double): NominatimResult?
}

/**
 * Resolve the configured [GeocodeClient], or null when online geocoding is off.
 *
 * GEO-LAW: nothing configured is an honest `null` — never a silent hop to the
 * public Nominatim, whose policy forbids exactly this app. Callers already
 * treat null as «шукаємо тільки в офлайн-газетирі».
 */
fun geocodeClientFor(config: GeoEndpointsConfig?): GeocodeClient? {
    val base = config?.geocodeBaseUrl?.takeIf { it.isNotBlank() } ?: return null
    return when (config.geocoderKind) {
        GeocoderKind.PHOTON -> PhotonClient(baseUrl = base)
        GeocoderKind.NOMINATIM -> NominatimClient(
            baseUrl = base,
            isEmulatorDev = config.geocodeIsEmulatorLoopback,
        )
    }
}
