package local.phantom.companion.core.geo.pmtiles

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Прямокутник тайлів одного зуму: обидві межі включно. */
data class TileRect(val z: Int, val x0: Int, val x1: Int, val y0: Int, val y1: Int) {
    val count: Int get() = (x1 - x0 + 1) * (y1 - y0 + 1)
}

/**
 * Піраміда архіву — чисто з заголовка.
 *
 * PMTiles не вміє перелічити свої тайли дешево, тож обхід іде так само, як
 * кузня їх пекла: рамка архіву × зум. Відсутній тайл усередині рамки —
 * звичайна річ (море), а не вада.
 */
object PmTilesPyramid {

    fun tileRect(header: PmTilesHeader, z: Int): TileRect {
        val (x0, y0) = tileOf(header.minLonDeg, header.maxLatDeg, z)
        val (x1, y1) = tileOf(header.maxLonDeg, header.minLatDeg, z)
        val edge = (1 shl z) - 1
        return TileRect(
            z = z,
            x0 = x0.coerceIn(0, edge),
            x1 = x1.coerceIn(0, edge),
            y0 = y0.coerceIn(0, edge),
            y1 = y1.coerceIn(0, edge),
        )
    }

    /** MBTiles рахує рядки знизу (TMS), PMTiles і MapLibre — згори. */
    fun tmsRow(z: Int, y: Int): Int = (1 shl z) - 1 - y

    fun tileOf(lonDeg: Double, latDeg: Double, z: Int): Pair<Int, Int> {
        val n = (1 shl z).toDouble()
        val x = floor((lonDeg + 180.0) / 360.0 * n).toInt()
        val lat = latDeg * PI / 180.0
        val y = floor((1.0 - ln(tan(lat) + 1.0 / kotlin.math.cos(lat)) / PI) / 2.0 * n).toInt()
        return x to y
    }

    /**
     * Метадані MBTiles з заголовка архіву. `format` беремо з типу тайла —
     * саме за ним MapLibre вирішує, растр перед ним чи вектор, і саме тут
     * рельєф відрізняється від основи.
     */
    fun mbTilesMetadata(header: PmTilesHeader, name: String, attribution: String?): Map<String, String> {
        val format = when (header.tileType) {
            PmTilesTileType.MVT -> "pbf"
            PmTilesTileType.PNG -> "png"
            PmTilesTileType.JPEG -> "jpg"
            PmTilesTileType.WEBP -> "webp"
            else -> "png"
        }
        val bounds = listOf(header.minLonDeg, header.minLatDeg, header.maxLonDeg, header.maxLatDeg)
            .joinToString(",") { trim(it) }
        return buildMap {
            put("name", name)
            put("format", format)
            put("type", if (format == "pbf") "overlay" else "baselayer")
            put("minzoom", header.minZoom.toString())
            put("maxzoom", header.maxZoom.toString())
            put("bounds", bounds)
            put("center", "${trim(header.centerLonDeg)},${trim(header.centerLatDeg)},${header.centerZoom}")
            if (!attribution.isNullOrBlank()) put("attribution", attribution)
        }
    }

    private fun trim(value: Double): String {
        val rounded = Math.round(value * 1e6) / 1e6
        return if (abs(rounded - rounded.toLong()) < 1e-9) rounded.toLong().toString() else rounded.toString()
    }
}
