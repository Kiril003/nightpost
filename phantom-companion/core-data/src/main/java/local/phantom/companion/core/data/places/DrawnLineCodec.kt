package local.phantom.companion.core.data.places

import local.phantom.companion.core.data.spatial.GeoPoint

/**
 * Вершини ↔ рядок: `"50.45,30.52;50.46,30.53"`.
 *
 * Кодуємо через `Double.toString()`, а не `String.format`: формат бере локаль
 * пристрою, а українська локаль пише кому замість крапки — і тоді широта
 * «50,45» розпадається при читанні на два числа. `toString()` локалі не має.
 */
object DrawnLineCodec {

    private const val POINT_SEPARATOR = ";"
    private const val COORD_SEPARATOR = ","

    fun encode(points: List<GeoPoint>): String =
        points.joinToString(POINT_SEPARATOR) { it.lat.toString() + COORD_SEPARATOR + it.lon.toString() }

    /** `null` — рядок пошкоджений; порожній список — цілком інша річ, ніж збій. */
    fun decode(raw: String): List<GeoPoint>? {
        if (raw.isBlank()) return emptyList()
        return raw.split(POINT_SEPARATOR).map { chunk ->
            val coords = chunk.split(COORD_SEPARATOR)
            if (coords.size != 2) return null
            val lat = coords[0].trim().toDoubleOrNull() ?: return null
            val lon = coords[1].trim().toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            GeoPoint(lat, lon)
        }
    }
}
