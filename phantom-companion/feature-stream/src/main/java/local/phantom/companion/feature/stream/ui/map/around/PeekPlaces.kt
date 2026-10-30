package local.phantom.companion.feature.stream.ui.map.around

import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.feature.stream.ui.map.dossier.haversineMeters

internal data class PeekRow(val place: AroundPlace, val meters: Double?)

internal sealed interface PeekPlaces {
    data object Asking : PeekPlaces
    data class Shown(val rows: List<PeekRow>, val total: Int) : PeekPlaces
    data object Nothing : PeekPlaces
    data object ZoomIn : PeekPlaces
}

internal const val PEEK_LIMIT = 12

internal fun PoiEntity.asAroundPlace(): AroundPlace =
    AroundPlace(name, lat, lon, kind = subcategory, category = category, subcategory = subcategory, hours = tags["opening_hours"])

/** Що показує «Поруч» у підглядному аркуші: результати чіпа, коли він обраний, інакше місця навколо. */
internal fun peekPlaces(
    chipActive: Boolean,
    chipHits: List<PoiEntity>,
    chipLoading: Boolean,
    around: AroundPlaces?,
    anchor: Pair<Double, Double>?,
): PeekPlaces {
    if (chipActive) {
        val named = chipHits.filter { it.name.isNotBlank() && it.name != "Unknown" }
        if (named.isEmpty()) return if (chipLoading) PeekPlaces.Asking else PeekPlaces.Nothing
        return PeekPlaces.Shown(rows(named.map { it.asAroundPlace() }, anchor), named.size)
    }
    return when (around) {
        null -> PeekPlaces.Asking
        is AroundPlaces.Shown ->
            if (around.places.isEmpty()) PeekPlaces.Nothing else PeekPlaces.Shown(rows(around.places, anchor), around.places.size)
        AroundPlaces.Nothing -> PeekPlaces.Nothing
        AroundPlaces.Unknown -> PeekPlaces.ZoomIn
    }
}

private fun rows(places: List<AroundPlace>, anchor: Pair<Double, Double>?): List<PeekRow> =
    places.map { p -> PeekRow(p, anchor?.let { haversineMeters(it.first, it.second, p.lat, p.lon) }) }
        .sortedBy { it.meters ?: Double.MAX_VALUE }
        .take(PEEK_LIMIT)
