package local.phantom.companion.core.sensor.nav

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

/**
 * ПРОВИДЕЦЬ — pure corridor math: which permanent-mesh cells and which
 * tile boxes cover a route, decided BEFORE any network is touched.
 * JVM-tested; the orchestration lives in feature-stream.
 */
object CorridorPlanner {

    data class Box(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

    /** [cells] are ordered along the route, origin first. */
    class CellPlan(val cells: LongArray, val truncated: Boolean)

    fun lengthKm(polyline: List<Pair<Double, Double>>): Double {
        var m = 0.0
        for (i in 1 until polyline.size) {
            m += haversineM(
                polyline[i - 1].first, polyline[i - 1].second,
                polyline[i].first, polyline[i].second,
            )
        }
        return m / 1000.0
    }

    fun sample(polyline: List<Pair<Double, Double>>, stepM: Double = SAMPLE_STEP_M): List<Pair<Double, Double>> {
        if (polyline.size < 2) return polyline
        val out = ArrayList<Pair<Double, Double>>()
        out.add(polyline.first())
        for (i in 1 until polyline.size) {
            val (aLat, aLon) = polyline[i - 1]
            val (bLat, bLon) = polyline[i]
            val d = haversineM(aLat, aLon, bLat, bLon)
            val steps = max(1, ceil(d / stepM).toInt())
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                out.add(aLat + (bLat - aLat) * t to aLon + (bLon - aLon) * t)
            }
        }
        return out
    }

    /**
     * Union of [RoadMeshStore]-aligned cells within [radiusM] of the route.
     * A corridor longer than [maxCells] is cut, origin-first, and says so —
     * never silently.
     */
    fun corridorCells(
        polyline: List<Pair<Double, Double>>,
        radiusM: Double = CELL_RADIUS_M,
        maxCells: Int = MAX_CELLS,
    ): CellPlan {
        val seen = LinkedHashSet<Long>()
        var truncated = false
        outer@ for ((lat, lon) in sample(polyline)) {
            val dLat = radiusM / M_PER_DEG_LAT
            val dLon = radiusM / (M_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
            for (cell in RoadMeshStore.cellsCovering(lat - dLat, lon - dLon, lat + dLat, lon + dLon)) {
                if (seen.size >= maxCells && cell !in seen) {
                    truncated = true
                    break@outer
                }
                seen.add(cell)
            }
        }
        return CellPlan(seen.toLongArray(), truncated)
    }

    /** Inverse of [RoadMeshStore.cellsCovering]'s key packing. */
    fun cellBounds(cell: Long): Box {
        val la = (cell shr 32).toDouble()
        val lo = cell.toInt().toDouble()
        val d = RoadMeshStore.CELL_DEG
        return Box(la * d, lo * d, (la + 1) * d, (lo + 1) * d)
    }

    /** Route chained into ≤ [maxBoxes] padded bboxes for tile-pyramid packs. */
    fun tileBoxes(
        polyline: List<Pair<Double, Double>>,
        padM: Double = TILE_PAD_M,
        maxBoxes: Int = MAX_TILE_BOXES,
    ): List<Box> {
        val pts = sample(polyline, stepM = 2_000.0)
        if (pts.isEmpty()) return emptyList()
        val perBox = max(1, ceil(pts.size.toDouble() / maxBoxes).toInt())
        val out = ArrayList<Box>()
        var i = 0
        while (i < pts.size) {
            val group = pts.subList(i, minOf(i + perBox + 1, pts.size))
            var minLat = group[0].first; var maxLat = minLat
            var minLon = group[0].second; var maxLon = minLon
            for ((la, lo) in group) {
                if (la < minLat) minLat = la; if (la > maxLat) maxLat = la
                if (lo < minLon) minLon = lo; if (lo > maxLon) maxLon = lo
            }
            val dLat = padM / M_PER_DEG_LAT
            val midLat = (minLat + maxLat) / 2
            val dLon = padM / (M_PER_DEG_LAT * cos(Math.toRadians(midLat)).coerceAtLeast(0.2))
            out.add(Box(minLat - dLat, minLon - dLon, maxLat + dLat, maxLon + dLon))
            i += perBox
        }
        return out
    }

    /** Honest slippy-map estimate of tiles a pyramid download will pull. */
    fun estimateTiles(boxes: List<Box>, minZoom: Int, maxZoom: Int): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val n = 1 shl z
            for (b in boxes) {
                val x1 = lonToTileX(b.minLon, n)
                val x2 = lonToTileX(b.maxLon, n)
                val y1 = latToTileY(b.maxLat, n)
                val y2 = latToTileY(b.minLat, n)
                total += (abs(x2 - x1) + 1).toLong() * (abs(y2 - y1) + 1).toLong()
            }
        }
        return total
    }

    private fun lonToTileX(lon: Double, n: Int): Int =
        floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)

    private fun latToTileY(lat: Double, n: Int): Int {
        val rad = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n
        return floor(y).toInt().coerceIn(0, n - 1)
    }

    const val SAMPLE_STEP_M = 500.0
    const val CELL_RADIUS_M = 700.0
    const val MAX_CELLS = 600
    const val TILE_PAD_M = 2_500.0
    const val MAX_TILE_BOXES = 12
    const val M_PER_DEG_LAT = 111_320.0
}
