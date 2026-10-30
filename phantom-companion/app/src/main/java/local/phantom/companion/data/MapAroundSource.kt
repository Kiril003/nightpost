package local.phantom.companion.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import local.phantom.companion.core.data.poi.OfflinePoiSearch
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.feature.stream.ui.map.around.AroundPlace
import local.phantom.companion.feature.stream.ui.map.around.AroundPlaces
import local.phantom.companion.feature.stream.ui.map.around.MapAroundPort
import local.phantom.companion.feature.stream.ui.map.around.PLACES_LIMIT
import local.phantom.companion.feature.stream.ui.map.around.nearestPlaces
import local.phantom.companion.feature.stream.ui.map.around.parseVenuesNear
import local.phantom.companion.ui.messenger.WebMessengerVenues

/**
 * Місця «поруч» для мапи: спершу заклади Nightpost (`/venues/near`, лише коли
 * двері ввімкнено — інакше [VenueClient] сам каже NotEnabled без запиту), а за
 * ними — місця з мапи пристрою без дублів ближче за [DEDUP_M].
 */
class MapAroundSource(
    private val venues: VenueApi,
    private val localPlaces: suspend (lat: Double, lon: Double) -> GeoResult<List<PoiEntity>>,
) : MapAroundPort {
    override suspend fun places(lat: Double, lon: Double): AroundPlaces = coroutineScope {
        val server = async {
            val near = runCatching { venues.near(WebMessengerVenues.areasAround(lat, lon)) }.getOrNull()
            (near as? VenueClient.Answer.Ok)?.let { parseVenuesNear(it.json) }.orEmpty()
        }
        val device = async { runCatching { localPlaces(lat, lon) }.getOrNull() }
        val fromServer = nearestPlaces(server.await(), lat, lon, Int.MAX_VALUE)
        val local = device.await()
        val fromLocal = (local as? GeoResult.Ok)?.value.orEmpty()
            .filter { it.name.isNotBlank() }
            .map { AroundPlace(it.name, it.lat, it.lon, kind = it.subcategory, category = it.category, subcategory = it.subcategory, hours = it.tags["opening_hours"]) }
            .let { nearestPlaces(it, lat, lon, Int.MAX_VALUE) }
        if (fromServer.isNotEmpty()) {
            val merged = fromServer + withoutNear(fromLocal, fromServer.map { it.lat to it.lon }) { it.lat to it.lon }
            return@coroutineScope AroundPlaces.Shown(merged.take(PLACES_LIMIT), venues = true)
        }
        when {
            local !is GeoResult.Ok -> AroundPlaces.Unknown
            fromLocal.isEmpty() -> AroundPlaces.Nothing
            else -> AroundPlaces.Shown(fromLocal.take(PLACES_LIMIT), venues = false)
        }
    }

    companion object {
        const val RADIUS_M = 1_200
        const val SELECTOR = "amenity~\"cafe|restaurant|bar|pub|fast_food|ice_cream|pharmacy\"|shop~\"supermarket|convenience|bakery\""
        const val DEDUP_M = 40.0

        fun <T> withoutNear(
            items: List<T>,
            anchors: List<Pair<Double, Double>>,
            meters: Double = DEDUP_M,
            at: (T) -> Pair<Double, Double>,
        ): List<T> = items.filter { item ->
            val (lat, lon) = at(item)
            anchors.none { (aLat, aLon) -> OfflinePoiSearch.haversineMeters(lat, lon, aLat, aLon) <= meters }
        }
    }
}
