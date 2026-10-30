package local.phantom.companion.feature.stream.geo

import androidx.annotation.StringRes
import local.phantom.companion.core.data.evac.ShelterTruth
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.data.spatial.SpatialMarker
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.map.OverpassClient
import local.phantom.companion.core.net.map.OverpassElement
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapText

internal object MapGroundLayers {

    const val FUEL_STATIONS = "fuel-stations"
    const val EV_CHARGERS = "ev-chargers"
    const val AUTO_REPAIR = "auto-repair"
    const val SHELTERS = "shelters"
    const val WATER_SOURCES = "water-sources"
    const val PHARMACIES = "pharmacies"
    const val REST_AREAS = "rest-areas"
    const val POWER_TOWERS = "power-towers"
    const val DRAINAGE_CROSSINGS = "drainage-crossings"
    const val FORDS = "fords"

    val quickAccessIds: List<String> = listOf(
        FUEL_STATIONS,
        EV_CHARGERS,
        AUTO_REPAIR,
        SHELTERS,
        WATER_SOURCES,
        PHARMACIES,
        REST_AREAS,
        POWER_TOWERS,
        DRAINAGE_CROSSINGS,
        FORDS,
    )

    fun quickAccessFor(showsUkraineWartime: Boolean): List<String> =
        if (showsUkraineWartime) quickAccessIds else quickAccessIds.filterNot { it == SHELTERS }

    private const val FUEL_COLOR = "#3B82F6"
    private const val EV_COLOR = "#00E676"
    private const val REPAIR_COLOR = "#F59E0B"
    private const val SHELTER_COLOR = "#EF4444"
    private const val WATER_COLOR = "#0EA5E9"
    private const val PHARMACY_COLOR = "#10B981"
    private const val REST_COLOR = "#8B5CF6"
    private const val TOWER_COLOR = "#F59E0B"
    private const val DITCH_COLOR = "#F97316"
    private const val CULVERT_COLOR = "#22C55E"
    private const val FORD_COLOR = "#0EA5E9"

    @StringRes
    fun emptyNotice(id: String): Int = when (id) {
        FUEL_STATIONS -> R.string.lx_map_empty_fuel
        EV_CHARGERS -> R.string.lx_map_empty_ev
        AUTO_REPAIR -> R.string.lx_map_empty_auto_repair
        SHELTERS -> R.string.lx_map_empty_shelters
        WATER_SOURCES -> R.string.lx_map_empty_water
        PHARMACIES -> R.string.lx_map_empty_pharmacies
        REST_AREAS -> R.string.lx_map_empty_rest_areas
        POWER_TOWERS -> R.string.lx_map_empty_power_towers
        DRAINAGE_CROSSINGS -> R.string.lx_map_empty_drainage
        FORDS -> R.string.lx_map_empty_fords
        else -> R.string.lx_map_empty_other
    }

    @StringRes
    fun unavailableNotice(id: String): Int = when (id) {
        FUEL_STATIONS -> R.string.lx_map_unavail_fuel
        EV_CHARGERS -> R.string.lx_map_unavail_ev
        AUTO_REPAIR -> R.string.lx_map_unavail_auto_repair
        SHELTERS -> R.string.lx_map_unavail_shelters
        WATER_SOURCES -> R.string.lx_map_unavail_water
        PHARMACIES -> R.string.lx_map_unavail_pharmacies
        REST_AREAS -> R.string.lx_map_unavail_rest_areas
        POWER_TOWERS -> R.string.lx_map_unavail_power_towers
        DRAINAGE_CROSSINGS -> R.string.lx_map_unavail_drainage
        FORDS -> R.string.lx_map_unavail_fords
        else -> R.string.lx_map_unavail_other
    }

    @StringRes
    fun titleFor(id: String): Int = when (id) {
        FUEL_STATIONS -> R.string.lx_map_title_fuel
        EV_CHARGERS -> R.string.lx_map_title_ev
        AUTO_REPAIR -> R.string.lx_map_title_auto_repair
        SHELTERS -> R.string.lx_map_title_shelters
        WATER_SOURCES -> R.string.lx_map_title_water
        PHARMACIES -> R.string.lx_map_title_pharmacies
        REST_AREAS -> R.string.lx_map_title_rest_areas
        POWER_TOWERS -> R.string.lx_map_title_power_towers
        DRAINAGE_CROSSINGS -> R.string.lx_map_title_drainage
        FORDS -> R.string.lx_map_title_fords
        else -> R.string.lx_map_title_other
    }

    private fun colorFor(id: String): String = when (id) {
        FUEL_STATIONS -> FUEL_COLOR
        EV_CHARGERS -> EV_COLOR
        AUTO_REPAIR -> REPAIR_COLOR
        SHELTERS -> SHELTER_COLOR
        WATER_SOURCES -> WATER_COLOR
        PHARMACIES -> PHARMACY_COLOR
        REST_AREAS -> REST_COLOR
        POWER_TOWERS -> TOWER_COLOR
        DRAINAGE_CROSSINGS -> DITCH_COLOR
        FORDS -> FORD_COLOR
        else -> "#8B5CF6"
    }

