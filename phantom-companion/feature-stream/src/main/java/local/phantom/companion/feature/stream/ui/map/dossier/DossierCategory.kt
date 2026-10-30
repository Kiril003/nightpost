package local.phantom.companion.feature.stream.ui.map.dossier

import androidx.annotation.StringRes
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapText

internal enum class DossierCategory(@StringRes private val labelRes: Int, val accentArgb: Long) {
    FUEL(R.string.mapl_b_cat_fuel, 0xFF3B82F6),
    MONEY(R.string.mapl_b_cat_money, 0xFF0EA5E9),
    PHARMACY(R.string.mapl_b_cat_pharmacy, 0xFF22C55E),
    HEALTH(R.string.mapl_b_cat_health, 0xFF10B981),
    FOOD(R.string.mapl_b_cat_food, 0xFFF59E0B),
    SHOP(R.string.mapl_b_cat_shop, 0xFFF97316),
    TRANSPORT(R.string.mapl_b_cat_transport, 0xFF14B8A6),
    LIBRARY(R.string.mapl_b_cat_library, 0xFF8B5CF6),
    EDUCATION(R.string.mapl_b_cat_education, 0xFFA78BFA),
    CULTURE(R.string.mapl_b_cat_culture, 0xFFA855F7),
    LODGING(R.string.mapl_b_cat_lodging, 0xFFEC4899),
    WORSHIP(R.string.mapl_b_cat_worship, 0xFFC084FC),
    PARKING(R.string.mapl_b_cat_parking, 0xFF64748B),
    LEISURE(R.string.mapl_b_cat_leisure, 0xFF4ADE80),
    SAFETY(R.string.mapl_b_cat_safety, 0xFFEF4444),
    POST(R.string.mapl_b_cat_post, 0xFFEAB308),
    OFFICE(R.string.mapl_b_cat_office, 0xFF94A3B8),
    GOVERNMENT(R.string.mapl_b_cat_government, 0xFF94A3B8),
    PLACE(R.string.mapl_b_cat_place, 0x00000000),
    ;

    val labelUk: String get() = MapText.s(labelRes)
    val usesAppAccent: Boolean get() = this == PLACE
}

internal fun dossierCategoryOf(
    tags: Map<String, String>,
    categoryHint: String? = null,
    subcategoryHint: String? = null,
): DossierCategory {
    for (key in TAG_KEYS_BY_PRIORITY) {
        val value = tags[key]?.let(::normalizeToken) ?: continue
        BY_VALUE[value]?.let { return it }
        BY_KEY[key]?.let { return it }
    }
    for (hint in listOfNotNull(subcategoryHint, categoryHint)) {
        val token = normalizeToken(hint)
        if (token.isEmpty()) continue
        BY_VALUE[token]?.let { return it }
        BY_KEY[token]?.let { return it }
    }
    return DossierCategory.PLACE
}

internal fun kindLabelUk(
    tags: Map<String, String>,
    categoryHint: String? = null,
    subcategoryHint: String? = null,
): String? {
    when {
        tags["highway"] == "bus_stop" -> return MapText.s(R.string.mapl_b_kind_bus_stop)
        tags["railway"] == "tram_stop" -> return MapText.s(R.string.mapl_b_kind_tram_stop)
        tags["railway"] == "station" || tags["railway"] == "halt" ->
            return MapText.s(R.string.mapl_b_kind_rail_station)
        tags["railway"] == "subway_entrance" -> return MapText.s(R.string.mapl_b_kind_subway_entrance)
        tags["amenity"] == "bus_station" -> return MapText.s(R.string.mapl_b_kind_bus_station)
    }
    tags["shop"]?.let { shop ->
        val typed = KIND_UK[normalizeToken(shop)]?.let { MapText.s(it) }
        return if (typed != null) {
            MapText.s(R.string.mapl_b_kind_shop_typed, typed)
        } else {
            MapText.s(R.string.mapl_b_kind_shop)
        }
    }
    for (key in TAG_KEYS_BY_PRIORITY) {
        val value = tags[key]?.let(::normalizeToken) ?: continue
        KIND_UK[value]?.let { return MapText.s(it) }
    }
    for (hint in listOfNotNull(subcategoryHint, categoryHint)) {
        val token = normalizeToken(hint)
        if (token.isEmpty()) continue
        KIND_UK[token]?.let { return MapText.s(it) }
    }
    val category = dossierCategoryOf(tags, categoryHint, subcategoryHint)
    return if (category == DossierCategory.PLACE) null else category.labelUk.lowercase()
}

private val TAG_KEYS_BY_PRIORITY = listOf(
    "amenity", "shop", "tourism", "leisure", "healthcare",
    "railway", "highway", "aeroway", "emergency", "office", "man_made",
    "natural", "power",
)

