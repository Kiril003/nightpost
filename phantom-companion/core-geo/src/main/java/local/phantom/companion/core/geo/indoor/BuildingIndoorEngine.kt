package local.phantom.companion.core.geo.indoor

import local.phantom.companion.core.net.map.OverpassClient
import local.phantom.companion.core.net.map.OverpassElement

/**
 * INDOOR-1 — Building Indoor, Passages, Escalators, Elevators & Mall Directory Models.
 */

enum class EntranceKind(val labelUk: String) {
    MAIN("Головний вхід"),
    SERVICE("Службовий вхід"),
    EMERGENCY("Аварійний вихід"),
    SUBWAY("Вхід до метро"),
    GENERIC("Вхід"),
    ACCESSIBLE("Безбарʼєрний вхід"),
}

enum class PassageKind(val labelUk: String) {
    CORRIDOR("Коридор"),
    STEPS("Сходи"),
    ESCALATOR("Ескалатор"),
    ELEVATOR("Ліфт"),
    FOOTWAY("Прохід"),
    BUILDING_PASSAGE("Прохід крізь будівлю"),
    MOVING_WALKWAY("Траволатор"),
}

enum class IndoorVenueCategory(val labelUk: String) {
    FOOD("Їжа та кава"),
    FASHION("Одяг та взуття"),
    TECH("Електроніка"),
    SUPERMARKET("Продукти"),
    HEALTH("Аптеки та здоровʼя"),
    ENTERTAINMENT("Розваги та кіно"),
    SERVICES("Сервіси та банки"),
    OTHER("Заклади"),
}

data class BuildingEntrance(
    val id: String,
    val lat: Double,
    val lon: Double,
    val kind: EntranceKind,
    val name: String? = null,
    val ref: String? = null,
    val level: Int = 0,
    val isWheelchairAccessible: Boolean = false,
)

data class IndoorPassage(
    val id: String,
    val points: List<Pair<Double, Double>>,
    val level: Int = 0,
    val kind: PassageKind = PassageKind.CORRIDOR,
    val name: String? = null,
)

data class IndoorRoom(
    val id: String,
    val polygon: List<Pair<Double, Double>>,
    val level: Int = 0,
    val name: String? = null,
    val category: String? = null,
    val venueCategory: IndoorVenueCategory = IndoorVenueCategory.OTHER,
)

data class IndoorVenueItem(
    val id: String,
    val name: String,
    val category: IndoorVenueCategory,
    val rawCategory: String? = null,
    val level: Int = 0,
    val unit: String? = null,
    val openingHours: String? = null,
    val lat: Double,
    val lon: Double,
    val phone: String? = null,
    val website: String? = null,
)

data class BuildingIndoorPlan(
    val buildingId: String,
    val name: String? = null,
    val availableLevels: List<Int> = listOf(0),
    val entrances: List<BuildingEntrance> = emptyList(),
    val passages: List<IndoorPassage> = emptyList(),
    val rooms: List<IndoorRoom> = emptyList(),
    val venues: List<IndoorVenueItem> = emptyList(),
) {
    fun passagesForLevel(level: Int): List<IndoorPassage> =
        passages.filter { it.level == level }

    fun roomsForLevel(level: Int): List<IndoorRoom> =
        rooms.filter { it.level == level }

    fun entrancesForLevel(level: Int): List<BuildingEntrance> =
        entrances.filter { it.level == level }

    fun venuesForLevel(level: Int): List<IndoorVenueItem> =
        venues.filter { it.level == level }

    fun venuesByCategory(category: IndoorVenueCategory): List<IndoorVenueItem> =
        venues.filter { it.category == category }
}

/**
 * Чи належить елемент БУДІВЛІ, а не вулиці довкола неї.
 *
 * Запит бере все в радіусі 350 м — footway, service, living_street, parking —
 * і без цього сита кожна стежка двору ставала «проходом» (сині лінії поверх
 * усієї мапи на кадрі власника 01.09 02:18), кожна стоянка — «кімнатою», а
 * `layer` будь-якої труби — поверхом. Ознаки внутрішнього: `indoor`, `level`,
 * `building:part`, ескалатор чи траволатор, прохід крізь будівлю, критий шлях,
 * ліфт, коридор, вхід до метро. Сходи й пішохідні шляхи без них — вулиця.
 */
