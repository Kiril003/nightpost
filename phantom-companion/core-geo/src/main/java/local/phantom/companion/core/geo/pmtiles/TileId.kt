package local.phantom.companion.core.geo.pmtiles

/** Координата тайлу в схемі XYZ (Slippy Map): зум і цілі x/y. */
data class TileCoord(val z: Int, val x: Int, val y: Int) {
    override fun toString(): String = "$z/$x/$y"
}

/**
 * Перетворення `z/x/y` ↔ ідентифікатор тайлу на **кривій Гільберта**.
 *
 * Чому саме Гільберт, а не рядковий порядок: крива зберігає просторову
 * близькість — сусідні на екрані тайли лежать поруч у файлі. Наслідок суто
 * практичний: один HTTP range-запит забирає цілий екран тайлів замість
 * девʼяти окремих, а на диску читання йде послідовно. Саме тому PMTiles
 * упорядковує тайли нею, і саме тому цю функцію не можна «спростити».
 *
 * Ідентифікатор глобальний по всій піраміді: спочатку йдуть усі тайли зумів
 * менших за z (їх рівно `(4^z − 1) / 3`), далі — позиція на кривій усередині z.
 * Тому один Long однозначно кодує `z/x/y`, і каталог не має зберігати зум окремо.
 */
object TileId {

    /**
     * Максимальний зум, який ще вміщується в Long без переповнення.
     * `(4^27 − 1)/3 + 4^26` ≈ 2.4e16 — далеко до 9.2e18, але вище 26 множення
     * `s * s` у циклі вже впирається в 2^52 і сенсу для карти не має.
     */
    const val MAX_ZOOM: Int = 26

    /** Ідентифікатор першого (нульового на кривій) тайлу зуму [z]. */
    fun firstIdAtZoom(z: Int): Long {
        require(z in 0..MAX_ZOOM) { "зум $z поза межами 0..$MAX_ZOOM" }
        // Сума геометричної прогресії 4^0 + … + 4^(z-1).
        return ((1L shl (2 * z)) - 1) / 3
    }

    /** Скільки тайлів на зумі [z] — `4^z`. */
    fun tileCountAtZoom(z: Int): Long {
        require(z in 0..MAX_ZOOM) { "зум $z поза межами 0..$MAX_ZOOM" }
        return 1L shl (2 * z)
    }

    /**
     * `z/x/y` → ідентифікатор тайлу (xy2d + зсув по зуму).
     *
     * @throws IllegalArgumentException якщо x або y виходять за `0..2^z − 1`.
     */
    fun fromZxy(z: Int, x: Int, y: Int): Long {
        require(z in 0..MAX_ZOOM) { "зум $z поза межами 0..$MAX_ZOOM" }
        val n = 1L shl z
        require(x >= 0 && y >= 0 && x < n && y < n) { "тайл $z/$x/$y поза межами зуму" }

        var xx = x.toLong()
        var yy = y.toLong()
        var d = 0L
        var s = n / 2
        while (s > 0) {
            val rx = if (xx and s > 0L) 1L else 0L
            val ry = if (yy and s > 0L) 1L else 0L
            d += s * s * ((3L * rx) xor ry)
            // Обертання квадранта. Аргумент — саме s (розмір поточного
            // квадранта), а не повний n: з n результат розходиться з
            // еталонною реалізацією PMTiles на зумах ≥ 2, і архів стає
            // нечитабельним для інших клієнтів.
            if (ry == 0L) {
                if (rx == 1L) {
                    xx = s - 1 - xx
                    yy = s - 1 - yy
                }
                val t = xx
                xx = yy
                yy = t
            }
            s /= 2
        }
        return firstIdAtZoom(z) + d
    }

    /** Зручна форма [fromZxy]. */
    fun fromCoord(c: TileCoord): Long = fromZxy(c.z, c.x, c.y)

    /**
     * Ідентифікатор тайлу → `z/x/y` (пошук зуму + d2xy).
     *
     * @throws IllegalArgumentException якщо [id] відʼємний або лежить вище [MAX_ZOOM].
     */
    fun toZxy(id: Long): TileCoord {
        require(id >= 0) { "ідентифікатор тайлу не може бути відʼємним: $id" }
        var acc = 0L
        var z = 0
        while (z <= MAX_ZOOM) {
            val count = 1L shl (2 * z)
            if (acc + count > id) break
            acc += count
            z++
        }
        require(z <= MAX_ZOOM) { "ідентифікатор $id вище максимального зуму $MAX_ZOOM" }

        var t = id - acc
        var x = 0L
        var y = 0L
        val n = 1L shl z
        var s = 1L
        while (s < n) {
            val rx = 1L and (t / 2)
            val ry = 1L and (t xor rx)
            if (ry == 0L) {
                if (rx == 1L) {
                    x = s - 1 - x
                    y = s - 1 - y
                }
                val tmp = x
                x = y
                y = tmp
            }
            x += s * rx
            y += s * ry
            t /= 4
            s *= 2
        }
        return TileCoord(z, x.toInt(), y.toInt())
    }
}
