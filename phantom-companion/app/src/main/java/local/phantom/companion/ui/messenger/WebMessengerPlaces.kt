package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.R
import local.phantom.companion.core.data.poi.OfflinePoiSearch
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.MapAroundSource
import local.phantom.companion.data.VenueClient
import local.phantom.companion.feature.stream.ui.map.around.AroundPlace
import local.phantom.companion.feature.stream.ui.map.around.parseVenuesNear
import local.phantom.companion.feature.stream.ui.map.currentPlaceSearch
import java.net.URLDecoder
import kotlin.math.roundToInt

class WebMessengerPlaces(
    private val here: () -> Pair<Double, Double>?,
    private val venuesNear: suspend (lat: Double, lon: Double) -> List<AroundPlace>,
    private val offline: suspend (q: String, lat: Double, lon: Double, limit: Int) -> List<AroundPlace>,
    private val online: suspend (q: String, near: Pair<Double, Double>) -> List<AroundPlace>,
    private val noFixWord: () -> String = { "no_fix" },
) {
    fun claims(method: String, seg: List<String>): Boolean =
        method == "GET" && seg.size == 2 && seg[0] == "messenger" && seg[1] == SEGMENT

    suspend fun handle(method: String, seg: List<String>, query: String): String {
        if (!claims(method, seg)) return WebMessengerReply.fail(404, "not_found", SEGMENT)
        val params = query.removePrefix("?").split('&').filter { it.isNotEmpty() }.associate {
            it.substringBefore('=') to runCatching { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
        }
        val q = params["q"].orEmpty().trim().take(MAX_QUERY)
        // Заклад за назвою — так само, як пошук по листах: апостроф будь-якого роду, регістр, двійники.
        val needle = WebMessengerSearch.foldQuery(q)
        val lat = params["lat"]?.toDoubleOrNull()
        val lon = params["lon"]?.toDoubleOrNull()
        val webPoint = if (lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
        val me = runCatching { here() }.getOrNull()
        val center = me ?: webPoint ?: return WebMessengerReply.fail(409, "no_fix", noFixWord())
        fun meters(p: AroundPlace) = OfflinePoiSearch.haversineMeters(center.first, center.second, p.lat, p.lon).roundToInt().coerceAtLeast(0)

        val venues = me?.let { runCatching { venuesNear(it.first, it.second) }.getOrDefault(emptyList()) }
            .orEmpty()
            .filter { q.isEmpty() || WebMessengerSearch.indexOfFolded(it.name, needle) >= 0 }
            .sortedBy(::meters)
        val fromPhone = runCatching { offline(q, center.first, center.second, LIMIT) }.getOrDefault(emptyList())
        val others = fromPhone.ifEmpty {
            if (q.isEmpty() || venues.isNotEmpty()) emptyList() else runCatching { online(q, center) }.getOrDefault(emptyList())
        }.filter { it.name.isNotBlank() }
        val rest = MapAroundSource.withoutNear(others, venues.map { it.lat to it.lon }) { it.lat to it.lon }.sortedBy(::meters)

        val rows = (venues + rest).take(LIMIT).map { p ->
            buildJsonObject {
                put("id", p.venueId)
                put("name", p.name)
                put("address", p.address?.takeIf { it.isNotBlank() })
                put("lat", p.lat)
                put("lon", p.lon)
                put("kind", p.kind?.takeIf { it.isNotBlank() })
                put("rating", p.rating?.let { r -> buildJsonObject { put("mean", r.mean); put("count", r.count) } } ?: JsonNull)
                put("menu_version", JsonNull)
                put("distance_m", meters(p))
            }
        }
        return WebMessengerReply.ok(buildJsonObject { put("places", JsonArray(rows)) })
    }

    companion object {
        const val LIMIT = 10
        const val SEGMENT = "places"
        private const val MAX_QUERY = 80

        fun of(container: AppContainer): WebMessengerPlaces = WebMessengerPlaces(
            here = {
                val h = WebMessengerVenues.hereOf(container.locationSnapshotProvider(), WebMessengerVenues.Use.NEAR) {
                    container.applicationContext.getString(it)
                }
                (h as? WebMessengerVenues.Here.Point)?.let { it.lat to it.lon }
            },
            venuesNear = { lat, lon ->
                runCatching {
                    val ans = container.venues.near(WebMessengerVenues.areasAround(lat, lon))
                    (ans as? VenueClient.Answer.Ok)?.let { parseVenuesNear(it.json) }.orEmpty()
                }.getOrDefault(emptyList())
            },
            offline = { q, lat, lon, limit ->
                runCatching {
                    val repo = container.poiRepository
                    if (q.isBlank()) {
                        val near = repo.getNearbyResult(lat, lon, MapAroundSource.RADIUS_M, MapAroundSource.SELECTOR)
                        (near as? GeoResult.Ok)?.value.orEmpty().map { AroundPlace(it.name, it.lat, it.lon, kind = it.subcategory, category = it.category, subcategory = it.subcategory) }
                    } else {
                        repo.searchOffline(q, lat, lon, MapAroundSource.RADIUS_M * 4, emptyList(), limit)
                            .map { AroundPlace(it.name, it.lat, it.lon, kind = it.subcategory, category = it.category, subcategory = it.subcategory) }
                    }
                }.getOrDefault(emptyList())
            },
            online = { q, near ->
                runCatching {
                    currentPlaceSearch().search(q, 5, near).mapNotNull { r ->
                        val title = r.displayName.substringBefore(',').trim().ifBlank { r.cleanTitle }
                        title.takeIf { it.isNotBlank() }?.let {
                            AroundPlace(it, r.latitude, r.longitude, kind = r.type, address = r.address?.streetWithNumber?.takeIf { s -> s != it })
                        }
                    }
                }.getOrDefault(emptyList())
            },
            noFixWord = { container.applicationContext.getString(R.string.msgr_scene_no_fix) },
        )
    }
}
