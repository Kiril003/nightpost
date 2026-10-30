package local.phantom.companion.core.data.geocoder

import local.phantom.companion.core.data.poi.PoiHit

/**
 * Влучання пакета кузні стає звичайним рядком видачі.
 *
 * `subcategory` навмисно бере ЗНАЧЕННЯ тега (`amenity=pharmacy` → `pharmacy`),
 * а не власну категорію пакета: саме значеннями тегів говорить [PoiVocabulary],
 * і лише так рядок отримує слово «аптека» без другого словника поруч.
 */
fun ForgeHit.toPoiHit(tags: Map<String, String>): PoiHit = PoiHit(
    id = stableId(),
    lat = lat,
    lon = lon,
    name = name,
    category = cat ?: kind,
    subcategory = sub?.substringAfter('=')?.takeIf { it.isNotBlank() } ?: cat,
    distanceMeters = distanceMeters ?: 0.0,
    tags = tags,
    matchedName = true,
)

/** Вулиця, зібрана з адрес, має osm_id = 0 — таких у пакеті десятки тисяч. */
private fun ForgeHit.stableId(): Long =
    if (osmId != 0L) osmId else "$kind:$name:$lat:$lon".hashCode().toLong()
