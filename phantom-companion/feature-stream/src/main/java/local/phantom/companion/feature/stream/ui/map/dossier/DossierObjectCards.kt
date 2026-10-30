package local.phantom.companion.feature.stream.ui.map.dossier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.data.evac.ShelterTruth
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapGroupTitle
import local.phantom.companion.feature.stream.ui.map.MapText
import local.phantom.companion.feature.stream.ui.map.SheetPill
import local.phantom.companion.feature.stream.ui.map.SheetSmall
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import local.phantom.companion.feature.stream.ui.map.mapRaised

/** Ознака існує лише тоді, коли її каже тег, і каже рівно те, що каже тег. */
internal val MEDICAL_AMENITIES = setOf("pharmacy", "hospital", "clinic", "doctors", "dentist", "veterinary")

internal object DossierObjectFacts {

    fun isFuel(tags: Map<String, String>): Boolean =
        tags["amenity"] == "fuel" || tags.keys.any { it.startsWith("fuel:") }

    fun isCharging(tags: Map<String, String>): Boolean =
        tags["amenity"] == "charging_station" || tags.keys.any { it.startsWith("socket:") }

    fun fuelKinds(tags: Map<String, String>): List<String> = buildList {
        if (tags["fuel:octane_95"] == "yes" || tags["fuel:octane_95"] == "only") add(MapText.s(R.string.mapl_d_fuel_a95))
        if (tags["fuel:octane_98"] == "yes" || tags["fuel:octane_100"] == "yes") add(MapText.s(R.string.mapl_d_fuel_a98_100))
        if (tags["fuel:diesel"] == "yes" || tags["fuel:gtl_diesel"] == "yes") add(MapText.s(R.string.mapl_d_fuel_diesel))
        if (tags["fuel:lpg"] == "yes" || tags["fuel:cng"] == "yes") add(MapText.s(R.string.mapl_d_fuel_gas))
        if (tags["fuel:adblue"] == "yes") add("AdBlue")
        if (tags["fuel:electricity"] == "yes" || isCharging(tags)) add(MapText.s(R.string.mapl_d_fuel_ev_charging))
    }

    fun sockets(tags: Map<String, String>): List<String> = buildList {
        if (tags["socket:type2_combo"] == "yes" || tags["socket:ccs"] == "yes") add("CCS 2")
        if (tags["socket:type2"] == "yes") add("Type 2")
        if (tags["socket:chademo"] == "yes") add("CHAdeMO")
        if (tags["socket:gbt"] == "yes") add("GB/T")
        if (tags["socket:type1"] == "yes") add("Type 1")
    }

    fun stationServices(tags: Map<String, String>): List<String> = buildList {
        if (tags["compressed_air"] == "yes") add(MapText.s(R.string.mapl_d_chip_compressed_air))
        if (tags["vacuum_cleaner"] == "yes") add(MapText.s(R.string.mapl_d_srv_vacuum))
        if (tags["car_wash"] == "yes") add(MapText.s(R.string.mapl_d_chip_car_wash))
        if (tags["toilets"] == "yes") add(MapText.s(R.string.mapl_d_srv_toilets))
        if (tags["shop"] != null) add(MapText.s(R.string.mapl_d_srv_shop))
        if (tags["amenity"] == "cafe") add(MapText.s(R.string.mapl_d_srv_cafe))
        if (tags["payment:contactless"] == "yes") add(MapText.s(R.string.mapl_d_srv_contactless))
        else if (tags["payment:cards"] == "yes") add(MapText.s(R.string.mapl_d_srv_card_payment))
    }

    fun parking(tags: Map<String, String>): List<String> = buildList {
        if (tags["park_and_ride"] == "yes") add(MapText.s(R.string.mapl_d_park_park_and_ride))
        if (tags["surveillance"] == "yes") add(MapText.s(R.string.mapl_d_park_surveillance))
        if (tags["parking"] == "underground") add(MapText.s(R.string.mapl_d_park_underground))
        else if (tags["covered"] == "yes") add(MapText.s(R.string.mapl_d_park_covered))
        if (tags["supervised"] == "yes") add(MapText.s(R.string.mapl_d_park_supervised))
    }

