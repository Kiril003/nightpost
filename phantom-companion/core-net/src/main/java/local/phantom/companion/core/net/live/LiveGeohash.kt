package local.phantom.companion.core.net.live

import kotlin.math.abs
import kotlin.math.max

/**
 * Геохеш — єдина форма, в якій район покидає пристрій.
 *
 * Запит «дай мітки тут» мусить якось описати «тут». Прямокутник у градусах
 * описав би вʼюпорт до метра; клітинка геохешу шести знаків — це ≈1.2×0.6 км
 * і нічого точнішого. Тому регіон завжди зводиться до клітинок, а не до кутів.
 */
object LiveGeohash {

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /** Довжина геохешу, якою підписана мітка в базі: клітинка ≈1.2×0.6 км. */
    const val REGION_PRECISION: Int = 6

    /**
     * Довжина префікса, яким ПИТАЮТЬ регіон: клітинка ≈4.9 км. Запит грубший
     * за саму мітку свідомо — журнал сервера має бачити «десь у цьому районі
     * міста», а не вʼюпорт оператора.
     */
    const val QUERY_PRECISION: Int = 5

    /** Клітинка, у яку падає точка. Порожній рядок — непридатний вхід. */
    fun encode(lat: Double, lon: Double, precision: Int = REGION_PRECISION): String {
        if (!lat.isFinite() || !lon.isFinite()) return ""
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return ""
        val p = precision.coerceIn(1, 12)
        var latMin = -90.0; var latMax = 90.0
        var lonMin = -180.0; var lonMax = 180.0
        val out = StringBuilder(p)
        var even = true
        var bit = 0
        var value = 0
        while (out.length < p) {
            if (even) {
                val mid = (lonMin + lonMax) / 2
                if (lon >= mid) { value = value * 2 + 1; lonMin = mid } else { value *= 2; lonMax = mid }
            } else {
                val mid = (latMin + latMax) / 2
                if (lat >= mid) { value = value * 2 + 1; latMin = mid } else { value *= 2; latMax = mid }
            }
            even = !even
            if (bit < 4) {
                bit++
            } else {
                out.append(BASE32[value])
                bit = 0
                value = 0
            }
        }
        return out.toString()
    }

    /**
     * Клітинки, що вкривають коло радіуса [radiusM] навколо точки.
     *
     * Крок сітки — РОЗМІР КЛІТИНКИ, а не радіус: інакше вибірка перестрибує
     * через клітинки між пробами й регіон приходить дірявим, що виглядає як
     * «тут нема міток». Сусідська арифметика геохешу (перенесення розрядів)
     * тут навмисно не використовується — це окремий клас багів на межах, а
     * зайва клітинка коштує кілька байтів запиту.
     */
    fun regionCells(
        lat: Double,
        lon: Double,
        radiusM: Double,
        precision: Int = QUERY_PRECISION,
        maxCells: Int = 12,
    ): List<String> {
        if (!lat.isFinite() || !lon.isFinite() || !radiusM.isFinite() || radiusM <= 0.0) return emptyList()
        val p = precision.coerceIn(1, 12)
        val latSpan = cellSpanLat(p)
        val lonSpan = cellSpanLon(p)
        val dLat = radiusM / 111_320.0
        val cosLat = kotlin.math.cos(Math.toRadians(lat))
        val dLon = if (abs(cosLat) < 1e-6) 180.0 else radiusM / (111_320.0 * abs(cosLat))
        val latSteps = stepsFor(dLat, latSpan)
        val lonSteps = stepsFor(dLon, lonSpan)
        val cells = LinkedHashSet<String>()
        for (i in -latSteps..latSteps) {
            for (j in -lonSteps..lonSteps) {
                val cell = encode(
                    (lat + i * latSpan).coerceIn(-90.0, 90.0),
                    wrapLon(lon + j * lonSpan),
                    p,
                )
                if (cell.isNotEmpty()) cells.add(cell)
                if (cells.size >= max(1, maxCells)) return cells.toList()
            }
        }
        return cells.toList()
    }

    /** Висота клітинки в градусах широти для довжини [precision]. */
    fun cellSpanLat(precision: Int): Double {
        val bits = 5 * precision.coerceIn(1, 12) / 2
        return 180.0 / (1L shl bits)
    }

    /** Ширина клітинки в градусах довготи для довжини [precision]. */
    fun cellSpanLon(precision: Int): Double {
        val bits = (5 * precision.coerceIn(1, 12) + 1) / 2
        return 360.0 / (1L shl bits)
    }

    private fun stepsFor(deltaDeg: Double, span: Double): Int =
        kotlin.math.ceil(deltaDeg / span).toInt().coerceIn(0, 3)

    private fun wrapLon(lon: Double): Double {
        var v = lon
        while (v > 180.0) v -= 360.0
        while (v < -180.0) v += 360.0
        return v
    }
}