private val BY_KEY: Map<String, DossierCategory> = mapOf(
    "shop" to DossierCategory.SHOP,
    "tourism" to DossierCategory.CULTURE,
    "leisure" to DossierCategory.LEISURE,
    "healthcare" to DossierCategory.HEALTH,
    "railway" to DossierCategory.TRANSPORT,
    "aeroway" to DossierCategory.TRANSPORT,
    "emergency" to DossierCategory.SAFETY,
    "office" to DossierCategory.OFFICE,
)

private val BY_VALUE: Map<String, DossierCategory> = buildMap {
    fun put(category: DossierCategory, vararg values: String) {
        values.forEach { put(it, category) }
    }
    put(DossierCategory.FUEL, "fuel", "charging_station", "gas", "petrol")
    put(DossierCategory.MONEY, "atm", "bank", "bureau_de_change", "money_transfer", "money_lender")
    put(DossierCategory.PHARMACY, "pharmacy", "chemist")
    put(
        DossierCategory.HEALTH,
        "hospital", "clinic", "doctors", "dentist", "veterinary", "healthcare", "laboratory",
        "ambulance_station",
    )
    put(
        DossierCategory.FOOD,
        "cafe", "restaurant", "fast_food", "bar", "pub", "food_court", "ice_cream",
        "biergarten", "bakery", "coffee", "confectionery",
    )
    put(
        DossierCategory.SHOP,
        "supermarket", "convenience", "marketplace", "mall", "department_store",
        "clothes", "electronics", "greengrocer", "butcher", "florist", "kiosk", "shop",
    )
    put(
        DossierCategory.TRANSPORT,
        "bus_station", "bus_stop", "taxi", "station", "halt", "tram_stop", "subway_entrance",
        "platform", "stop_position", "ferry_terminal", "aerodrome", "terminal", "railway",
        "subway",
    )
    put(DossierCategory.LIBRARY, "library", "public_bookcase")
    put(
        DossierCategory.EDUCATION,
        "school", "kindergarten", "university", "college", "language_school", "driving_school",
    )
    put(
        DossierCategory.CULTURE,
        "theatre", "cinema", "arts_centre", "museum", "gallery", "artwork", "monument",
        "memorial", "attraction", "viewpoint", "castle", "ruins", "zoo", "aquarium",
    )
    put(DossierCategory.LODGING, "hotel", "hostel", "guest_house", "motel", "apartment", "camp_site")
    put(DossierCategory.WORSHIP, "place_of_worship", "monastery", "church", "chapel", "cathedral")
    put(DossierCategory.PARKING, "parking", "parking_entrance", "parking_space", "bicycle_parking")
    put(
        DossierCategory.LEISURE,
        "park", "garden", "playground", "fitness_centre", "sports_centre", "pitch",
        "stadium", "swimming_pool", "nature_reserve", "dog_park",
    )
    put(
        DossierCategory.SAFETY,
        "shelter", "police", "fire_station", "drinking_water", "defibrillator",
        "civil_defense", "bunker", "spring", "water_well",
    )
    put(DossierCategory.POST, "post_office", "parcel_locker", "post_box")
    put(
        DossierCategory.GOVERNMENT,
        "townhall", "courthouse", "embassy", "government", "public_building", "prison",
        "community_centre",
    )
    put(DossierCategory.OFFICE, "office", "coworking_space")
}

