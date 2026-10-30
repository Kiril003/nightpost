package local.phantom.companion.core.net.backpack

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * «Рюкзак перед підвалом» (розд. 07 §7): коли в моїй області починається
 * тривога, у телефона ще є секунди доброго звʼязку. За ≤ 2 МБ і ~20 с він
 * бере те, без чого внизу гірше: свіжий підписаний знімок тривог, усе, що
 * лежить для нього в сховку (відповіді ПК, листи, «де я» людей), і мапу
 * навколо себе. Вночі на Wi-Fi і зарядці — повний пакет.
 *
 * Тут лише чиста частина: план тайлів під бюджет і стан для показу.
 * Скільки байтів справді лягло — каже завантажувач мапи, не цей план.
 */
object Backpack {

    enum class Trigger { ALERT, NIGHT, MANUAL }

    /** Після тривоги: усе разом не більше 2 МБ і ~20 с. */
    const val QUICK_BYTES = 2_000_000L
    const val QUICK_MS = 20_000L
    /** Маяк (≈ 200 Б) і забір сховку — з запасом. */
    const val RESERVED_BYTES = 50_000L
    /** Вночі на Wi-Fi і зарядці. */
    const val NIGHT_BYTES = 150_000_000L

    /**
     * Оцінка одного векторного тайла разом зі стилем і шрифтами, що
     * докачуються поруч. Навмисно з запасом: перебрати бюджет гірше, ніж
     * недобрати. Справжнє число повідомляє MapLibre після завантаження.
     */
    const val TILE_BYTES = 30_000L

    @Serializable
    data class TilePlan(
        val north: Double,
        val east: Double,
        val south: Double,
        val west: Double,
        val minZoom: Int,
        val maxZoom: Int,
        val radiusM: Int,
        val tiles: Long,
        val estimateBytes: Long,
    ) {
        fun contains(lat: Double, lon: Double): Boolean = lat in south..north && lon in west..east
    }

    private data class Shape(val radiusM: Int, val minZoom: Int, val maxZoom: Int)

    /** Від найкращого до найскромнішого — перший, що влазить у бюджет. */
    private val QUICK_SHAPES = listOf(
        Shape(3_000, 12, 16), Shape(2_000, 12, 16), Shape(3_000, 12, 15),
        Shape(1_500, 12, 16), Shape(2_000, 12, 15), Shape(1_000, 12, 16),
        Shape(1_500, 12, 15), Shape(1_000, 12, 15), Shape(800, 12, 15), Shape(500, 12, 15),
    )
    private val NIGHT_SHAPES = listOf(
        Shape(15_000, 8, 16), Shape(10_000, 8, 16), Shape(15_000, 8, 15), Shape(8_000, 8, 16), Shape(10_000, 8, 15),
    )

    fun budgetOf(trigger: Trigger): Long = when (trigger) {
        Trigger.ALERT, Trigger.MANUAL -> QUICK_BYTES - RESERVED_BYTES
        Trigger.NIGHT -> NIGHT_BYTES
    }

    /** План тайлів навколо [lat]/[lon] під бюджет [trigger], або null — не влазить навіть найскромніший. */
    fun plan(lat: Double, lon: Double, trigger: Trigger): TilePlan? {
        if (lat !in -85.0..85.0 || lon !in -180.0..180.0) return null
        val budget = budgetOf(trigger)
        val shapes = if (trigger == Trigger.NIGHT) NIGHT_SHAPES else QUICK_SHAPES
        for (s in shapes) {
            val dLat = s.radiusM / 111_320.0
            val dLon = s.radiusM / (111_320.0 * cos(lat * PI / 180).coerceAtLeast(0.01))
            val n = lat + dLat
            val sth = lat - dLat
            val e = lon + dLon
            val w = lon - dLon
            val tiles = (s.minZoom..s.maxZoom).sumOf { tileCount(n, e, sth, w, it) }
            val bytes = tiles * TILE_BYTES
            if (bytes <= budget) return TilePlan(n, e, sth, w, s.minZoom, s.maxZoom, s.radiusM, tiles, bytes)
        }
        return null
    }

    /** Скільки тайлів XYZ покриває прямокутник на рівні [z]. */
    fun tileCount(north: Double, east: Double, south: Double, west: Double, z: Int): Long {
        val x0 = tileX(west, z)
        val x1 = tileX(east, z)
        val y0 = tileY(north, z)
        val y1 = tileY(south, z)
        return (x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong()
    }

    private fun tileX(lon: Double, z: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    private fun tileY(lat: Double, z: Int): Int {
        val r = lat * PI / 180
        val y = (1.0 - ln(tan(r) + 1 / cos(r)) / PI) / 2.0 * (1 shl z)
        return floor(y).toInt().coerceIn(0, (1 shl z) - 1)
    }

    /** Що сталося з однією річчю в рюкзаку — словами, які веб покаже як є. */
    @Serializable
    data class Item(
        /** ok · failed · skipped · running */
        val state: String,
        val detail: String = "",
        val bytes: Long = 0,
    )

    @Serializable
    data class State(
        val trigger: String = "",
        val startedAtMs: Long = 0,
        val finishedAtMs: Long = 0,
        val siren: Item? = null,
        val relay: Item? = null,
        val tiles: Item? = null,
        val plan: TilePlan? = null,
        /** Імʼя пакета мапи, щоб читати справжній прогрес у завантажувача. */
        val regionName: String = "",
        val lastNightMs: Long = 0,
    )
}
