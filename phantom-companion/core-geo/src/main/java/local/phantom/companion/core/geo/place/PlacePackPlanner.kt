package local.phantom.companion.core.geo.place

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Скільки коштує іменоване місце і на скільки частин його доведеться різати.
 *
 * Жоден рушій офлайн-пакетів не тягне область одним шматком: кожен рівень
 * масштабу множить кількість тайлів на чотири, і вже на країні це виходить
 * за будь-який розумний бюджет одного пакета. Тому місце ріжеться на частини,
 * кожна з яких вкладається в бюджет і кожна з яких — повний діапазон z.
 * Це і дає чесний статус: «5 з 12 частин», а не «завантажено».
 */
object PlacePackPlanner {

    const val MIN_ZOOM = 10

    /**
     * Стеля не наша, а постачальника: `https://tiles.openfreemap.org/planet`
     * віддає TileJSON із `"maxzoom": 14` (перевірено 16.08.2026), і схема
     * OpenMapTiles у решти провайдерів у `resolveStyleUrls` така сама.
     * Замовляти z15–16 можна — приїде рівно нуль нових байтів, зате оцінка
     * розміру роздулася б у 16 разів. Тому пакет рівно до z14, а все глибше
     * телефон розтягує з z14.
     */
    const val MAX_ZOOM = 14

    /** Той самий бюджет, що й у `OfflineBudget.MAX_TILES_PER_PACK`. */
    const val TILES_PER_CHUNK = 60_000L

    /** Понад це — операторові пропонується менша глибина, а не 300 завантажень. */
    const val MAX_CHUNKS = 220

    /**
     * Середній розмір тайла на кожному рівні, байти.
     *
     * Виміряно 16.08.2026 на самому джерелі (`tiles.openfreemap.org/planet`),
     * по [SAMPLE_PER_ZOOM] випадкових тайлів на рівень із прямокутника України.
     * Одна константа «≈1,5 КБ на тайл», яку тут раніше вважали достатньою,
     * занижувала Україну в 2,7 раза: тайл z10 важить під 80 КБ, а z14 у полі —
     * близько 1,3 КБ, і в піраміді ці рівні змішані.
     *
     * Середнє, не медіана: рахується сума, і важкі тайли — її частина, а не викид.
     */
    private val BYTES_AT_ZOOM: Map<Int, Long> = mapOf(
        10 to 83_280L,
        11 to 30_508L,
        12 to 15_431L,
        13 to 6_358L,
        14 to 2_101L,
    )

    /**
     * Те саме, але для міст: рамка міста — суцільна забудова, і країнове
     * середнє занижувало Київ приблизно в тридцять разів. Вибірка —
     * [URBAN_SAMPLE_PER_ZOOM] тайлів на рівень усередині рамок восьми
     * найбільших міст таблиці. z14 тут важчий за z13: у центрі кожен поділ
     * додає будинки, а не прибирає їх.
     */
    private val BYTES_AT_ZOOM_URBAN: Map<Int, Long> = mapOf(
        10 to 114_343L,
        11 to 48_589L,
        12 to 24_076L,
        13 to 13_597L,
        14 to 23_526L,
    )

    const val SAMPLE_PER_ZOOM = 24
    const val URBAN_SAMPLE_PER_ZOOM = 32
    const val SAMPLED_ON = "16.08.2026"

