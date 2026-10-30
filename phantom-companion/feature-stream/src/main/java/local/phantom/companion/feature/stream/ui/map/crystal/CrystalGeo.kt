package local.phantom.companion.feature.stream.ui.map.crystal

import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Crystal City C1 — shared geo primitives for the building data layer.
 *
 * Buildings are fetched and cached per slippy-map tile at a fixed zoom
 * ([CRYSTAL_TILE_ZOOM]): fine enough that one tile is a comfortable
 * Overpass request (~a city block cluster), coarse enough that a
 * viewport at the tactical pitch threshold covers only a handful.
 */

/** WGS84 point. Pure Kotlin (no Android/MapLibre types) so parser unit tests run on the JVM. */
data class CityPoint(val lat: Double, val lon: Double)

/** Slippy tile key at [CRYSTAL_TILE_ZOOM]. */
data class CrystalTileKey(val x: Int, val y: Int) {
    /** Stable cache-file stem, e.g. `15_19151_11071`. */
    val fileStem: String get() = "${CRYSTAL_TILE_ZOOM}_${x}_$y"
}

/** Tile bbox in Overpass order: (south, west, north, east). */
data class CrystalTileBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)

const val CRYSTAL_TILE_ZOOM = 15

object CrystalGeo {

    private const val N = 1 shl CRYSTAL_TILE_ZOOM

    fun tileFor(lat: Double, lon: Double): CrystalTileKey {
        val x = floor((lon + 180.0) / 360.0 * N).toInt().coerceIn(0, N - 1)
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * N)
            .toInt().coerceIn(0, N - 1)
        return CrystalTileKey(x, y)
    }

    fun boundsOf(tile: CrystalTileKey): CrystalTileBounds {
        val west = tile.x.toDouble() / N * 360.0 - 180.0
        val east = (tile.x + 1).toDouble() / N * 360.0 - 180.0
        val north = tileEdgeLat(tile.y)
        val south = tileEdgeLat(tile.y + 1)
        return CrystalTileBounds(south = south, west = west, north = north, east = east)
    }

    /**
     * All tiles covering a viewport bbox, nearest-to-centre first so the
     * fetch budget (see [CrystalCityFeed]) spends itself where the
     * operator is actually looking.
     */
    fun tilesCovering(
        north: Double,
        east: Double,
        south: Double,
        west: Double,
    ): List<CrystalTileKey> {
        val topLeft = tileFor(north, west)
        val bottomRight = tileFor(south, east)
        val cx = (topLeft.x + bottomRight.x) / 2.0
        val cy = (topLeft.y + bottomRight.y) / 2.0
        val tiles = mutableListOf<CrystalTileKey>()
        for (x in topLeft.x..bottomRight.x) {
            for (y in topLeft.y..bottomRight.y) {
                tiles += CrystalTileKey(x, y)
            }
        }
        return tiles.sortedBy { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) }
    }

    private fun tileEdgeLat(y: Int): Double {
        val n = Math.PI - 2.0 * Math.PI * y / N
        return Math.toDegrees(atan(sinh(n)))
    }
}
