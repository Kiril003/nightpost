package local.phantom.companion.core.net.peer

import kotlin.math.floor

/**
 * Дискретна сітка району, а не H3: H3 для JVM — це JNI з .so під кожну ABI, а
 * тут потрібні лише «клітинка точки» й «центр клітинки». Смуги широти фіксованої
 * висоти, у кожній — ціле число стовпців під косинус, тож клітинка майже квадрат
 * розміру [Level.sizeM] від екватора до полярного кола.
 *
 * StrictMath свідомо: відправник і приймач мусять отримати те саме число
 * стовпців у смузі, інакше центр клітинки на двох телефонах розійдеться.
 */
object PeerWhereGrid {

    enum class Level(val sizeM: Double) { DISTRICT(3_000.0), QUARTER(460.0) }

    data class Cell(val level: Level, val row: Int, val col: Int)

    enum class Nearness { SAME, NEAR, FAR }

    private const val M_PER_DEG = 111_320.0

    private fun dLat(level: Level): Double = level.sizeM / M_PER_DEG

    fun rows(level: Level): Int = kotlin.math.ceil(180.0 / dLat(level)).toInt()

    private fun rowSouth(level: Level, row: Int): Double = -90.0 + row * dLat(level)

    private fun rowCentreLat(level: Level, row: Int): Double =
        (rowSouth(level, row) + dLat(level) / 2).coerceIn(-89.999, 89.999)

    fun cols(level: Level, row: Int): Int {
        val widthM = 360.0 * StrictMath.cos(StrictMath.toRadians(rowCentreLat(level, row))) * M_PER_DEG
        return maxOf(1, floor(widthM / level.sizeM).toInt())
    }

    fun cellOf(lat: Double, lon: Double, level: Level): Cell {
        val row = floor((lat.coerceIn(-90.0, 90.0) + 90.0) / dLat(level)).toInt()
            .coerceIn(0, rows(level) - 1)
        val n = cols(level, row)
        val col = floor((normLon(lon) + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        return Cell(level, row, col)
    }

    fun isValid(cell: Cell): Boolean =
        cell.row in 0 until rows(cell.level) && cell.col in 0 until cols(cell.level, cell.row)

    data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double)

    fun boundsOf(cell: Cell): Bounds {
        val south = rowSouth(cell.level, cell.row).coerceAtLeast(-90.0)
        val n = cols(cell.level, cell.row)
        val west = -180.0 + cell.col * 360.0 / n
        return Bounds(south, west, (south + dLat(cell.level)).coerceAtMost(90.0), west + 360.0 / n)
    }

    fun centreOf(cell: Cell): Pair<Double, Double> {
        val n = cols(cell.level, cell.row)
        val lon = -180.0 + (cell.col + 0.5) * 360.0 / n
        return rowCentreLat(cell.level, cell.row) to lon
    }

    /**
     * Чи точка вийшла за межу клітинки ДАЛІ, ніж на [marginM].
     *
     * Без запасу людина, що ходить уздовж межі, перемикала б клітинку щоразу, і
     * мить перемикання казала б приймачеві, що вона стоїть рівно на лінії.
     */
    internal fun escaped(cell: Cell, lat: Double, lon: Double, marginM: Double): Boolean {
        val south = rowSouth(cell.level, cell.row)
        val north = south + dLat(cell.level)
        val n = cols(cell.level, cell.row)
        val west = -180.0 + cell.col * 360.0 / n
        val east = west + 360.0 / n
        val mLat = marginM / M_PER_DEG
        val cosLat = StrictMath.cos(StrictMath.toRadians(lat.coerceIn(-89.999, 89.999)))
        val mLon = marginM / (M_PER_DEG * maxOf(cosLat, 1e-6))
        if (lat < south - mLat || lat > north + mLat) return true
        val x = normLon(lon)
        val inside = if (west - mLon < -180.0 || east + mLon > 180.0) {
            val w = normLon(west - mLon)
            val e = normLon(east + mLon)
            if (w <= e) x in w..e else x >= w || x <= e
        } else {
            x in (west - mLon)..(east + mLon)
        }
        return !inside
    }

    /** Відповідь для скла — слово, а не метри: метри до людини дають трилатерацію. */
    fun nearness(a: Cell, b: Cell): Nearness {
        require(a.level == b.level) { "клітинки різних рівнів не порівнюються" }
        if (a == b) return Nearness.SAME
        val (la, oa) = centreOf(a)
        val (lb, ob) = centreOf(b)
        val dLatM = (lb - la) * M_PER_DEG
        var dLon = ob - oa
        if (dLon > 180.0) dLon -= 360.0
        if (dLon < -180.0) dLon += 360.0
        val dLonM = dLon * M_PER_DEG * StrictMath.cos(StrictMath.toRadians((la + lb) / 2))
        return if (StrictMath.hypot(dLatM, dLonM) <= 2.0 * a.level.sizeM) Nearness.NEAR else Nearness.FAR
    }

    private fun normLon(lon: Double): Double {
        var x = lon
        while (x >= 180.0) x -= 360.0
        while (x < -180.0) x += 360.0
        return x
    }
}
