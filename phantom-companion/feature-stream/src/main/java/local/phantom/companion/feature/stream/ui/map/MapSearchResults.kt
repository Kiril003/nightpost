package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import local.phantom.companion.core.data.places.SavedPlaceCategory
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.PoiHit
import local.phantom.companion.core.data.poi.PoiVocabulary
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import local.phantom.companion.feature.stream.ui.map.icons.glyph
import local.phantom.companion.core.design.R as DesignR

/**
 * Мусить збігатися з `SEARCH_MIN_CHARS` у MapScreen.kt, який гейтить автопошук.
 * Там воно `private` до файла, тож спільної константи поки нема — при зміні
 * рухати обидві.
 */
private const val FLOOD_MIN_CHARS = 2

/**
 * Набране — один список із трьох відповідей, яких геокодер сам не дає:
 * НАМІР категорії (розвʼязаний офлайн, миттєво), власні мітки оператора,
 * і зрештою місця з довідника пристрою.
 */
internal fun LazyListScope.searchResults(
    trimmed: String,
    intent: DiscoveryCat?,
    suggestedCats: List<local.phantom.companion.core.nav.OsmSpatialUniverse.SpatialCategory>,
    results: List<NominatimResult>,
    offlineHits: List<PoiHit>,
    saved: List<PoiEntity>,
    here: Pair<Double, Double>?,
    alongRoute: Boolean,
    inFlight: Boolean,
    submitted: Boolean,
    gap: SearchGap?,
    /** ISO-код країни під якорем — рядок називає країну, лише коли вона інша. */
    hereCountry: String?,
    /** Пакета цієї країни немає на пристрої — одна пропозиція, нагорі. */
    missingPack: MissingPack?,
    onOpenPacks: (() -> Unit)?,
    onPickCategory: (selector: String, label: String) -> Unit,
    onSearchHere: (selector: String, label: String) -> Unit,
    onFloodByName: (String) -> Unit,
    onResultTap: (NominatimResult) -> Unit,
    onOfflineTap: (PoiHit) -> Unit,
    onSavedTap: (PoiEntity) -> Unit,
    onRouteDirect: ((lat: Double, lon: Double, name: String) -> Unit)?,
    onAskAi: (String) -> Unit,
) {
    // Нагорі, бо це відповідь на «чому не те»: рядки нижче чесно підписані
    // чужою країною, і без цієї пропозиції людина лишалась із самим лише
    // діагнозом.
    if (missingPack != null && onOpenPacks != null) {
        item(key = "pack-missing") { MissingPackOffer(missingPack, onOpenPacks) }
    }

    if (suggestedCats.isNotEmpty()) {
        item(key = "cat-head") { SectionLabel(stringResource(R.string.map_search_type_categories)) }
        itemsIndexed(suggestedCats, key = { _, it -> "cat-${it.id}" }) { i, cat ->
            SuggestedCategoryRow(cat, i, suggestedCats.size) { onPickCategory(cat.selector, cat.labelUk) }
        }
    }

    if (trimmed.length >= FLOOD_MIN_CHARS) {
        item(key = "flood") { FloodByNameCard(trimmed) { onFloodByName(trimmed) } }
    }

    if (intent != null) {
        item(key = "intent") {
            IntentBanner(
                cat = intent,
                alongRoute = alongRoute,
                onFlood = { onPickCategory(intent.selector, intent.label) },
                onHere = { onSearchHere(intent.selector, intent.label) },
            )
        }
    }

    // Тут говорить PHANTOM — тож і знак його, «P», а не чужі іскри.
    item(key = "ask") {
        LineRow(
            title = stringResource(R.string.map_search_ask_about, trimmed),
            subtitle = null,
            leading = { PhantomMarkGlyph() },
            modifier = Modifier.padding(vertical = PhantomSpace.s1),
        ) { onAskAi(trimmed) }
    }

    // Імʼя, адреса і категорія по ЦІЛОМУ слову — те саме правило, що в сховищі.
    // Доти екран мав власне, вужче: «додому» не знаходило дім, названий «Хата».
    val savedMatches = saved.filter {
        SavedPlaceCategory.matchesQuery(trimmed, it.name, it.subcategory, it.category)
    }.take(4)
    if (savedMatches.isNotEmpty()) {
        item(key = "saved-head") { SectionLabel(stringResource(R.string.map_search_saved_matches)) }
        itemsIndexed(savedMatches, key = { _, it -> "saved-${it.id}" }) { i, p ->
            LineRow(
                title = p.name,
                subtitle = distanceLabel(here, p.lat, p.lon),
                leading = { MapIcon(DesignR.drawable.lc_bookmark) },
                index = i,
                count = savedMatches.size,
                onRoute = onRouteDirect?.let { fn -> { fn(p.lat, p.lon, p.name) } },
            ) { onSavedTap(p) }
        }
    }

    val dedupedResults = dedupePlaceResults(results)
    if (dedupedResults.isNotEmpty()) {
        item(key = "places-head") { SectionLabel(stringResource(R.string.map_search_places)) }
        itemsIndexed(dedupedResults, key = ::placeResultKey) { i, r ->
            val title = r.cleanTitle
            LineRow(
                title = title,
                subtitle = r.cleanSubtitle.ifBlank { PoiVocabulary.label(r.type) },
                leading = { PlaceLead() },
                index = i,
                count = dedupedResults.size,
                onRoute = onRouteDirect?.let { fn -> { fn(r.latitude, r.longitude, title) } },
            ) { onResultTap(r) }
        }
    }

    // Памʼять пристрою відповідає завжди й першою. Онлайн-секція вище — лише свій
    // налаштований сервер; публічних геокодерів немає (R0-01).
    //
    // Віку паку тут немає навмисно: [PoiHit] не несе ні назви паку, ні дати
    // завантаження, а вигаданий вік гірший за його відсутність.
    if (offlineHits.isNotEmpty()) {
        item(key = "offline-head") { SectionLabel(stringResource(R.string.map_search_offline)) }
        itemsIndexed(offlineHits, key = { _, it -> "offline-${it.id}" }) { i, poi ->
            LineRow(
                title = poi.name,
                subtitle = offlineSubtitle(poi, hereCountry),
                leading = { PlaceLead() },
                index = i,
                count = offlineHits.size,
                onRoute = onRouteDirect?.let { fn -> { fn(poi.lat, poi.lon, poi.name) } },
            ) { onOfflineTap(poi) }
        }
    }

    if (submitted && !inFlight && results.isEmpty() && offlineHits.isEmpty() && intent == null) {
        item(key = "empty") { NothingFound(trimmed, gap) { onAskAi(trimmed) } }
    }
}

