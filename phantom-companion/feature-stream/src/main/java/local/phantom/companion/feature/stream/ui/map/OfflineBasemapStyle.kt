package local.phantom.companion.feature.stream.ui.map

/**
 * ОФЛАЙН-ОСНОВА — остання миля з `styles/OFFLINE.md`: коли файл тайлів
 * внесено, `sources.openmaptiles` має тягнути ЙОГО, а не мережевий TileJSON.
 *
 * Рядком, не `org.json`: у юніт-тесті `org.json` — заглушка з `android.jar`
 * (та сама причина, з якої `POIOverlay.featureCollection` лишається
 * неперевіреним і перевіряють лише `labelPlan`, див. `PoiLabelTwinTest`).
 * Стиль тут — один плаский обʼєкт `sources.openmaptiles` без вкладених
 * `{}`, тож рядкова заміна є точною, а не здогадом.
 */
private val OPENMAPTILES_SOURCE = Regex(""""openmaptiles"\s*:\s*\{[^{}]*\}""")
private val URL_FIELD = Regex(""""url"\s*:\s*"[^"]*"""")

/**
 * Підміняє мережевий `url` джерела `openmaptiles` на локальний mbtiles-файл.
 *
 * Три коси навмисно: `mbtiles://` (схема) + перша `/` самого [absolutePath] —
 * бібліотека відмовляє в будь-якій іншій формі («MBTilesFileSource only
 * supports absolute path urls», виміряно в `styles/OFFLINE.md`).
 *
 * Стиль без джерела `openmaptiles` (чужий постачальник) повертається
 * незмінним — підміна стосується лише нашого власного Обсидіана.
 */
internal fun applyOfflineBasemap(styleJson: String, absolutePath: String): String {
    require(absolutePath.startsWith("/")) {
        "absolute tile file path required, got: $absolutePath"
    }
    return OPENMAPTILES_SOURCE.replace(styleJson) { match ->
        URL_FIELD.replace(match.value, """"url": "mbtiles://$absolutePath"""")
    }
}

/** Імʼя джерела рельєфу — одне на стиль і на [installHillshade]. */
internal const val LOCAL_DEM_SOURCE = "phantom-dem-source"

private val SOURCES_OPEN = Regex(""""sources"\s*:\s*\{""")

/**
 * Вносить рельєф кузні в стиль ТИМ САМИМ способом, яким уже вноситься основа.
 *
 * Джерело оголошується в JSON, а не кодом, через `encoding`: рушій має
 * прочитати висоту як terrarium, а [org.maplibre.android.style.sources.RasterDemSource]
 * з локальним файлом не дає цього сказати — TileSet несе шаблони тайлів, а
 * не `url`, і саме `url` розбирає MBTilesFileSource.
 *
 * Стиль без блока `sources` повертається незмінним, а повторний виклик нічого
 * не додає: джерело з таким імʼям уже там.
 */
internal fun applyLocalTerrain(styleJson: String, absolutePath: String, maxZoom: Int): String {
    require(absolutePath.startsWith("/")) {
        "absolute terrain file path required, got: $absolutePath"
    }
    if (styleJson.contains(""""$LOCAL_DEM_SOURCE"""")) return styleJson
    val source = """"$LOCAL_DEM_SOURCE": {"type": "raster-dem", "encoding": "terrarium", """ +
        """"tileSize": 256, "minzoom": 0, "maxzoom": $maxZoom, "url": "mbtiles://$absolutePath"},"""
    val opening = SOURCES_OPEN.find(styleJson) ?: return styleJson
    return styleJson.substring(0, opening.range.last + 1) + source +
        styleJson.substring(opening.range.last + 1)
}

// A city pack is a rectangle: drawn from it while online, every other city stayed grey.
internal fun localBasemapFor(
    pack: local.phantom.companion.core.geo.basemap.OfflineBasemapInfo?,
    online: Boolean,
): local.phantom.companion.core.geo.basemap.OfflineBasemapInfo? = if (online) null else pack
