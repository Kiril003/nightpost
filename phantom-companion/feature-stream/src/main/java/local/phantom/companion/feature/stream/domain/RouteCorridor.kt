package local.phantom.companion.feature.stream.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Коридор маршруту — ОДНА збережена ділянка замість купи дрібних.
 *
 * Власник, 16.08: «замість купи дрібних про коридори — незнаю як ти думаєш
 * користувачі це видаляти будуть — став 1 коридор на маршрут, який вміщує в
 * себе все незавантажене і околиці маршруту для перепрокладання і того, куди по
 * дорозі заїхати можна».
 *
 * Прямокутник навколо всього маршруту для цього не годиться: Київ — Львів це
 * 540 × 200 км, з яких по дорозі лежить смуга завширшки з десяток. Такий пакет
 * не поміститься в телефон і на девʼять десятих складатиметься з землі, якою
 * ніхто не поїде.
 *
 * Тому коридор — не одна рамка, а ланцюг рамок уздовж лінії, кожна з запасом
 * [DEFAULT_MARGIN_KM] на сторону. Запас не декоративний: він і є «околиці» —
 * ним перепрокладають обʼїзд, коли дорогу закрито, і ним заїжджають на заправку
 * за кілометр від траси. MapLibre приймає геометрію, а не лише рамку, тож
 * ланцюг іде одним `MultiPolygon` і лишається ОДНИМ пакетом із одним імʼям і
 * одним «видалити».
 */
object RouteCorridor {

    /** Скільки землі обабіч дороги входить у коридор, на кожну сторону. */
    const val DEFAULT_MARGIN_KM = 5.0

    /**
     * Стеля на кількість ланок. Кожна ланка — окремий прямокутник у покритті;
     * тисяча ланок на довгому маршруті коштувала б більше, ніж economy від
     * точнішої форми.
     */
    const val MAX_PARTS = 48

    private const val KM_PER_DEG_LAT = 111.32

    data class Box(
        val northLat: Double,
        val eastLon: Double,
        val southLat: Double,
        val westLon: Double,
    ) {
        val areaKm2: Double
            get() {
                val midLat = Math.toRadians((northLat + southLat) / 2.0)
                return (northLat - southLat) * KM_PER_DEG_LAT *
                    (eastLon - westLon) * KM_PER_DEG_LAT * cos(midLat)
            }
    }

    /**
     * Ланцюг рамок уздовж маршруту. Порожньо, коли точок менше за дві — коридору
     * навколо однієї точки не буває, і вигадувати його нема сенсу.
     */
    fun boxes(
        points: List<Pair<Double, Double>>,
        marginKm: Double = DEFAULT_MARGIN_KM,
        maxParts: Int = MAX_PARTS,
    ): List<Box> {
        if (points.size < 2) return emptyList()
        val perPart = max(2, (points.size + maxParts - 1) / maxParts)
        val out = ArrayList<Box>()
        var i = 0
        while (i < points.size - 1) {
            val end = min(points.size, i + perPart + 1)
            val slice = points.subList(i, end)
            out += boxAround(slice, marginKm)
            i = end - 1
        }
        return out
    }

    /** Скільки землі коридор насправді накриває, км². Сума ланок без відрахунку
     *  перекриттів — тобто оцінка ЗВЕРХУ, і саме таку показують перед тапом. */
    fun areaKm2(boxes: List<Box>): Double = boxes.sumOf { abs(it.areaKm2) }

    /** GeoJSON `MultiPolygon` для offline-визначення MapLibre. */
    fun multiPolygonJson(boxes: List<Box>): String {
        val parts = boxes.joinToString(",") { b ->
            "[[" + listOf(
                "[${b.westLon},${b.southLat}]",
                "[${b.eastLon},${b.southLat}]",
                "[${b.eastLon},${b.northLat}]",
                "[${b.westLon},${b.northLat}]",
                "[${b.westLon},${b.southLat}]",
            ).joinToString(",") + "]]"
        }
        return """{"type":"MultiPolygon","coordinates":[$parts]}"""
    }

    private fun boxAround(slice: List<Pair<Double, Double>>, marginKm: Double): Box {
        var north = -90.0
        var south = 90.0
        var east = -180.0
        var west = 180.0
        for ((lat, lon) in slice) {
            north = max(north, lat); south = min(south, lat)
            east = max(east, lon); west = min(west, lon)
        }
        val dLat = marginKm / KM_PER_DEG_LAT
        val midLat = Math.toRadians((north + south) / 2.0)
        // Довгота стискається до полюсів: той самий кілометр коштує тим більше
        // градусів, чим північніше. Без косинуса коридор на півночі був би
        // вужчим за замовлений саме там, де обʼїзд знайти найважче.
        val dLon = marginKm / (KM_PER_DEG_LAT * max(0.1, cos(midLat)))
        return Box(
            northLat = (north + dLat).coerceAtMost(90.0),
            southLat = (south - dLat).coerceAtLeast(-90.0),
            eastLon = (east + dLon).coerceAtMost(180.0),
            westLon = (west - dLon).coerceAtLeast(-180.0),
        )
    }
}