    fun shelter(tags: Map<String, String>): List<String> = buildList {
        if (tags["generator:source"] != null || tags["power"] == "generator") add(MapText.s(R.string.mapl_d_shelter_generator))
        if (tags["internet_access"] == "wlan" || tags["internet_access"] == "yes") add("Wi-Fi")
        if (tags["drinking_water"] == "yes") add(MapText.s(R.string.mapl_d_chip_drinking_water))
        if (tags["emergency"] == "defibrillator") add(MapText.s(R.string.mapl_d_chip_defibrillator))
        if (tags["heating"] == "yes") add(MapText.s(R.string.mapl_d_shelter_heating))
        if (tags["toilets"] == "yes") add(MapText.s(R.string.mapl_d_shelter_toilets))
        if (tags["wheelchair"] == "yes") add(MapText.s(R.string.mapl_d_wheelchair_accessible))
    }

    fun dining(tags: Map<String, String>): List<String> = buildList {
        if (tags["outdoor_seating"] == "yes") add(MapText.s(R.string.mapl_d_chip_terrace))
        if (tags["takeaway"] == "yes") add(MapText.s(R.string.mapl_d_chip_takeaway))
        if (tags["drive_through"] == "yes") add(MapText.s(R.string.mapl_d_chip_drive_through))
        if (tags["delivery"] == "yes") add(MapText.s(R.string.mapl_d_chip_delivery))
        if (tags["breakfast"] == "yes") add(MapText.s(R.string.mapl_d_dining_breakfast))
        if (tags["highchair"] == "yes") add(MapText.s(R.string.mapl_d_dining_highchair))
        if (tags["dog"] == "yes" || tags["pets"] == "yes") add(MapText.s(R.string.mapl_d_dining_pets_allowed))
        if (tags["internet_access"] == "wlan" || tags["internet_access"] == "yes") add("Wi-Fi")
        if (tags["payment:cards"] == "yes") add(MapText.s(R.string.mapl_d_srv_card_payment))
    }

    fun medical(tags: Map<String, String>): List<String> = buildList {
        if (tags["emergency"] == "yes") add(MapText.s(R.string.mapl_d_med_emergency))
        if (tags["dispensing"] == "yes") add(MapText.s(R.string.mapl_d_med_dispensing))
        if (tags["defibrillator"] == "yes" || tags["emergency"] == "defibrillator") add(MapText.s(R.string.mapl_d_chip_defibrillator))
        if (tags["blood_pressure"] == "yes") add(MapText.s(R.string.mapl_d_med_blood_pressure))
        if (tags["wheelchair"] == "yes" || tags["ramp"] == "yes") add(MapText.s(R.string.mapl_d_med_ramp))
        if (tags["drive_through"] == "yes") add(MapText.s(R.string.mapl_d_chip_drive_through))
        tags["healthcare:speciality"]?.let { add(it) }
    }

    fun mall(tags: Map<String, String>): List<String> = buildList {
        if (tags["parking"] != null) add(MapText.s(R.string.mapl_d_mall_parking))
        if (tags["cinema"] == "yes") add(MapText.s(R.string.mapl_d_mall_cinema))
        if (tags["food_court"] == "yes") add(MapText.s(R.string.mapl_d_mall_food_court))
        if (tags["atm"] == "yes") add(MapText.s(R.string.mapl_d_chip_atm))
        if (tags["baby_feeding"] == "yes") add(MapText.s(R.string.mapl_d_mall_baby_feeding))
        if (tags["wheelchair"] == "yes") add(MapText.s(R.string.mapl_d_wheelchair_accessible))
        if (tags["elevator"] == "yes") add(MapText.s(R.string.mapl_d_mall_elevator))
    }
}