    suspend fun fetch(
        id: String,
        overpass: OverpassClient,
        lat: Double,
        lon: Double,
        radiusMeters: Int = 3_000,
    ): GeoResult<List<SpatialMarker>> {
        val probe = when (id) {
            FUEL_STATIONS -> overpass.fetchAroundResult(lat, lon, radiusMeters, "amenity=fuel")
            EV_CHARGERS -> overpass.fetchAroundResult(lat, lon, radiusMeters, "amenity=charging_station")
            AUTO_REPAIR -> overpass.fetchAroundAnyResult(
                lat, lon, radiusMeters,
                listOf("amenity=car_repair", "amenity=car_wash", "shop=tyres"),
            )
            SHELTERS -> overpass.fetchAroundAnyResult(lat, lon, radiusMeters, ShelterTruth.BOMB_SHELTER_SELECTORS)
            WATER_SOURCES -> overpass.fetchAroundAnyResult(
                lat, lon, radiusMeters,
                listOf("amenity=drinking_water", "man_made=water_well", "natural=spring"),
            )
            PHARMACIES -> overpass.fetchAroundResult(lat, lon, radiusMeters, "amenity=pharmacy")
            REST_AREAS -> overpass.fetchAroundAnyResult(
                lat, lon, radiusMeters,
                listOf("highway=rest_area", "highway=services"),
            )
            POWER_TOWERS -> overpass.fetchAroundResult(lat, lon, radiusMeters, "power=tower")
            DRAINAGE_CROSSINGS ->
                overpass.fetchAroundAnyResult(
                    lat, lon, radiusMeters,
                    listOf("waterway~\"drain|ditch\"", "tunnel=culvert"),
                )
            FORDS -> overpass.fetchAroundResult(lat, lon, radiusMeters, "ford=yes")
            else -> GeoResult.Ok(emptyList())
        }
        return when (probe) {
            is GeoResult.Ok -> GeoResult.Ok(markersFrom(id, probe.value))
            is GeoResult.Unavailable -> probe
        }
    }

    internal fun markersFrom(id: String, elements: List<OverpassElement>): List<SpatialMarker> = when (id) {
        FUEL_STATIONS -> elements.mapNotNull { it.toFuelMarker() }
        EV_CHARGERS -> elements.mapNotNull { it.toEvMarker() }
        AUTO_REPAIR -> elements.mapNotNull { it.toAutoRepairMarker() }
        SHELTERS -> elements.mapNotNull { it.toShelterMarker() }
        WATER_SOURCES -> elements.mapNotNull { it.toWaterMarker() }
        PHARMACIES -> elements.mapNotNull { it.toPharmacyMarker() }
        REST_AREAS -> elements.mapNotNull { it.toRestAreaMarker() }
        POWER_TOWERS -> elements.mapNotNull { it.toTowerMarker() }
        DRAINAGE_CROSSINGS -> elements.mapNotNull { it.toDrainageMarker() }
        FORDS -> elements.mapNotNull { it.toFordMarker() }
        else -> emptyList()
    }

    fun specFor(id: String, markers: List<SpatialMarker>): SpatialLayerSpec {
        val title = MapText.s(titleFor(id))
        return SpatialLayerSpec(
            title = title,
            origin = LayerOrigin.WORLD,
            kind = id,
            markers = markers,
            color = colorFor(id),
            glyph = title,
            ttlMs = 60 * 60_000L,
        )
    }

    private fun OverpassElement.toFuelMarker(): SpatialMarker? {
        val lat = effectiveLat ?: return null
        val lon = effectiveLon ?: return null
        val brand = tags?.get("brand")
            ?: tags?.get("operator")
            ?: tags?.get("name")
            ?: MapText.s(R.string.lx_map_marker_fuel_default)
        val fuels = buildList {
            if (tags?.get("fuel:octane_95") == "yes") add("95")
            if (tags?.get("fuel:diesel") == "yes") add(MapText.s(R.string.lx_map_marker_fuel_diesel))
            if (tags?.get("fuel:lpg") == "yes") add(MapText.s(R.string.lx_map_marker_fuel_lpg))
        }.joinToString("/")
        val base = MapText.s(R.string.lx_map_marker_fuel_detail, brand)
        return SpatialMarker(
            lat = lat, lon = lon,
            label = brand,
            glyph = brand,
            color = FUEL_COLOR,
            detail = if (fuels.isNotEmpty()) "$base · $fuels" else base,
        )
    }

    private fun OverpassElement.toEvMarker(): SpatialMarker? {
        val lat = effectiveLat ?: return null
        val lon = effectiveLon ?: return null
        val brand = tags?.get("brand")
            ?: tags?.get("operator")
            ?: MapText.s(R.string.lx_map_marker_ev_default)
        val power = tags?.get("charging_station:output") ?: tags?.get("socket:output")
        val base = MapText.s(R.string.lx_map_marker_ev_detail, brand)
        return SpatialMarker(
            lat = lat, lon = lon,
            label = brand,
            glyph = brand,
            color = EV_COLOR,
            detail = if (power != null) "$base ($power)" else base,
        )
    }

