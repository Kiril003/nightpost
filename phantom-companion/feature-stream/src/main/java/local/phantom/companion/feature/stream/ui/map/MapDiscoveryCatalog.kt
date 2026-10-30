package local.phantom.companion.feature.stream.ui.map

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import local.phantom.companion.core.data.country.CountryPackRegistry
import local.phantom.companion.core.data.evac.ShelterTruth
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.source.BatchASource

internal const val INVINCIBILITY_POINT_SELECTOR = "amenity=community_centre"

internal enum class DiscoveryGroup(@StringRes val titleRes: Int, val glyph: PhantomGlyph) {
    ENERGY(R.string.mapl_a_grp_energy, PhantomGlyph.POWER),
    WATER(R.string.mapl_a_grp_water, PhantomGlyph.WATER),
    SAFETY(R.string.mapl_a_grp_safety, PhantomGlyph.SHELTER),
    HEALTH(R.string.mapl_a_grp_health, PhantomGlyph.HOSPITAL),
    COMMS(R.string.mapl_a_grp_comms, PhantomGlyph.WIFI),
    AUTO(R.string.mapl_a_grp_auto, PhantomGlyph.FUEL),
    SHOPS(R.string.mapl_a_grp_shops, PhantomGlyph.CART),
    FOOD(R.string.mapl_a_grp_food, PhantomGlyph.CAFE),
    STATE(R.string.mapl_a_grp_state, PhantomGlyph.CIVIC),
    TERRAIN(R.string.mapl_a_grp_terrain, PhantomGlyph.TREE),
    CULTURE_TOURISM(R.string.mapl_a_grp_culture, PhantomGlyph.VIEW),
    ACCESS(R.string.mapl_a_grp_access, PhantomGlyph.DOOR);

    val title: String get() = MapText.s(titleRes)
}

internal data class DiscoveryCat(
    @StringRes val labelRes: Int,
    val glyph: PhantomGlyph,
    val selector: String,
    val group: DiscoveryGroup,
    @ArrayRes val keywordsRes: Int = 0,
    val ukKeywords: List<String> = emptyList(),
    @StringRes val tacticalNote: Int? = null,
    val quick: Boolean = false,
    val transitHub: Boolean = false,
    val shelf: Int? = null,
    val ukraineWartime: Boolean = false,
) {
    val label: String get() = MapText.s(labelRes)
    val keywords: List<String>
        get() = ((if (keywordsRes != 0) MapText.list(keywordsRes) else emptyList()) + ukKeywords).distinct()
}

internal object MapDiscovery {