    /** Поза виміряним діапазоном береться найближчий виміряний рівень. */
    fun bytesAtZoom(zoom: Int, kind: PlaceKind = PlaceKind.Oblast): Long {
        val table = if (kind == PlaceKind.City) BYTES_AT_ZOOM_URBAN else BYTES_AT_ZOOM
        return table[zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)] ?: table.getValue(MAX_ZOOM)
    }

    /**
     * Скільки пристрій уже качав насправді. Показується поруч з оцінкою як
     * друге, незалежне число — не підміняє її: середнє по пристрою змішує
     * рівні, і підставити його в оцінку означало б порахувати z10 за ціною z14.
     */
    data class DeviceAverage(
        val bytesPerResource: Long,
        val fromResources: Long,
        val fromBytes: Long,
    )

    /** Нижче цього вибірка ще нічого не доводить — краще не показувати числа. */
    const val MIN_SAMPLE_RESOURCES = 2_000L

    fun deviceAverage(completedResources: Long, completedBytes: Long): DeviceAverage? =
        if (completedResources >= MIN_SAMPLE_RESOURCES && completedBytes > 0L) {
            DeviceAverage(completedBytes / completedResources, completedResources, completedBytes)
        } else {
            null
        }

    data class Chunk(
        val index: Int,
        val northLat: Double,
        val eastLon: Double,
        val southLat: Double,
        val westLon: Double,
        val tiles: Long,
        val bytes: Long,
    )

    data class Plan(
        val place: NamedPlace,
        val minZoom: Int,
        val maxZoom: Int,
        val chunks: List<Chunk>,
        val tiles: Long,
        val bytes: Long,
    ) {
        val chunkCount: Int get() = chunks.size
    }

    /** Глибина, яку не можна запропонувати, і причина — щоб рядок не мовчав. */
    data class TooDeep(val maxZoom: Int, val chunksNeeded: Int)

    sealed interface Depth {
        val maxZoom: Int

        data class Available(val plan: Plan) : Depth {
            override val maxZoom: Int get() = plan.maxZoom
        }

        data class Refused(val reason: TooDeep) : Depth {
            override val maxZoom: Int get() = reason.maxZoom
        }
    }

    /** Глибини, які пропонуються операторові. Глибше [MAX_ZOOM] пропонувати нічого. */
    val DEPTHS: List<Int> = listOf(11, 12, 13, MAX_ZOOM)

    fun depths(
        place: NamedPlace,
        minZoom: Int = MIN_ZOOM,
    ): List<Depth> = DEPTHS.filter { it >= minZoom && it <= MAX_ZOOM }.map { z ->
        val p = plan(place, minZoom, z)
        if (p == null) {
            Depth.Refused(TooDeep(z, chunksNeeded(place, minZoom, z)))
        } else {
            Depth.Available(p)
        }
    }

    /** `null` — коли на цій глибині місце не ріжеться в [MAX_CHUNKS] частин. */
    fun plan(
        place: NamedPlace,
        minZoom: Int = MIN_ZOOM,
        maxZoom: Int = MAX_ZOOM,
        tilesPerChunk: Long = TILES_PER_CHUNK,
        maxChunks: Int = MAX_CHUNKS,
    ): Plan? {
        if (!place.valid || maxZoom < minZoom) return null
        // Прохання глибше стелі не відхиляється мовчки — план чесно каже, що
        // він до [MAX_ZOOM], і саме це число потрапляє і в оцінку, і в підпис.
        val deepest = maxZoom.coerceAtMost(MAX_ZOOM)
        val boxes = split(place, minZoom, deepest, tilesPerChunk, maxChunks) ?: return null
        val chunks = boxes.mapIndexed { i, b ->
            Chunk(
                index = i,
                northLat = b.n,
                eastLon = b.e,
                southLat = b.s,
                westLon = b.w,
                tiles = pyramidTiles(b.n, b.e, b.s, b.w, minZoom, deepest),
                bytes = (minZoom..deepest).sumOf {
                    tileCount(b.n, b.e, b.s, b.w, it) * bytesAtZoom(it, place.kind)
                },
            )
        }
        return Plan(
            place = place,
            minZoom = minZoom,
            maxZoom = deepest,
            chunks = chunks,
            tiles = chunks.sumOf { it.tiles },
            bytes = chunks.sumOf { it.bytes },
        )
    }

    /** Скільки частин було б потрібно — рахується без ліміту, щоб відмова назвала число. */
    fun chunksNeeded(place: NamedPlace, minZoom: Int, maxZoom: Int): Int =
        split(place, minZoom, maxZoom, TILES_PER_CHUNK, Int.MAX_VALUE)?.size ?: Int.MAX_VALUE

    /** Одна ланка коридора: та сама рамка, що йде в `MultiPolygon` MapLibre. */
    data class CorridorBox(
        val northLat: Double,
        val eastLon: Double,
        val southLat: Double,
        val westLon: Double,
    )

    /**
     * Скільки важить коридор маршруту — щоб число стояло на кнопці ДО тапу.
     *
     * Ланки коридора перекриваються (кожна бере наступну точку маршруту), тож
     * сума по ланках рахує спільні тайли двічі. Тут вони рахуються один раз:
     * ключі тайлів складаються в множину, і саме її розмір іде в байти. Це та
     * сама помилка, через яку підсумок панелі показував на 762 МБ більше, ніж
     * лежить на диску, — повторювати її на кнопці не можна.
     *
     * Таблиця країнова, не міська: коридор здебільшого поле й траса.
     */
    fun corridorTiles(boxes: List<CorridorBox>, minZoom: Int, maxZoom: Int): Map<Int, Long> {
        if (boxes.isEmpty() || maxZoom < minZoom) return emptyMap()
        val deepest = maxZoom.coerceAtMost(MAX_ZOOM)
        val out = LinkedHashMap<Int, Long>()
        for (z in minZoom..deepest) {
            val n = 1L shl z
            val seen = HashSet<Long>()
            for (b in boxes) {
                val x0 = lonToX(b.westLon, n)
                val x1 = lonToX(b.eastLon, n)
                val y0 = latToY(b.northLat, n)
                val y1 = latToY(b.southLat, n)
                for (x in minOf(x0, x1)..maxOf(x0, x1)) {
                    for (y in minOf(y0, y1)..maxOf(y0, y1)) {
                        seen.add(x * n + y)
                    }
                }
            }
            out[z] = seen.size.toLong()
        }
        return out
    }

    fun corridorBytes(boxes: List<CorridorBox>, minZoom: Int, maxZoom: Int): Long =
        corridorTiles(boxes, minZoom, maxZoom).entries
            .sumOf { (z, tiles) -> tiles * bytesAtZoom(z, PlaceKind.Oblast) }

    private data class Box(val n: Double, val e: Double, val s: Double, val w: Double)

    /**
     * Ділити навпіл по довшій стороні, поки кожна частина не влізе в бюджет.
     * Порядок обходу — «в ширину», щоб частини йшли зверху вниз і оператор
     * бачив прогрес там, де дивиться, а не в випадковому куті.
     */
    private fun split(
        place: NamedPlace,
        minZoom: Int,
        maxZoom: Int,
        budget: Long,
        maxChunks: Int,
    ): List<Box>? {
        var level = listOf(Box(place.northLat, place.eastLon, place.southLat, place.westLon))
        repeat(SPLIT_DEPTH_LIMIT) {
            val over = level.filter { pyramidTiles(it.n, it.e, it.s, it.w, minZoom, maxZoom) > budget }
            if (over.isEmpty()) return level.sortedWith(compareByDescending<Box> { it.n }.thenBy { it.w })
            if (level.size > maxChunks) return null
            level = level.flatMap { b ->
                if (pyramidTiles(b.n, b.e, b.s, b.w, minZoom, maxZoom) <= budget) listOf(b) else bisect(b)
            }
        }
        return null
    }

    private fun bisect(b: Box): List<Box> {
        val midLat = Math.toRadians((b.n + b.s) / 2.0)
        val hKm = (b.n - b.s) * 111.32
        val wKm = (b.e - b.w) * 111.32 * cos(midLat)
        return if (hKm >= wKm) {
            val m = (b.n + b.s) / 2.0
            listOf(Box(b.n, b.e, m, b.w), Box(m, b.e, b.s, b.w))
        } else {
            val m = (b.e + b.w) / 2.0
            listOf(Box(b.n, m, b.s, b.w), Box(b.n, b.e, b.s, m))
        }
    }

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
        return (abs(x1 - x0) + 1) * (abs(y1 - y0) + 1)
    }

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

    private fun lonToX(lon: Double, n: Long): Long =
        floor((lon + 180.0) / 360.0 * n).toLong().coerceIn(0, n - 1)

    private fun latToY(lat: Double, n: Long): Long {
        val r = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        val y = (1.0 - ln(tan(r) + 1.0 / cos(r)) / Math.PI) / 2.0 * n
        return floor(y).toLong().coerceIn(0, n - 1)
    }

    private const val SPLIT_DEPTH_LIMIT = 14
}
