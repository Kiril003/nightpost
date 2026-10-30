package local.phantom.companion.feature.stream.ui.map.around

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.PeerPrecision
import local.phantom.companion.core.data.peers.ShareMode
import local.phantom.companion.core.data.peers.expiredAt
import local.phantom.companion.core.data.peers.freshAt
import local.phantom.companion.feature.stream.ui.map.dossier.haversineMeters

/** Місце поруч: заклад Nightpost (з [venueId] і оцінкою) або місце з мапи пристрою. */
data class AroundPlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val kind: String? = null,
    val address: String? = null,
    val venueId: String? = null,
    val rating: AroundRating? = null,
    val category: String? = null,
    val subcategory: String? = null,
    val hours: String? = null,
)

data class AroundRating(val mean: Double, val count: Int)

sealed interface AroundPlaces {
    /** [venues] — це заклади Nightpost, а не місця з мапи. */
    data class Shown(val places: List<AroundPlace>, val venues: Boolean) : AroundPlaces
    /** Джерело відповіло: поруч нічого. */
    data object Nothing : AroundPlaces
    /** Спитати не вдалось — ні сервера, ні місць на пристрої. */
    data object Unknown : AroundPlaces
}

/** Звідки мапа бере місця поруч; у застосунку — заклади або POI пристрою. */
fun interface MapAroundPort {
    suspend fun places(lat: Double, lon: Double): AroundPlaces

    companion object {
        val None = MapAroundPort { _, _ -> AroundPlaces.Unknown }
    }
}

/** Людина, що ділиться положенням: [meters] null — свого місця нема; [approx] — лише район чи квартал. */
data class AroundFriend(
    val point: PeerPoint,
    val meters: Double?,
    val approx: Boolean,
    val fresh: Boolean,
)

/**
 * Хто з людей зараз ділиться положенням. Разова точка без [PeerPoint.precision]
 * — це надіслане місце, а не людина, тож її тут нема. Одна людина — один рядок,
 * найсвіжіший. Найближчі перші; без свого місця — найсвіжіші перші.
 */
fun aroundFriends(points: List<PeerPoint>, me: Pair<Double, Double>?, nowMs: Long): List<AroundFriend> =
    points.asSequence()
        .filter { !it.expiredAt(nowMs) }
        .filter { it.precision != null || it.share.mode == ShareMode.LIVE || it.share.mode == ShareMode.TRAIL }
        .groupBy { it.nodeId }
        .values
        .map { same -> same.maxBy { it.atMs } }
        .map { p ->
            AroundFriend(
                point = p,
                meters = me?.let { haversineMeters(it.first, it.second, p.lat, p.lon) },
                approx = p.box != null || (p.precision != null && p.precision != PeerPrecision.EXACT),
                fresh = p.freshAt(nowMs),
            )
        }
        .sortedWith(compareBy<AroundFriend>({ it.meters == null }, { it.meters ?: 0.0 }, { -it.point.atMs }))

/** Місця від найближчого; не більше [limit]. */
fun nearestPlaces(places: List<AroundPlace>, lat: Double, lon: Double, limit: Int = PLACES_LIMIT): List<AroundPlace> =
    places.sortedBy { haversineMeters(lat, lon, it.lat, it.lon) }.take(limit)

/**
 * Відповідь `/venues/near` (`summary_view` сервера) — у місця. Заклад без
 * точки на мапі не стає рядком «поруч»: відстані до нього нема. Кривий JSON — null.
 */
fun parseVenuesNear(json: String): List<AroundPlace>? {
    val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
    val rows = root["venues"] as? JsonArray ?: return null
    return rows.mapNotNull { row ->
        val o = row as? JsonObject ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val lon = (o["lon"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val r = o["rating"] as? JsonObject
        val mean = (r?.get("mean") as? JsonPrimitive)?.doubleOrNull
        val count = (r?.get("count") as? JsonPrimitive)?.intOrNull
        AroundPlace(
            name = name,
            lat = lat,
            lon = lon,
            address = o.str("address"),
            venueId = id,
            rating = if (mean != null && count != null && count > 0) AroundRating(mean, count) else null,
        )
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Відстань для рядка: десятки метрів до кілометра, далі десяті; район — цілі кілометри «≈». */
fun aroundDistance(
    meters: Double,
    approx: Boolean,
    m: String,
    km: String,
    locale: java.util.Locale = java.util.Locale.getDefault(),
): String = when {
    approx -> "≈ ${maxOf(1, Math.round(meters / 1000.0).toInt())} $km"
    meters < 1000 -> "${maxOf(10, (Math.round(meters / 10.0) * 10).toInt())} $m"
    meters < 100_000 -> "%.1f %s".format(locale, meters / 1000.0, km)
    else -> "%.0f %s".format(locale, meters / 1000.0, km)
}

const val PLACES_LIMIT = 8