internal fun isIndoorish(tags: Map<String, String>): Boolean =
    tags.containsKey("indoor") || tags.containsKey("level") || tags.containsKey("building:part") ||
        tags.containsKey("conveying") || tags["tunnel"] == "building_passage" || tags["covered"] == "yes" ||
        tags["highway"] == "elevator" || tags["amenity"] == "elevator" || tags["highway"] == "corridor" ||
        tags["railway"] == "subway_entrance" ||
        // Підземна чи багатоповерхова стоянка — частина будівлі й без `level`;
        // наземна — двір.
        tags["parking"] == "underground" || tags["parking"] == "multi-storey"

class BuildingIndoorEngine(
    private val overpass: OverpassClient = OverpassClient(),
) {
    private val cache = mutableMapOf<String, BuildingIndoorPlan>()

    suspend fun fetchBuildingPlan(lat: Double, lon: Double, radiusM: Int = 350): BuildingIndoorPlan? {
        val cacheKey = "%.4f_%.4f_%d".format(java.util.Locale.US, lat, lon, radiusM)
        cache[cacheKey]?.let { return it }

        val query = """
            [out:json][timeout:15];
            (
              node["entrance"](around:$radiusM,$lat,$lon);
              node["door"](around:$radiusM,$lat,$lon);
              node["barrier"](around:$radiusM,$lat,$lon);
              node["railway"="subway_entrance"](around:$radiusM,$lat,$lon);
              node["highway"~"^(elevator|steps|corridor|footway|pedestrian)$"](around:$radiusM,$lat,$lon);
              node["conveying"~"^(escalator|moving_walkway)$"](around:$radiusM,$lat,$lon);
              node["indoor"](around:$radiusM,$lat,$lon);
              node["shop"](around:$radiusM,$lat,$lon);
              node["amenity"](around:$radiusM,$lat,$lon);
              way["highway"~"^(steps|elevator|corridor|footway|pedestrian|service|living_street)$"](around:$radiusM,$lat,$lon);
              way["tunnel"~"^(building_passage|yes)$"](around:$radiusM,$lat,$lon);
              way["covered"="yes"](around:$radiusM,$lat,$lon);
              way["conveying"~"^(escalator|moving_walkway)$"](around:$radiusM,$lat,$lon);
              way["indoor"](around:$radiusM,$lat,$lon);
              way["building:part"](around:$radiusM,$lat,$lon);
              way["shop"](around:$radiusM,$lat,$lon);
              way["amenity"](around:$radiusM,$lat,$lon);
            );
            out body geom 800;
        """.trimIndent()

        val elements = runCatching { overpass.query(query) }.getOrNull()
        if (elements.isNullOrEmpty()) return null

        val plan = parseElements(cacheKey, elements)
        // Самі входи планом не є: `entrance=yes` стоїть на дверях кожного підʼїзду,
        // і план «із самих дверей» давав панель поверхів посеред двору.
        if (plan.passages.isEmpty() && plan.rooms.isEmpty() && plan.venues.isEmpty()) {
            return null
        }
        cache[cacheKey] = plan
        return plan
    }

    private fun parseElements(id: String, elements: List<OverpassElement>): BuildingIndoorPlan {
        val entrances = mutableListOf<BuildingEntrance>()
        val passages = mutableListOf<IndoorPassage>()
        val rooms = mutableListOf<IndoorRoom>()
        val venues = mutableListOf<IndoorVenueItem>()
        val levels = mutableSetOf<Int>()
        levels.add(0) // Default ground floor always present

        for (e in elements) {
            val tags = e.tags ?: emptyMap()
            // Лише `level`: `layer` — вуличний натяк малювання (міст layer=1,
            // труба layer=-1), і саме він дав «ПОВЕРХ +2 / +1 / −1» удома.
            // Поверхи додаються після циклу — від прийнятого, не від радіуса.
            val level = parseLevel(tags["level"])
            val indoorish = isIndoorish(tags)

            val nLat = e.lat
            val nLon = e.lon

            // 1. Entrances & Node Features
            if (e.type == "node" && nLat != null && nLon != null) {
                if (tags.containsKey("entrance") || tags["railway"] == "subway_entrance") {
                    val accessible = tags["wheelchair"] == "yes" || tags["wheelchair"] == "designated" || tags["ramp"] == "yes"
                    val kind = when {
                        tags["railway"] == "subway_entrance" -> EntranceKind.SUBWAY
                        tags["entrance"] == "main" -> EntranceKind.MAIN
                        tags["entrance"] == "service" -> EntranceKind.SERVICE
                        tags["entrance"] == "emergency" -> EntranceKind.EMERGENCY
                        accessible -> EntranceKind.ACCESSIBLE
                        else -> EntranceKind.GENERIC
                    }
                    entrances.add(
                        BuildingEntrance(
                            id = "ent_${e.id}",
                            lat = nLat,
                            lon = nLon,
                            kind = kind,
                            name = tags["name"] ?: tags["description"],
                            ref = tags["ref"],
                            level = level,
                            isWheelchairAccessible = accessible,
                        )
                    )
                }

                // Node Elevator / Escalator / Stairs
                if (indoorish && (tags["highway"] == "elevator" || tags["amenity"] == "elevator")) {
                    passages.add(
                        IndoorPassage(
                            id = "node_elev_${e.id}",
                            points = listOf(nLat to nLon, nLat to nLon),
                            level = level,
                            kind = PassageKind.ELEVATOR,
                            name = tags["name"] ?: "Ліфт",
                        )
                    )
                } else if (indoorish && tags["conveying"] == "escalator") {
                    passages.add(
                        IndoorPassage(
                            id = "node_esc_${e.id}",
                            points = listOf(nLat to nLon, nLat to nLon),
                            level = level,
                            kind = PassageKind.ESCALATOR,
                            name = tags["name"] ?: "Ескалатор",
                        )
                    )
                }

                // Node Venues (Shops, Cafes, ATMs, Pharmacies)
                val venueName = tags["name"] ?: tags["brand"] ?: tags["operator"]
                // Кавʼярня без indoor/level — вулиця, а не заклад цього будинку.
                if (indoorish && !venueName.isNullOrBlank() && (tags.containsKey("shop") || tags.containsKey("amenity") || tags.containsKey("craft"))) {
                    val cat = mapToVenueCategory(tags)
                    venues.add(
                        IndoorVenueItem(
                            id = "venue_node_${e.id}",
                            name = venueName,
                            category = cat,
                            rawCategory = tags["shop"] ?: tags["amenity"],
                            level = level,
                            unit = tags["addr:unit"] ?: tags["ref"],
                            openingHours = tags["opening_hours"],
                            lat = nLat,
                            lon = nLon,
                            phone = tags["phone"] ?: tags["contact:phone"],
                            website = tags["website"] ?: tags["contact:website"],
                        )
                    )
                }
            }

            // 2. Geometries (Passages, Stairs, Escalators, Rooms, Building Passages)
            val geom = e.geometry?.map { pt -> pt.lat to pt.lon } ?: emptyList()

            if (geom.size >= 2) {
                val isClosed = (geom.size >= 3 && geom.first() == geom.last()) || geom.size >= 4
                // Крамниця чи стоянка без indoor/level — вуличний обʼєкт, не кімната:
                // шість «parking» на кадрі власника 01.09 були стоянками довкола дому.
                val isRoomOrShop = indoorish && (
                    tags["indoor"] == "room" || tags["indoor"] == "area" ||
                        tags.containsKey("shop") || tags.containsKey("amenity") || tags.containsKey("building:part")
                    )

                val vCat = mapToVenueCategory(tags)

                if (isClosed && isRoomOrShop) {
                    val roomName = tags["name"] ?: tags["brand"]
                    rooms.add(
                        IndoorRoom(
                            id = "room_${e.id}",
                            polygon = geom,
                            level = level,
                            name = roomName,
                            category = tags["shop"] ?: tags["amenity"] ?: tags["building:part"],
                            venueCategory = vCat,
                        )
                    )

                    if (!roomName.isNullOrBlank()) {
                        val cLat = geom.map { it.first }.average()
                        val cLon = geom.map { it.second }.average()
                        venues.add(
                            IndoorVenueItem(
                                id = "venue_room_${e.id}",
                                name = roomName,
                                category = vCat,
                                rawCategory = tags["shop"] ?: tags["amenity"],
                                level = level,
                                unit = tags["addr:unit"] ?: tags["ref"],
                                openingHours = tags["opening_hours"],
                                lat = cLat,
                                lon = cLon,
                                phone = tags["phone"] ?: tags["contact:phone"],
                                website = tags["website"] ?: tags["contact:website"],
                            )
                        )
                    }
                } else if (indoorish) {
                    // Стежка двору чи service-проїзд без indoor/level — вулиця: не
                    // прохід. Саме вони малювались синім поверх усієї мапи.
                    val kind = when {
                        tags["conveying"] == "escalator" -> PassageKind.ESCALATOR
                        tags["conveying"] == "moving_walkway" -> PassageKind.MOVING_WALKWAY
                        tags["highway"] == "steps" -> PassageKind.STEPS
                        tags["highway"] == "elevator" || tags["amenity"] == "elevator" -> PassageKind.ELEVATOR
                        tags["tunnel"] == "building_passage" || tags["covered"] == "yes" -> PassageKind.BUILDING_PASSAGE
                        tags["indoor"] == "corridor" -> PassageKind.CORRIDOR
                        else -> PassageKind.FOOTWAY
                    }
                    passages.add(
                        IndoorPassage(
                            id = "way_${e.id}",
                            points = geom,
                            level = level,
                            kind = kind,
                            name = tags["name"],
                        )
                    )
                }
            }
        }

        // Поверхи — лише від прийнятого. Раніше сюди потрапляв `level`/`layer`
        // будь-чого в радіусі, і панель показувала поверхи, за якими нічого немає.
        for (l in entrances.map { it.level } + passages.map { it.level } + rooms.map { it.level } + venues.map { it.level }) {
            levels.add(l)
        }
        return BuildingIndoorPlan(
            buildingId = id,
            availableLevels = levels.sorted(),
            entrances = entrances,
            passages = passages,
            rooms = rooms,
            venues = venues.distinctBy { it.name.lowercase() + "_" + it.level },
        )
    }

    private fun parseLevel(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        return raw.trim().toIntOrNull() ?: raw.split(';', ',', '-').firstOrNull()?.trim()?.toIntOrNull() ?: 0
    }

    private fun mapToVenueCategory(tags: Map<String, String>): IndoorVenueCategory {
        val shop = tags["shop"]
        val amenity = tags["amenity"]

        return when {
            amenity in setOf("fast_food", "restaurant", "cafe", "food_court", "bar", "pub", "ice_cream", "bistro") ||
                shop in setOf("bakery", "pastry", "coffee", "tea", "deli") -> IndoorVenueCategory.FOOD

            shop in setOf("clothes", "shoes", "fashion", "bag", "jewelry", "boutique", "watch", "tailor", "leather") ->
                IndoorVenueCategory.FASHION

            shop in setOf("electronics", "mobile_phone", "computer", "telecommunication", "gadgets", "audio_video") ->
                IndoorVenueCategory.TECH

            shop in setOf("supermarket", "convenience", "greengrocer", "alcohol", "beverages", "department_store", "mall") ->
                IndoorVenueCategory.SUPERMARKET

            amenity in setOf("pharmacy", "clinic", "dentist", "optician") ||
                shop in setOf("chemist", "pharmacy", "optician", "beauty", "cosmetics", "perfumery", "hairdresser") ->
                IndoorVenueCategory.HEALTH

            amenity in setOf("cinema", "theatre", "casino", "bowling_alley", "entertainment", "playground") ||
                tags.containsKey("leisure") -> IndoorVenueCategory.ENTERTAINMENT

            amenity in setOf("bank", "atm", "bureau_de_change", "post_office", "police", "dry_cleaning") ||
                shop in setOf("laundry", "dry_cleaning", "money_lender", "travel_agency", "locksmith") ->
                IndoorVenueCategory.SERVICES

            else -> IndoorVenueCategory.OTHER
        }
    }
}