    val all: List<DiscoveryCat> = listOf(
        DiscoveryCat(R.string.mapl_a_cat_generators, PhantomGlyph.POWER, "generator:source~\"diesel|gas|petrol|solar\"|power=generator", DiscoveryGroup.ENERGY,
            R.array.mapl_a_kw_generators, BatchASource.GENERATORS_UK,
            tacticalNote = R.string.map_discovery_note_generator, quick = true,
            ukraineWartime = true),
        DiscoveryCat(R.string.mapl_a_cat_ev_charging, PhantomGlyph.EV, "amenity=charging_station", DiscoveryGroup.ENERGY,
            R.array.mapl_a_kw_ev_charging, BatchASource.EV_CHARGING_UK,
            tacticalNote = R.string.map_discovery_note_ev_charging, quick = true),
        DiscoveryCat(R.string.mapl_a_cat_transformers, PhantomGlyph.POWER, "power~\"substation|transformer\"", DiscoveryGroup.ENERGY,
            R.array.mapl_a_kw_transformers, BatchASource.TRANSFORMERS_UK),

        DiscoveryCat(R.string.mapl_a_cat_drinking_water, PhantomGlyph.WATER, "amenity=drinking_water", DiscoveryGroup.WATER,
            R.array.mapl_a_kw_drinking_water, BatchASource.DRINKING_WATER_UK,
            tacticalNote = R.string.map_discovery_note_drinking_water, quick = true, shelf = 6),
        DiscoveryCat(R.string.mapl_a_cat_water_wells, PhantomGlyph.WATER, "man_made=water_well", DiscoveryGroup.WATER,
            R.array.mapl_a_kw_water_wells, BatchASource.WATER_WELLS_UK),
        DiscoveryCat(R.string.mapl_a_cat_springs, PhantomGlyph.WATER, "natural=spring", DiscoveryGroup.WATER,
            R.array.mapl_a_kw_springs, BatchASource.SPRINGS_UK),

        DiscoveryCat(R.string.mapl_a_cat_shelter, PhantomGlyph.SHELTER, ShelterTruth.BOMB_SHELTER_SELECTOR, DiscoveryGroup.SAFETY,
            R.array.mapl_a_kw_shelter, BatchASource.SHELTER_UK,
            tacticalNote = R.string.map_discovery_note_shelter, quick = true, shelf = 5,
            ukraineWartime = true),
        DiscoveryCat(R.string.mapl_a_cat_metro_shelter, PhantomGlyph.METRO, "railway=subway_entrance", DiscoveryGroup.SAFETY,
            R.array.mapl_a_kw_metro_shelter, BatchASource.METRO_SHELTER_UK,
            tacticalNote = R.string.map_discovery_note_metro_shelter, quick = true,
            ukraineWartime = true),
        DiscoveryCat(R.string.mapl_a_cat_aed, PhantomGlyph.HEART, "emergency=defibrillator", DiscoveryGroup.SAFETY,
            R.array.mapl_a_kw_aed, BatchASource.AED_UK),
        DiscoveryCat(R.string.mapl_a_cat_fire_station, PhantomGlyph.FIRE, "amenity=fire_station", DiscoveryGroup.SAFETY,
            R.array.mapl_a_kw_fire_station, BatchASource.FIRE_STATION_UK),
        DiscoveryCat(R.string.mapl_a_cat_police, PhantomGlyph.POLICE, "amenity=police", DiscoveryGroup.SAFETY,
            R.array.mapl_a_kw_police, BatchASource.POLICE_UK),

        DiscoveryCat(R.string.mapl_a_cat_pharmacy, PhantomGlyph.PHARMACY, "amenity=pharmacy", DiscoveryGroup.HEALTH,
            R.array.mapl_a_kw_pharmacy, BatchASource.PHARMACY_UK,
            tacticalNote = R.string.map_discovery_note_pharmacy, quick = true, shelf = 3),
        DiscoveryCat(R.string.mapl_a_cat_hospital, PhantomGlyph.HOSPITAL, "amenity~\"hospital|clinic\"", DiscoveryGroup.HEALTH,
            R.array.mapl_a_kw_hospital, BatchASource.HOSPITAL_UK,
            tacticalNote = R.string.map_discovery_note_hospital, quick = true, shelf = 7),
        DiscoveryCat(R.string.mapl_a_cat_ambulance, PhantomGlyph.PULSE, "emergency=ambulance_station", DiscoveryGroup.HEALTH,
            R.array.mapl_a_kw_ambulance, BatchASource.AMBULANCE_UK),
        DiscoveryCat(R.string.mapl_a_cat_dentist, PhantomGlyph.TOOTH, "amenity=dentist", DiscoveryGroup.HEALTH,
            R.array.mapl_a_kw_dentist, BatchASource.DENTIST_UK),
        DiscoveryCat(R.string.mapl_a_cat_vet, PhantomGlyph.PAW, "amenity=veterinary", DiscoveryGroup.HEALTH,
            R.array.mapl_a_kw_vet, BatchASource.VET_UK),

        DiscoveryCat(R.string.mapl_a_cat_post_office, PhantomGlyph.POST, "amenity=post_office", DiscoveryGroup.COMMS,
            R.array.mapl_a_kw_post_office, BatchASource.POST_OFFICE_UK,
            tacticalNote = R.string.map_discovery_note_post_office, quick = true),
        DiscoveryCat(R.string.mapl_a_cat_parcel_locker, PhantomGlyph.PARCEL, "amenity=parcel_locker", DiscoveryGroup.COMMS,
            R.array.mapl_a_kw_parcel_locker, BatchASource.PARCEL_LOCKER_UK,
            tacticalNote = R.string.map_discovery_note_parcel_locker, quick = true),
        DiscoveryCat(R.string.mapl_a_cat_atm, PhantomGlyph.CARD, "amenity=atm", DiscoveryGroup.COMMS,
            R.array.mapl_a_kw_atm, BatchASource.ATM_UK,
            tacticalNote = R.string.map_discovery_note_atm, quick = true, shelf = 8),
        DiscoveryCat(R.string.mapl_a_cat_bank, PhantomGlyph.CARD, "amenity=bank", DiscoveryGroup.COMMS,
            R.array.mapl_a_kw_bank, BatchASource.BANK_UK),

        DiscoveryCat(R.string.mapl_a_cat_transit_stop, PhantomGlyph.STOP, "highway=bus_stop|public_transport=platform|amenity=bus_station|railway=tram_stop", DiscoveryGroup.AUTO,
            R.array.mapl_a_kw_transit_stop, BatchASource.TRANSIT_STOP_UK,
            tacticalNote = R.string.map_discovery_note_transit_stop, quick = true,
            transitHub = true),
        DiscoveryCat(R.string.mapl_a_cat_transit_hub, PhantomGlyph.TRAIN, "railway~\"subway_entrance|station|halt\"|amenity=bus_station", DiscoveryGroup.AUTO,
            R.array.mapl_a_kw_transit_hub, BatchASource.TRANSIT_HUB_UK,
            tacticalNote = R.string.map_discovery_note_transit_hub, quick = true,
            transitHub = true),
        DiscoveryCat(R.string.mapl_a_cat_fuel, PhantomGlyph.FUEL, "amenity=fuel", DiscoveryGroup.AUTO,
            R.array.mapl_a_kw_fuel, BatchASource.FUEL_UK,
            tacticalNote = R.string.map_discovery_note_fuel, quick = true, shelf = 4),
        DiscoveryCat(R.string.mapl_a_cat_underground_parking, PhantomGlyph.PARKING, "parking=underground", DiscoveryGroup.AUTO,
            R.array.mapl_a_kw_underground_parking, BatchASource.UNDERGROUND_PARKING_UK,
            tacticalNote = R.string.map_discovery_note_underground_parking, quick = true),
        DiscoveryCat(R.string.mapl_a_cat_car_repair, PhantomGlyph.REPAIR, "shop=car_repair", DiscoveryGroup.AUTO,
            R.array.mapl_a_kw_car_repair, BatchASource.CAR_REPAIR_UK),
        DiscoveryCat(R.string.mapl_a_cat_tyres, PhantomGlyph.TYRE, "shop=tyres", DiscoveryGroup.AUTO,
            R.array.mapl_a_kw_tyres, BatchASource.TYRES_UK),

        DiscoveryCat(R.string.mapl_a_cat_supermarket, PhantomGlyph.CART, "shop~\"supermarket|convenience\"", DiscoveryGroup.SHOPS,
            R.array.mapl_a_kw_supermarket, BatchASource.SUPERMARKET_UK,
            tacticalNote = R.string.map_discovery_note_supermarket, quick = true, shelf = 2),
        DiscoveryCat(R.string.mapl_a_cat_marketplace, PhantomGlyph.SHOP, "amenity=marketplace", DiscoveryGroup.SHOPS,
            R.array.mapl_a_kw_marketplace, BatchASource.MARKETPLACE_UK),
        DiscoveryCat(R.string.mapl_a_cat_bakery, PhantomGlyph.BREAD, "shop=bakery", DiscoveryGroup.SHOPS,
            R.array.mapl_a_kw_bakery, BatchASource.BAKERY_UK),
        DiscoveryCat(R.string.mapl_a_cat_hardware, PhantomGlyph.SHOP, "shop~\"hardware|doityourself\"", DiscoveryGroup.SHOPS,
            R.array.mapl_a_kw_hardware, BatchASource.HARDWARE_UK),

        DiscoveryCat(R.string.mapl_a_cat_cafe, PhantomGlyph.CAFE, "amenity=cafe", DiscoveryGroup.FOOD,
            R.array.mapl_a_kw_cafe, BatchASource.CAFE_UK, quick = true, shelf = 1),
        DiscoveryCat(R.string.mapl_a_cat_restaurant, PhantomGlyph.FOOD, "amenity=restaurant", DiscoveryGroup.FOOD,
            R.array.mapl_a_kw_restaurant, BatchASource.RESTAURANT_UK),
        DiscoveryCat(R.string.mapl_a_cat_fast_food, PhantomGlyph.FOOD, "amenity=fast_food", DiscoveryGroup.FOOD,
            R.array.mapl_a_kw_fast_food, BatchASource.FAST_FOOD_UK),

        DiscoveryCat(R.string.mapl_a_cat_invincibility, PhantomGlyph.CIVIC, INVINCIBILITY_POINT_SELECTOR, DiscoveryGroup.STATE,
            R.array.mapl_a_kw_invincibility, BatchASource.INVINCIBILITY_UK,
            tacticalNote = R.string.map_discovery_note_invincibility_point, quick = true,
            ukraineWartime = true),
        DiscoveryCat(R.string.mapl_a_cat_cnap, PhantomGlyph.CIVIC, "office=government", DiscoveryGroup.STATE,
            R.array.mapl_a_kw_cnap, BatchASource.CNAP_UK),
        DiscoveryCat(R.string.mapl_a_cat_townhall, PhantomGlyph.CIVIC, "amenity=townhall", DiscoveryGroup.STATE,
            R.array.mapl_a_kw_townhall, BatchASource.TOWNHALL_UK),

        DiscoveryCat(R.string.mapl_a_cat_bridges, PhantomGlyph.BRIDGE, "bridge=yes", DiscoveryGroup.TERRAIN,
            R.array.mapl_a_kw_bridges, BatchASource.BRIDGES_UK,
            tacticalNote = R.string.map_discovery_note_bridge),
        DiscoveryCat(R.string.mapl_a_cat_peaks, PhantomGlyph.MOUNTAIN, "natural=peak", DiscoveryGroup.TERRAIN,
            R.array.mapl_a_kw_peaks, BatchASource.PEAKS_UK),

        DiscoveryCat(R.string.mapl_a_cat_landmarks, PhantomGlyph.VIEW, "tourism~\"attraction|viewpoint\"|historic~\"monument|memorial|castle|ruins\"", DiscoveryGroup.CULTURE_TOURISM,
            R.array.mapl_a_kw_landmarks, BatchASource.LANDMARKS_UK,
            tacticalNote = R.string.map_discovery_note_landmark, quick = true),
        DiscoveryCat(R.string.mapl_a_cat_museums, PhantomGlyph.VIEW, "tourism~\"museum|gallery\"", DiscoveryGroup.CULTURE_TOURISM,
            R.array.mapl_a_kw_museums, BatchASource.MUSEUMS_UK,
            tacticalNote = R.string.map_discovery_note_museum),
        DiscoveryCat(R.string.mapl_a_cat_parks, PhantomGlyph.TREE, "leisure~\"park|garden\"|tourism=zoo", DiscoveryGroup.CULTURE_TOURISM,
            R.array.mapl_a_kw_parks, BatchASource.PARKS_UK,
            tacticalNote = R.string.map_discovery_note_park, quick = true),
        DiscoveryCat(R.string.mapl_a_cat_worship, PhantomGlyph.CHURCH, "amenity=place_of_worship", DiscoveryGroup.CULTURE_TOURISM,
            R.array.mapl_a_kw_worship, BatchASource.WORSHIP_UK,
            tacticalNote = R.string.map_discovery_note_place_of_worship),

        DiscoveryCat(R.string.mapl_a_cat_entrances, PhantomGlyph.DOOR, "building=entrance|entrance~\"staircase|main|entrance|yes\"", DiscoveryGroup.ACCESS,
            R.array.mapl_a_kw_entrances, BatchASource.ENTRANCES_UK,
            tacticalNote = R.string.map_discovery_note_entrance, quick = true),
        DiscoveryCat(R.string.mapl_a_cat_passages, PhantomGlyph.DOOR, "tunnel=building_passage|covered=arcade", DiscoveryGroup.ACCESS,
            R.array.mapl_a_kw_passages, BatchASource.PASSAGES_UK,
            tacticalNote = R.string.map_discovery_note_passage),
        DiscoveryCat(R.string.mapl_a_cat_steps, PhantomGlyph.STAIRS, "highway=steps|ramp=yes", DiscoveryGroup.ACCESS,
            R.array.mapl_a_kw_steps, BatchASource.STEPS_UK,
            tacticalNote = R.string.map_discovery_note_steps),
    )

