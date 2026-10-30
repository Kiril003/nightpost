package local.phantom.companion.core.data.poi

import kotlin.math.roundToLong

/** Місце з шару `poi` векторної плитки (схема OpenMapTiles), яке мапа вже намалювала. */
object TilePlaces {
    const val SOURCE_TAG = "_tile"
    const val SAME_PLACE_M = 60.0

    private val NAME_KEYS = listOf("name:uk", "name", "name:latin", "name_int", "name:en")

    private val SHOP_CLASSES = setOf(
        "shop", "grocery", "clothing_store", "alcohol_shop", "bakery", "butcher", "hairdresser",
        "jewelry", "mobile_phone", "music", "shoe", "florist", "furniture", "garden_centre",
        "hardware", "laundry", "optician", "stationery", "toys", "beer", "car", "bicycle",
        "books", "gift",
    )
    private val SHOP_VALUES = setOf(
        "supermarket", "convenience", "bakery", "butcher", "greengrocer", "deli", "kiosk",
        "mall", "department_store", "books", "florist", "clothes", "shoes", "hairdresser",
        "beauty", "chemist", "optician", "electronics", "mobile_phone", "alcohol", "beverages",
        "pastry", "confectionery", "gift", "jewelry", "hardware", "doityourself", "furniture",
        "sports", "toys", "stationery", "pet", "bicycle", "car", "car_repair", "laundry",
        "dry_cleaning", "tobacco", "newsagent", "variety_store", "cosmetics", "seafood",
    )
    private val TOURISM_VALUES = setOf(
        "hotel", "hostel", "guest_house", "motel", "apartment", "museum", "gallery",
        "attraction", "viewpoint", "artwork", "information", "zoo", "theme_park", "camp_site",
    )
    private val LEISURE_VALUES = setOf(
        "park", "playground", "sports_centre", "fitness_centre", "garden", "stadium",
        "swimming_pool", "pitch", "dog_park", "water_park", "ice_rink",
    )
    private val STOP_VALUES = setOf("bus_stop", "tram_stop", "platform", "stop_position", "halt")
    private val STATION_VALUES = setOf("station", "subway", "subway_entrance", "bus_station")

    fun entity(lat: Double, lon: Double, props: Map<String, String>): PoiEntity? {
        val cls = props["class"]?.trim().orEmpty()
        val sub = props["subclass"]?.trim()?.takeIf { it.isNotEmpty() } ?: cls.takeIf { it.isNotEmpty() } ?: return null
        val (key, value) = keyOf(cls, sub)
        val tags = LinkedHashMap<String, String>()
        for (k in NAME_KEYS) props[k]?.trim()?.takeIf { it.isNotEmpty() }?.let { tags[k] = it }
        val name = poiDisplayName(tags) ?: return null
        tags[key] = value
        if (key == "public_transport" && sub != value) tags[if (sub == "bus_stop") "highway" else "railway"] = sub
        tags[SOURCE_TAG] = cls
        return PoiEntity(idOf(name, value, lat, lon), lat, lon, name, key, value, tags)
    }

    fun isTile(tags: Map<String, String>): Boolean = SOURCE_TAG in tags

    private fun keyOf(cls: String, sub: String): Pair<String, String> = when {
        sub in STOP_VALUES -> "public_transport" to "platform"
        sub in STATION_VALUES || cls == "railway" -> "railway" to "station"
        sub in TOURISM_VALUES -> "tourism" to sub
        sub in LEISURE_VALUES -> "leisure" to sub
        sub in SHOP_VALUES || cls in SHOP_CLASSES -> "shop" to sub
        else -> "amenity" to sub
    }

    // Негативні id не перетинаються з OSM-id пакетів: тонкий рядок плитки не затре багатий.
    private fun idOf(name: String, kind: String, lat: Double, lon: Double): Long {
        var h = 1125899906842597L
        for (ch in "${name.lowercase()}|$kind|${(lat * 1e4).roundToLong()}|${(lon * 1e4).roundToLong()}") h = 31 * h + ch.code
        return -((h and Long.MAX_VALUE) or 1L)
    }

    fun sameName(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)
}
