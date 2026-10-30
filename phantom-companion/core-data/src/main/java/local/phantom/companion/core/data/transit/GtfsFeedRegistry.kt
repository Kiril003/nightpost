package local.phantom.companion.core.data.transit

/**
 * NAV-6 — the honest registry of live GTFS-Realtime VehiclePositions
 * feeds. A city is listed ONLY after live verification of a keyless,
 * publicly reachable feed — everywhere else the map must say «живого
 * фіда для цього міста немає», never animate an empty promise (Law 3).
 *
 * To light up a new city: verify its feed by hand (curl the URL, see
 * protobuf entities), then add one entry to that country's pack. The map
 * picks the feed whose anchor is nearest the viewer and inside [radiusKm].
 */
data class GtfsCityFeed(
    val city: String,
    val anchorLat: Double,
    val anchorLon: Double,
    /** How far from the anchor this feed still covers vehicles. */
    val radiusKm: Double,
    val vehiclePositionsUrl: String,
)

/**
 * Опублікований розклад міста, спечений у пак скриптом
 * `scripts/fetch_gtfs_schedule.py`.
 *
 * Це НЕ джерело позицій — статичний GTFS не знає, де зараз машина. Це
 * джерело хвилин: воно витісняє модель інтервалів там, де перевізник
 * публікує власний розклад, і нічого не обіцяє там, де не публікує.
 */
data class GtfsScheduleAsset(
    val city: String,
    /** Шлях асета в паку — zip у формі GTFS. */
    val asset: String,
    /** Хто це опублікував; проходить той самий аудит ліцензій, що й геометрія. */
    val attribution: String,
)

object GtfsFeedRegistry {

    /**
     * ATLAS-3 — verified feeds now ship inside the country pack that owns the
     * city, not in a list here. The anchor+radius gate below is unchanged, so
     * a pack for a second country lights up its cities without touching this.
     */
    val feeds: List<GtfsCityFeed>
        get() = local.phantom.companion.core.data.country.CountryPackRegistry.transitFeeds()

    /** The feed covering (lat, lon), or null — the honest «нема» state. */
    fun feedFor(lat: Double, lon: Double): GtfsCityFeed? =
        feeds.filter { haversineKm(lat, lon, it.anchorLat, it.anchorLon) <= it.radiusKm }
            .minByOrNull { haversineKm(lat, lon, it.anchorLat, it.anchorLon) }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
    }
}
