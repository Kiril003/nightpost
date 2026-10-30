package local.phantom.companion.core.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import local.phantom.companion.core.data.spatial.GeoPoint

/**
 * Спільна геометрія для шарів карти: відстані, азимути, різниця курсів.
 *
 * Свідомо не гаверсинус за замовчуванням. Усі задачі цього модуля локальні —
 * станція, перехрестя, ділянка контролю швидкості — там локальна рівнокутна
 * проєкція дає похибку в міліметри, а коштує двох множень замість чотирьох
 * тригонометричних викликів. Для довгих відрізків є [greatCircleM].
 */
object GeoMath {

    const val EARTH_RADIUS_M: Double = 6_371_000.0

    private const val DEG_TO_RAD = PI / 180.0
    private const val RAD_TO_DEG = 180.0 / PI

    /** Відстань між точками, метри (локальна проєкція; для десятків кілометрів). */
    fun distanceM(lat1Deg: Double, lon1Deg: Double, lat2Deg: Double, lon2Deg: Double): Double {
        val latMidRad = (lat1Deg + lat2Deg) * 0.5 * DEG_TO_RAD
        val dNorthM = (lat2Deg - lat1Deg) * DEG_TO_RAD * EARTH_RADIUS_M
        val dEastM = (lon2Deg - lon1Deg) * DEG_TO_RAD * EARTH_RADIUS_M * cos(latMidRad)
        return hypot(dNorthM, dEastM)
    }

    /** Відстань по великому колу, метри — для відрізків у сотні кілометрів. */
    fun greatCircleM(lat1Deg: Double, lon1Deg: Double, lat2Deg: Double, lon2Deg: Double): Double {
        val p1 = lat1Deg * DEG_TO_RAD
        val p2 = lat2Deg * DEG_TO_RAD
        val dp = (lat2Deg - lat1Deg) * DEG_TO_RAD
        val dl = (lon2Deg - lon1Deg) * DEG_TO_RAD
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    /**
     * Довжина ламаної — сума відрізків, метри.
     *
     * По великому колу, а не локальною проєкцією: намальовану на дрібному
     * масштабі лінію тягнуть через півкраїни, а там локальна проєкція вже
     * помиляється на кілометри. Десяток зайвих тригонометричних викликів на
     * вершину нічого не коштує проти показаного числа, якому не можна вірити.
     */
    fun pathLengthM(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        var sum = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            sum += greatCircleM(a.lat, a.lon, b.lat, b.lon)
        }
        return sum
    }

    /** Азимут з першої точки на другу, градуси компаса `0..360`. */
    fun bearingDeg(lat1Deg: Double, lon1Deg: Double, lat2Deg: Double, lon2Deg: Double): Double {
        val p1 = lat1Deg * DEG_TO_RAD
        val p2 = lat2Deg * DEG_TO_RAD
        val dl = (lon2Deg - lon1Deg) * DEG_TO_RAD
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalizeDeg(atan2(y, x) * RAD_TO_DEG)
    }

    /** Звести кут до `0..360`. */
    fun normalizeDeg(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /**
     * Найменша різниця двох курсів, `0..180`.
     *
     * Окремою функцією, бо наївне `abs(a - b)` дає 350° замість 10° на переході
     * через північ — і саме там ламається перевірка «камера попереду чи позаду».
     */
    fun headingDeltaDeg(aDeg: Double, bDeg: Double): Double {
        val d = abs(normalizeDeg(aDeg) - normalizeDeg(bDeg))
        return if (d > 180.0) 360.0 - d else d
    }

    /** км/год → м/с. */
    fun kmhToMps(kmh: Double): Double = kmh / 3.6

    /** м/с → км/год. */
    fun mpsToKmh(mps: Double): Double = mps * 3.6
}
