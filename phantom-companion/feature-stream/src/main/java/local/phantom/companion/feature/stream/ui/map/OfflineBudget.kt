package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.geo.place.PlacePackPlanner
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * How deep a pack may go before it stops being worth its bytes.
 *
 * Every zoom level quadruples the tile count, so the same z16 that costs a few
 * megabytes over a city block costs gigabytes over an oblast. Nothing capped
 * it: the device carried a 1,9 GB tile store.
 */
object OfflineBudget {

    /** Tiles a single pack may claim. ~1.5 kB/tile vector ⇒ roughly 90 MB. */
    const val MAX_TILES_PER_PACK = 60_000

    /**
     * Стеля постачальника, а не наша: `tiles.openfreemap.org/planet` віддає
     * TileJSON із `maxzoom: 14`.
     *
     * Виміряно 17.08.2026 на телефоні власника: у сховищі 1,9 ГБ і 488 128
     * тайлів, найглибший — z14, при тому що 17 пакетів замовлено до z16.
     * Тобто z15–16 їхали в знаменник `requiredResourceCount` і не приїжджали
     * ніколи: відсоток занижений, час завантаження завищений. Стеля тут, у
     * єдиному місці, через яке проходять усі три шляхи створення пакета.
     */
    val PROVIDER_MAX_ZOOM: Int = PlacePackPlanner.MAX_ZOOM

    /**
     * Скільки місця лишити телефону, крім самого пакета.
     *
     * Телефон із нулем вільного — мертвий телефон, а мапа на мертвому телефоні
     * нічого не варта.
     */
    const val DISK_RESERVE_BYTES: Long = 500L * 1024L * 1024L

    /** Скільки ще бракує, байти. `0` — вміщується. */
    fun diskShortfallBytes(
        estimateBytes: Long,
        usableBytes: Long,
        reserveBytes: Long = DISK_RESERVE_BYTES,
    ): Long = (estimateBytes + reserveBytes - usableBytes).coerceAtLeast(0L)

    fun fitsDisk(
        estimateBytes: Long,
        usableBytes: Long,
        reserveBytes: Long = DISK_RESERVE_BYTES,
    ): Boolean = diskShortfallBytes(estimateBytes, usableBytes, reserveBytes) == 0L

    fun tileCount(
        northLat: Double,
        eastLon: Double,
        southLat: Double,
        westLon: Double,
        zoom: Int,
    ): Long {
        val n = 1L shl zoom
        val x0 = lonToX(westLon, n)
        val x1 = lonToX(eastLon, n)
        val y0 = latToY(northLat, n)
        val y1 = latToY(southLat, n)
        val w = abs(x1 - x0) + 1
        val h = abs(y1 - y0) + 1
        return w * h
    }

    /** Total across a min..max range — the pyramid, not just its base. */
    fun pyramidTiles(
        northLat: Double,
        eastLon: Double,
        southLat: Double,
        westLon: Double,
        minZoom: Int,
        maxZoom: Int,
    ): Long = (minZoom..maxZoom).sumOf {
        tileCount(northLat, eastLon, southLat, westLon, it)
    }

    /**
     * The deepest zoom this box can afford. Never below [minZoom] — a pack
     * shallower than its own floor is not a smaller pack, it is a broken one.
     * Never above [PROVIDER_MAX_ZOOM] either: deeper levels are empty requests.
     */
    fun cappedMaxZoom(
        northLat: Double,
        eastLon: Double,
        southLat: Double,
        westLon: Double,
        minZoom: Int,
        requestedMax: Int,
        budget: Long = MAX_TILES_PER_PACK.toLong(),
    ): Int {
        var z = requestedMax.coerceAtMost(PROVIDER_MAX_ZOOM).coerceAtLeast(minZoom)
        while (z > minZoom) {
            if (pyramidTiles(northLat, eastLon, southLat, westLon, minZoom, z) <= budget) return z
            z--
        }
        return minZoom
    }

    private fun lonToX(lon: Double, n: Long): Long =
        floor((lon + 180.0) / 360.0 * n).toLong().coerceIn(0, n - 1)

    private fun latToY(lat: Double, n: Long): Long {
        val r = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        val y = (1.0 - ln(tan(r) + 1.0 / cos(r)) / Math.PI) / 2.0 * n
        return floor(y).toLong().coerceIn(0, n - 1)
    }
}