private val KIND_UK: Map<String, Int> = buildMap {
    put("cafe", R.string.mapl_b_kind_cafe)
    put("restaurant", R.string.mapl_b_kind_restaurant)
    put("bar", R.string.mapl_b_kind_bar)
    put("pub", R.string.mapl_b_kind_pub)
    put("fast_food", R.string.mapl_b_kind_fast_food)
    put("food_court", R.string.mapl_b_kind_food_court)
    put("ice_cream", R.string.mapl_b_kind_ice_cream)
    put("pharmacy", R.string.mapl_b_kind_pharmacy)
    put("bank", R.string.mapl_b_kind_bank)
    put("atm", R.string.mapl_b_kind_atm)
    put("bureau_de_change", R.string.mapl_b_kind_bureau_de_change)
    put("fuel", R.string.mapl_b_kind_fuel)
    put("charging_station", R.string.mapl_b_kind_charging_station)
    put("hospital", R.string.mapl_b_kind_hospital)
    put("clinic", R.string.mapl_b_kind_clinic)
    put("doctors", R.string.mapl_b_kind_doctors)
    put("dentist", R.string.mapl_b_kind_dentist)
    put("veterinary", R.string.mapl_b_kind_veterinary)
    put("school", R.string.mapl_b_kind_school)
    put("kindergarten", R.string.mapl_b_kind_kindergarten)
    put("university", R.string.mapl_b_kind_university)
    put("college", R.string.mapl_b_kind_college)
    put("library", R.string.mapl_b_kind_library)
    put("place_of_worship", R.string.mapl_b_kind_place_of_worship)
    put("police", R.string.mapl_b_kind_police)
    put("fire_station", R.string.mapl_b_kind_fire_station)
    put("post_office", R.string.mapl_b_kind_post_office)
    put("parcel_locker", R.string.mapl_b_kind_parcel_locker)
    put("shelter", R.string.mapl_b_kind_shelter)
    put("parking", R.string.mapl_b_kind_parking)
    put("drinking_water", R.string.mapl_b_kind_drinking_water)
    put("theatre", R.string.mapl_b_kind_theatre)
    put("cinema", R.string.mapl_b_kind_cinema)
    put("marketplace", R.string.mapl_b_kind_marketplace)
    put("taxi", R.string.mapl_b_kind_taxi)
    put("townhall", R.string.mapl_b_kind_townhall)
    put("courthouse", R.string.mapl_b_kind_courthouse)
    put("embassy", R.string.mapl_b_kind_embassy)
    put("toilets", R.string.mapl_b_kind_toilets)
    put("bench", R.string.mapl_b_kind_bench)
    put("waste_basket", R.string.mapl_b_kind_waste_basket)
    put("supermarket", R.string.mapl_b_kind_supermarket)
    put("convenience", R.string.mapl_b_kind_convenience)
    put("bakery", R.string.mapl_b_kind_bakery)
    put("clothes", R.string.mapl_b_kind_clothes)
    put("electronics", R.string.mapl_b_kind_electronics)
    put("hairdresser", R.string.mapl_b_kind_hairdresser)
    put("butcher", R.string.mapl_b_kind_butcher)
    put("greengrocer", R.string.mapl_b_kind_greengrocer)
    put("florist", R.string.mapl_b_kind_florist)
    put("mall", R.string.mapl_b_kind_mall)
    put("kiosk", R.string.mapl_b_kind_kiosk)
    put("department_store", R.string.mapl_b_kind_department_store)
    put("hotel", R.string.mapl_b_kind_hotel)
    put("hostel", R.string.mapl_b_kind_hostel)
    put("museum", R.string.mapl_b_kind_museum)
    put("viewpoint", R.string.mapl_b_kind_viewpoint)
    put("artwork", R.string.mapl_b_kind_artwork)
    put("attraction", R.string.mapl_b_kind_attraction)
    put("monument", R.string.mapl_b_kind_monument)
    put("memorial", R.string.mapl_b_kind_memorial)
    put("gallery", R.string.mapl_b_kind_gallery)
    put("guest_house", R.string.mapl_b_kind_guest_house)
    put("park", R.string.mapl_b_kind_park)
    put("garden", R.string.mapl_b_kind_garden)
    put("playground", R.string.mapl_b_kind_playground)
    put("fitness_centre", R.string.mapl_b_kind_fitness_centre)
    put("sports_centre", R.string.mapl_b_kind_sports_centre)
    put("stadium", R.string.mapl_b_kind_stadium)
    put("swimming_pool", R.string.mapl_b_kind_swimming_pool)
    put("pitch", R.string.mapl_b_kind_pitch)
    put("bus_station", R.string.mapl_b_kind_bus_station)
    put("bus_stop", R.string.mapl_b_kind_bus_stop)
    put("tram_stop", R.string.mapl_b_kind_tram_stop)
    put("subway_entrance", R.string.mapl_b_kind_subway_entrance)
    put("station", R.string.mapl_b_kind_station)
    put("halt", R.string.mapl_b_kind_halt)
    put("platform", R.string.mapl_b_kind_platform)
    put("ferry_terminal", R.string.mapl_b_kind_ferry_terminal)
    put("aerodrome", R.string.mapl_b_kind_aerodrome)
    put("defibrillator", R.string.mapl_b_kind_defibrillator)
    put("spring", R.string.mapl_b_kind_spring)
    put("water_well", R.string.mapl_b_kind_water_well)
    put("ambulance_station", R.string.mapl_b_kind_ambulance_station)
    put("community_centre", R.string.mapl_b_kind_community_centre)
    put("generator", R.string.mapl_b_kind_generator)
    put("substation", R.string.mapl_b_kind_substation)
    put("transformer", R.string.mapl_b_kind_transformer)
    put("car_repair", R.string.mapl_b_kind_car_repair)
    put("tyres", R.string.mapl_b_kind_tyres)
    put("subway", R.string.mapl_b_kind_subway)
}

private fun normalizeToken(raw: String): String =
    raw.trim().lowercase().replace(' ', '_').replace('-', '_')
