package local.phantom.companion.feature.stream.ui.map.icons

import local.phantom.companion.core.nav.OsmSpatialUniverse
import local.phantom.companion.feature.stream.ui.map.dossier.DossierCategory

/**
 * Каталоги пошуку — на ту саму руку, що й піни.
 *
 * Полиця категорій, сітка «поруч» і шапка досьє жили на власному наборі
 * Material Outlined, підібраному по тих самих тегах OSM: оператор тиснув
 * мальовану аптеку Material і приземлявся на аптеку [PhantomGlyph] — два
 * різні знаки для однієї відповіді. Тут тих таблиць більше нема.
 *
 * Ключ — айді каталогу, не тег: [PoiGlyphTable] відповідає за те, що приїхало
 * з Overpass, а тут — за те, що оператор бачить ДО пошуку.
 */

/** Досьє тапнутого місця. Вичерпний `when`: нова категорія зламає збірку. */
internal fun DossierCategory.glyph(): PhantomGlyph = when (this) {
    DossierCategory.FUEL -> PhantomGlyph.FUEL
    DossierCategory.MONEY -> PhantomGlyph.CARD
    DossierCategory.PHARMACY -> PhantomGlyph.PHARMACY
    DossierCategory.HEALTH -> PhantomGlyph.HOSPITAL
    DossierCategory.FOOD -> PhantomGlyph.FOOD
    DossierCategory.SHOP -> PhantomGlyph.SHOP
    DossierCategory.TRANSPORT -> PhantomGlyph.BUS
    DossierCategory.LIBRARY -> PhantomGlyph.BOOK
    DossierCategory.EDUCATION -> PhantomGlyph.SCHOOL
    // Музей, театр, кіно — родина держави й культури носить одну ратушу:
    // на 14dp окрема маска театру не відрізняється від неї.
    DossierCategory.CULTURE -> PhantomGlyph.CIVIC
    DossierCategory.LODGING -> PhantomGlyph.BED
    DossierCategory.WORSHIP -> PhantomGlyph.CHURCH
    DossierCategory.PARKING -> PhantomGlyph.PARKING
    DossierCategory.LEISURE -> PhantomGlyph.TREE
    DossierCategory.SAFETY -> PhantomGlyph.SHELTER
    DossierCategory.POST -> PhantomGlyph.POST
    DossierCategory.OFFICE -> PhantomGlyph.WORK
    DossierCategory.GOVERNMENT -> PhantomGlyph.CIVIC
    DossierCategory.PLACE -> PhantomGlyph.PLACE
}

/**
 * Тактичний каталог `core-nav` — той, що живить підказки набору.
 *
 * Таблиця явна, бо айді там власні (`med_pharmacy`), а не теги OSM. Хвіст
 * свідомо йде в ромб «просто місце», але `CatalogGlyphTest` не дає жодному
 * айді каталогу туди дійти.
 */
private val SPATIAL_GLYPHS: Map<String, PhantomGlyph> = mapOf(
    "energy_generator" to PhantomGlyph.POWER,
    "energy_ev_charging" to PhantomGlyph.EV,
    "energy_substation" to PhantomGlyph.POWER,
    "water_drinking" to PhantomGlyph.WATER,
    "water_well" to PhantomGlyph.WATER,
    "water_spring" to PhantomGlyph.WATER,
    "defense_bomb_shelter" to PhantomGlyph.SHELTER,
    "defense_subway_shelter" to PhantomGlyph.METRO,
    "defense_defibrillator" to PhantomGlyph.HEART,
    "defense_fire_station" to PhantomGlyph.FIRE,
    "defense_police" to PhantomGlyph.POLICE,
    "med_pharmacy" to PhantomGlyph.PHARMACY,
    "med_hospital" to PhantomGlyph.HOSPITAL,
    "med_ambulance" to PhantomGlyph.PULSE,
    "barrier_lift_gate" to PhantomGlyph.BARRIER,
    "barrier_bollard_block" to PhantomGlyph.BARRIER,
    "barrier_speed_camera" to PhantomGlyph.CAMERA,
    "comms_post_office" to PhantomGlyph.POST,
    "comms_parcel_locker" to PhantomGlyph.PARCEL,
    "comms_cell_tower" to PhantomGlyph.WIFI,
    "fuel_gas_station" to PhantomGlyph.FUEL,
    "auto_repair" to PhantomGlyph.REPAIR,
    "auto_tyres" to PhantomGlyph.TYRE,
    "auto_parking_underground" to PhantomGlyph.PARKING,
    "food_supermarket" to PhantomGlyph.CART,
    "food_market" to PhantomGlyph.SHOP,
    "food_bakery" to PhantomGlyph.BREAD,
    "state_cnap" to PhantomGlyph.CIVIC,
    "state_community_invincible" to PhantomGlyph.CIVIC,
    "terrain_peak" to PhantomGlyph.MOUNTAIN,
    "terrain_bridge" to PhantomGlyph.BRIDGE,
)

internal fun OsmSpatialUniverse.SpatialCategory.glyph(): PhantomGlyph =
    SPATIAL_GLYPHS[id] ?: PhantomGlyph.PLACE

internal val spatialGlyphKeys: Set<String> get() = SPATIAL_GLYPHS.keys