/**
 * Two different geocoder rows (a node and a relation for the same place,
 * or plain coincidence) can carry the exact same centroid — a crash on
 * A52, twice in one night, `Key "place-49.8046309-18.2342778" was already
 * used`. Coordinates alone are not a key LazyColumn can trust, and a row
 * that looks identical to the one above it teaches the operator nothing
 * twice. This collapses same-title-same-spot duplicates first, so the
 * index folded into [placeResultKey] only has to be unique, never has to
 * hide a repeat.
 */
internal fun dedupePlaceResults(results: List<NominatimResult>): List<NominatimResult> =
    results.fold(mutableListOf()) { kept, r ->
        if (kept.none { it.cleanTitle.equals(r.cleanTitle, ignoreCase = true) && metersApart(it, r) < 25.0 }) kept += r
        kept
    }

private fun metersApart(a: NominatimResult, b: NominatimResult): Double {
    val dLat = (a.latitude - b.latitude) * 111_320.0
    val dLon = (a.longitude - b.longitude) * 111_320.0 * kotlin.math.cos(Math.toRadians(a.latitude))
    return kotlin.math.hypot(dLat, dLon)
}

internal fun placeResultKey(index: Int, r: NominatimResult): String = "place-$index-${r.latitude}-${r.longitude}"

/** Підказка категорії під час набору: рядок групи, «шукати» — дія словом у кінці. */
@Composable
private fun SuggestedCategoryRow(
    cat: local.phantom.companion.core.nav.OsmSpatialUniverse.SpatialCategory,
    index: Int,
    count: Int,
    onPick: () -> Unit,
) {
    val c = LocalPhantomColors.current
    GroupSlot(index = index, count = count) {
        MapListRow(
            title = cat.labelUk,
            subtitle = cat.tacticalUsageReasonUk,
            leading = {
                PhantomGlyphIcon(glyph = cat.glyph(), tint = c.text2, modifier = Modifier.size(PhantomSize.iconM))
            },
            onClick = onPick,
            trailing = {
                MapTextAction(label = stringResource(R.string.map_search_type_go), onClick = onPick)
            },
        )
    }
}
