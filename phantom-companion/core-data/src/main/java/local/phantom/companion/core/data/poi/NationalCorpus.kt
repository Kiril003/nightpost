package local.phantom.companion.core.data.poi

/**
 * Види місць, які довідник країни везе без мережі (`scripts/bake_country_places.py`:
 * LABELLED і SHOP_VALUES). Кавʼярень, готелів, вокзалів у ньому немає навмисно, тож
 * порожнеча за ними поза Києвом — «не знаю», а не «поруч немає».
 * NationalCorpusMirrorTest звіряє цей список зі скриптом.
 */
object NationalCorpus {
    val KINDS: Set<String> = setOf(
        "agrarian", "alcohol", "atm", "bakery", "bank", "beverages", "bicycle", "butcher", "car_parts",
        "car_repair", "charging_station", "chemist", "clinic", "convenience", "dairy", "defibrillator",
        "deli", "dentist", "department_store", "doctors", "doityourself", "drinking_water", "electronics",
        "farm", "fire_station", "fishing", "food", "fuel", "gas", "general", "greengrocer", "hardware",
        "hospital", "hunting", "kiosk", "marketplace", "medical_supply", "military_surplus", "mobile_phone",
        "outdoor", "paint", "pet", "pharmacy", "police", "post_box", "post_office", "seafood", "shower",
        "spring", "supermarket", "toilets", "trade", "tyres", "variety_store", "veterinary", "water",
        "water_point", "water_tank", "water_tap", "water_well",
    )
}
