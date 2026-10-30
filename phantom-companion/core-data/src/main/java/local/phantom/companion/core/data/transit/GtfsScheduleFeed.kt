package local.phantom.companion.core.data.transit

/**
 * ATLAS-3 — оголошений СТАТИЧНИЙ розклад міста.
 *
 * Брат-близнюк [GtfsCityFeed], і навмисно окремий тип: той знає, ДЕ ЗАРАЗ
 * машина (GTFS-RT, протобуф, секунди), цей — О КОТРІЙ вона відправляється
 * (GTFS static, zip, доба). Злити їх в один запис означало б, що місто з
 * розкладом, але без живих позицій, або навпаки, не описується взагалі —
 * а це рівно випадок Києва (розклад є, публічних позицій без ключа нема)
 * і рівно випадок Львова навпаки.
 *
 * @param staticUrl пряме посилання НА ФАЙЛ. Свідомо не каталог: на
 *   data.gov.ua лістинг і файл — різні речі, і колись саме помилка на
 *   лістингу (він відповідав 403) була записана в архітектуру як вирок
 *   «офіційних українських фідів нема». Файли віддавали 200 весь той час.
 * @param cleartext джерело живе лише по http. Оголошується ТУТ, щоб
 *   виняток у network_security_config мав видимого власника, а не
 *   зʼявлявся в маніфесті без сліду.
 */
data class GtfsScheduleFeed(
    val city: String,
    val anchorLat: Double,
    val anchorLon: Double,
    val radiusKm: Double,
    val staticUrl: String,
    /** Хто опублікував і під чим — показується людині поруч із часами. */
    val attribution: String,
    val cleartext: Boolean = staticUrl.startsWith("http://"),
) {
    /** Рамка, у якій зупинки цього фіда мають глузд. */
    val expectedBounds: FeedBounds
        get() {
            val dLat = radiusKm / 111.32
            val dLon = radiusKm / (111.32 * kotlin.math.cos(Math.toRadians(anchorLat)).coerceAtLeast(0.01))
            return FeedBounds(
                south = anchorLat - dLat,
                west = anchorLon - dLon,
                north = anchorLat + dLat,
                east = anchorLon + dLon,
            )
        }
}

object GtfsScheduleRegistry {

    val feeds: List<GtfsScheduleFeed>
        get() = local.phantom.companion.core.data.country.CountryPackRegistry.scheduleFeeds()

    /** Розклад, що покриває точку, або null — чесне «тут розкладу нема». */
    fun feedFor(lat: Double, lon: Double): GtfsScheduleFeed? =
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