    private fun OverpassElement.toAutoRepairMarker(): SpatialMarker? {
        val lat = effectiveLat ?: return null
        val lon = effectiveLon ?: return null
        val name = tags?.get("name") ?: when {
            tags?.get("shop") == "tyres" -> MapText.s(R.string.lx_map_marker_tyres)
            tags?.get("amenity") == "car_wash" -> MapText.s(R.string.lx_map_marker_car_wash)
            else -> MapText.s(R.string.lx_map_marker_auto_repair)
        }
        return SpatialMarker(
            lat = lat, lon = lon,
            label = name,
            glyph = name,
            color = REPAIR_COLOR,
            detail = MapText.s(R.string.lx_map_marker_auto_repair_detail, name),
        )
    }

    private fun OverpassElement.toShelterMarker(): SpatialMarker? {
        val lat = effectiveLat ?: return null
        val lon = effectiveLon ?: return null
        if (!ShelterTruth.isBombShelter(tags.orEmpty())) return null
        val name = tags?.get("name")
            ?: tags?.get("shelter_type")
            ?: MapText.s(R.string.lx_map_marker_shelter_default)
        return SpatialMarker(
            lat = lat, lon = lon,
            label = name,
            glyph = name,
            color = SHELTER_COLOR,
            detail = MapText.s(R.string.lx_map_marker_shelter_detail, name),
        )
    }

    private fun OverpassElement.toWaterMarker(): SpatialMarker? {
        val lat = effectiveLat ?: return null
        val lon = effectiveLon ?: return null
        val name = tags?.get("name") ?: if (tags?.get("natural") == "spring") {
            MapText.s(R.string.lx_map_marker_spring)
        } else {
            MapText.s(R.string.lx_map_marker_water_default)
        }
        return SpatialMarker(
            lat = lat, lon = lon,
            label = name,
            glyph = name,
            color = WATER_COLOR,
            detail = MapText.s(R.string.lx_map_marker_water_detail, name),
        )
    }

    private fun OverpassElement.toPharmacyMarker(): SpatialMarker? {
        val lat = effectiveLat ?: return null
        val lon = effectiveLon ?: return null
        val name = tags?.get("name")
            ?: tags?.get("brand")
            ?: MapText.s(R.string.lx_map_marker_pharmacy_default)
        val is247 = tags?.get("opening_hours") == "24/7"
        val base = MapText.s(R.string.lx_map_marker_pharmacy_detail, name)
        return SpatialMarker(
            lat = lat, lon = lon,
            label = name,
            glyph = name,
            color = PHARMACY_COLOR,
            detail = if (is247) "$base · 24/7" else base,
        )
    }

    private fun OverpassElement.toRestAreaMarker(): SpatialMarker? {
        val lat = effectiveLat ?: return null
        val lon = effectiveLon ?: return null
        val name = tags?.get("name") ?: MapText.s(R.string.lx_map_marker_rest_area_default)
        return SpatialMarker(
            lat = lat, lon = lon,
            label = name,
            glyph = name,
            color = REST_COLOR,
            detail = MapText.s(R.string.lx_map_marker_rest_area_detail, name),
        )
    }

    private fun OverpassElement.toTowerMarker(): SpatialMarker? {
        val lat = effectiveLat ?: return null
        val lon = effectiveLon ?: return null
        val label = MapText.s(R.string.lx_map_marker_tower_label)
        return SpatialMarker(
            lat = lat, lon = lon,
            label = label,
            glyph = label,
            color = TOWER_COLOR,
            detail = MapText.s(R.string.lx_map_marker_tower_detail),
        )
    }

    private fun OverpassElement.toDrainageMarker(): SpatialMarker? {
        val lat = effectiveLat ?: return null
        val lon = effectiveLon ?: return null
        val isCulvert = tags?.get("tunnel") == "culvert"
        val label = MapText.s(
            if (isCulvert) R.string.lx_map_marker_culvert_label else R.string.lx_map_marker_ditch_label,
        )
        return SpatialMarker(
            lat = lat, lon = lon,
            label = label,
            glyph = label,
            color = if (isCulvert) CULVERT_COLOR else DITCH_COLOR,
            detail = MapText.s(
                if (isCulvert) R.string.lx_map_marker_culvert_detail else R.string.lx_map_marker_ditch_detail,
            ),
        )
    }

    private fun OverpassElement.toFordMarker(): SpatialMarker? {
        val lat = effectiveLat ?: return null
        val lon = effectiveLon ?: return null
        val label = MapText.s(R.string.lx_map_marker_ford_label)
        return SpatialMarker(
            lat = lat, lon = lon,
            label = label,
            glyph = label,
            color = FORD_COLOR,
            detail = MapText.s(R.string.lx_map_marker_ford_detail),
        )
    }
}