@Composable
internal fun DossierFuelAndEvCard(dossier: PlaceDossier, accent: Color) {
    val tags = dossier.tags
    val isFuel = DossierObjectFacts.isFuel(tags)
    val isEv = DossierObjectFacts.isCharging(tags)
    if (!isFuel && !isEv) return

    val c = LocalPhantomColors.current

    DossierObjectCardContainer(
        title = if (isEv && !isFuel) stringResource(R.string.mapl_d_card_ev_station) else stringResource(R.string.mapl_d_card_fuel_station),
        glyph = if (isEv && !isFuel) PhantomGlyph.EV else PhantomGlyph.FUEL,
        accent = accent,
    ) {
        if (isFuel) {
            val fuels = DossierObjectFacts.fuelKinds(tags)
            if (fuels.isNotEmpty()) {
                SheetSmall(stringResource(R.string.mapl_d_sec_fuel_kinds), color = c.text2)
                Spacer(Modifier.height(4.dp))
                DossierStringChips(fuels)
                Spacer(Modifier.height(8.dp))
            }
        }

        if (isEv) {
            val sockets = DossierObjectFacts.sockets(tags)
            if (sockets.isNotEmpty()) {
                SheetSmall(stringResource(R.string.mapl_d_sec_sockets), color = c.text2)
                Spacer(Modifier.height(4.dp))
                DossierStringChips(sockets)
                Spacer(Modifier.height(8.dp))
            }

            val power = tags["charging_station:output"] ?: tags["socket:type2_combo:output"] ?: tags["socket:output"]
            if (power != null) {
                DossierFactRow(stringResource(R.string.mapl_d_fact_max_power), power, c.text2)
            }
        }

        val services = DossierObjectFacts.stationServices(tags)
        if (services.isNotEmpty()) {
            SheetSmall(stringResource(R.string.mapl_d_sec_on_site), color = c.text2)
            Spacer(Modifier.height(4.dp))
            DossierStringChips(services)
        }
    }
}

@Composable
internal fun DossierParkingCard(dossier: PlaceDossier, accent: Color) {
    val tags = dossier.tags
    val isParking = tags["amenity"] == "parking" || tags.keys.any { it.startsWith("parking") }
    if (!isParking) return

    val c = LocalPhantomColors.current
    val parkType = when (tags["parking"]) {
        "underground" -> stringResource(R.string.mapl_d_park_title_underground)
        "multi-storey" -> stringResource(R.string.mapl_d_park_title_multi_storey)
        "surface" -> stringResource(R.string.mapl_d_park_title_surface)
        "lane", "street" -> stringResource(R.string.mapl_d_park_title_street)
        else -> stringResource(R.string.mapl_d_park_title_default)
    }

    DossierObjectCardContainer(
        title = parkType,
        glyph = PhantomGlyph.PARKING,
        accent = accent,
    ) {
        val feeFree = stringResource(R.string.mapl_d_fee_free)
        val fee = when (tags["fee"]) {
            "yes" -> stringResource(R.string.mapl_d_fee_paid)
            "no" -> feeFree
            else -> tags["fee"]
        }
        if (fee != null) {
            DossierFactRow(stringResource(R.string.mapl_d_fact_fee), fee, if (fee == feeFree) c.success else c.text2)
        }

        tags["capacity"]?.let {
            DossierFactRow(
                stringResource(R.string.mapl_d_fact_park_capacity),
                stringResource(R.string.mapl_d_fact_park_capacity_cars, it),
                c.text2,
            )
        }
        tags["capacity:disabled"]?.let {
            DossierFactRow(
                stringResource(R.string.mapl_d_fact_park_disabled),
                stringResource(R.string.mapl_d_fact_park_disabled_places, it),
                c.text2,
            )
        }
        tags["maxheight"]?.let {
            DossierFactRow(
                stringResource(R.string.mapl_d_fact_maxheight),
                stringResource(R.string.mapl_d_fact_meters, it),
                c.warning,
            )
        }
        tags["access"]?.let {
            DossierFactRow(
                stringResource(R.string.mapl_d_fact_access_mode),
                if (it == "customers") stringResource(R.string.mapl_d_access_customers) else it,
                c.text2,
            )
        }

        val parkingPills = DossierObjectFacts.parking(tags)
        if (parkingPills.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            DossierStringChips(parkingPills)
        }
    }
}