    val quick: List<DiscoveryCat> = all.filter { it.quick }

    val byGroup: List<Pair<DiscoveryGroup, List<DiscoveryCat>>> =
        DiscoveryGroup.entries.map { g -> g to all.filter { it.group == g } }

    fun byGroupFor(showsUkraineWartime: Boolean): List<Pair<DiscoveryGroup, List<DiscoveryCat>>> =
        if (showsUkraineWartime) {
            byGroup
        } else {
            byGroup.mapNotNull { (g, cats) ->
                val filtered = cats.filterNot { it.ukraineWartime }
                if (filtered.isEmpty()) null else g to filtered
            }
        }

    val shortlist: List<DiscoveryCat> =
        all.filter { it.shelf != null }.sortedBy { it.shelf }

    fun shortlistFor(showsUkraineWartime: Boolean): List<DiscoveryCat> =
        if (showsUkraineWartime) shortlist else shortlist.filterNot { it.ukraineWartime }

    fun quickFor(showsUkraineWartime: Boolean): List<DiscoveryCat> =
        if (showsUkraineWartime) quick else quick.filterNot { it.ukraineWartime }

    fun resolve(
        raw: String,
        showsUkraineWartime: Boolean = CountryPackRegistry.showsUkraineWartime(),
    ): DiscoveryCat? {
        val q = normalize(raw)
        if (q.length < 2) return null
        val pool = if (showsUkraineWartime) all else all.filterNot { it.ukraineWartime }
        pool.firstOrNull { c -> normalize(c.label) == q || c.keywords.any { normalize(it) == q } }
            ?.let { return it }
        return pool.firstOrNull { c ->
            normalize(c.label).startsWith(q) || c.keywords.any { normalize(it).startsWith(q) }
        }
    }

    fun normalize(s: String): String =
        s.trim().lowercase()
            .replace('\'', '\u02BC')
            .replace('\u2019', '\u02BC')
            .replace('`', '\u02BC')
}
