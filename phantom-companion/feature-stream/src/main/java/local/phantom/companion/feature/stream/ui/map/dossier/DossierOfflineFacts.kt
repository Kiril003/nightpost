package local.phantom.companion.feature.stream.ui.map.dossier

import local.phantom.companion.core.data.poi.PoiHit

internal fun gazetteerHit(hit: PoiHit): DossierLocalHit = DossierLocalHit(
    source = DossierSourceKind.GAZETTEER,
    name = hit.name,
    lat = hit.lat,
    lon = hit.lon,
    distanceM = hit.distanceMeters,
    osmId = hit.id,
    categoryHint = hit.category,
    subcategoryHint = hit.subcategory,
    tags = hit.tags,
)

/** Адреса з тегів самого місця — офлайн і точніша за геокодер, що бачить лише будинок. */
internal fun tagAddress(tags: Map<String, String>): String? {
    val street = (tags["addr:street"] ?: tags["addr:place"])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val house = tags["addr:housenumber"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val city = tags["addr:city"]?.trim()?.takeIf { it.isNotEmpty() }
    return listOfNotNull(street, house, city).joinToString(", ")
}

internal enum class CoreField { HOURS, ADDRESS, PHONE }

/** `value == null` — поле має сенс для цього місця, але даних нема. */
internal data class CoreFact(val field: CoreField, val value: String?)

internal fun coreFacts(d: PlaceDossier): List<CoreFact> {
    return buildList {
        d.rawHours?.let { add(CoreFact(CoreField.HOURS, it)) }
        d.address?.let { add(CoreFact(CoreField.ADDRESS, it)) }
        d.phone?.let { add(CoreFact(CoreField.PHONE, it)) }
    }
}