@Composable
internal fun DossierShelterCard(dossier: PlaceDossier, accent: Color) {
    val tags = dossier.tags
    if (!ShelterTruth.isProtective(tags)) return

    val c = LocalPhantomColors.current
    val shelterKind = when {
        tags["shelter_type"] == "bomb_shelter" -> stringResource(R.string.mapl_d_shelter_title_bomb)
        tags["shelter_type"] == "underground_parking" -> stringResource(R.string.mapl_d_shelter_title_underground_parking)
        tags["shelter_type"] == "subway_station" -> stringResource(R.string.mapl_d_shelter_title_subway)
        tags["shelter_type"] == "basement" -> stringResource(R.string.mapl_d_shelter_title_basement)
        else -> tags["shelter_type"] ?: tags["bunker_type"] ?: stringResource(R.string.mapl_d_shelter_title_default)
    }

    DossierObjectCardContainer(
        title = shelterKind,
        glyph = PhantomGlyph.SHELTER,
        accent = accent,
    ) {
        tags["capacity"]?.let {
            DossierFactRow(
                stringResource(R.string.mapl_d_fact_capacity),
                stringResource(R.string.mapl_d_fact_capacity_persons, it),
                c.text2,
            )
        }

        val survivalPills = DossierObjectFacts.shelter(tags)
        if (survivalPills.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            SheetSmall(stringResource(R.string.mapl_d_sec_in_shelter), color = c.text2)
            Spacer(Modifier.height(4.dp))
            DossierStringChips(survivalPills)
        }
    }
}

@Composable
internal fun DossierDiningCard(dossier: PlaceDossier, accent: Color) {
    val tags = dossier.tags
    val isFood = tags["amenity"] in setOf("restaurant", "cafe", "fast_food", "bar", "pub")
    if (!isFood) return

    val diningPills = DossierObjectFacts.dining(tags)
    if (dossier.cuisine == null && diningPills.isEmpty()) return

    val c = LocalPhantomColors.current

    DossierObjectCardContainer(
        title = dossier.kindLabel ?: stringResource(R.string.mapl_d_card_dining_default),
        glyph = PhantomGlyph.FOOD,
        accent = accent,
    ) {
        dossier.cuisine?.let {
            val cuisines = it.split(',', ';').map { c -> c.trim().replaceFirstChar { ch -> ch.uppercase() } }
            SheetSmall(stringResource(R.string.mapl_d_sec_cuisine), color = c.text2)
            Spacer(Modifier.height(4.dp))
            DossierStringChips(cuisines)
            Spacer(Modifier.height(8.dp))
        }

        if (diningPills.isNotEmpty()) {
            SheetSmall(stringResource(R.string.mapl_d_sec_service), color = c.text2)
            Spacer(Modifier.height(4.dp))
            DossierStringChips(diningPills)
        }
    }
}

@Composable
internal fun DossierMallCard(dossier: PlaceDossier, accent: Color) {
    val tags = dossier.tags
    val isMall = tags["shop"] == "mall" || tags["building"] in setOf("retail", "mall", "commercial")
    if (!isMall) return

    val c = LocalPhantomColors.current

    DossierObjectCardContainer(
        title = stringResource(R.string.mapl_d_card_mall),
        glyph = PhantomGlyph.SHOP,
        accent = accent,
    ) {
        dossier.building?.levels?.let { DossierFactRow(stringResource(R.string.mapl_d_fact_levels), "$it", c.text2) }

        val mallPills = DossierObjectFacts.mall(tags)
        if (mallPills.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            DossierStringChips(mallPills)
        }
    }
}

/**
 * Картка виду обʼєкта: той самий знак, що й пін на мапі (PhantomGlyph), назва
 * групи словом і факти. Знак — чорнилом `text2`: бурштин на аркуші означає дію.
 */
@Composable
private fun DossierObjectCardContainer(
    title: String,
    glyph: PhantomGlyph,
    accent: Color,
    content: @Composable () -> Unit,
) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PhantomSpace.s1)
            .mapRaised()
            .padding(PhantomSpace.s4),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            PhantomGlyphIcon(glyph = glyph, tint = c.text2, modifier = Modifier.size(20.dp))
            MapGroupTitle(title.replaceFirstChar { it.uppercase() })
        }
        Spacer(Modifier.height(PhantomSpace.s2))
        content()
    }
}

/** Пігулки фактів усередині піднятої картки: тло на тон нижче за картку (`raised`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DossierStringChips(items: List<String>) {
    val c = LocalPhantomColors.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { text -> SheetPill(text, fill = c.raised, ink = c.text) }
    }
}
