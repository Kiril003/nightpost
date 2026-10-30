package local.phantom.companion.core.data.poi

import java.io.File
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стеля кадру — на СПРАВЖНЬОМУ корпусі країни, а не на вигаданих десяти рядках.
 *
 * ЧОМУ ЦЕ ОКРЕМИЙ ФАЙЛ. `PoiDensityTest` перевіряє закон на зразках, які пише
 * сам тест, і цього досить, щоб описати правило. Але хвиля почалася не з
 * правила, а з РОЗПОДІЛУ: у корпусі 176 558 рядків роди розкладені важким
 * хвостом, і саме хвіст ламав «відсортуй і візьми перші N». Вигаданий зразок
 * такого хвоста не має і ніколи його не відтворить — тому перевірка читає той
 * самий байт у байт `places.tsv`, що поїде в APK (робочий каталог модульного
 * тесту — тека модуля, прийом уже вжитий в `OfflinePlacePackTest`).
 *
 * ЩО ТУТ МІРЯЄТЬСЯ. Кадр телефона на трьох зумах, у трьох найщільніших
 * комірках країни. Розмір кадру виведено з геометрії A52 (411×914 dp) і
 * розміру плитки MapLibre — не вгадано:
 *   ширина в градусах = 360 · 411 / (512 · 2^z).
 *
 * ЧИСЛА ДО ЗМІНИ, заміряні 17.09 на цьому ж корпусі (300 найщільніших кадрів):
 *   z13, стеля 25: на склі 5 різних родів, найбільша купа одного роду 12,
 *                  вода дійшла в 0 кадрах із 275, де вона була;
 *   z14, стеля 40: 7 родів, купа 17, вода в 0 кадрах із 235.
 * Після: z13 — 25 родів, купа 1, 275 із 275; z14 — 39 родів, купа 2, 235 із 235.
 */
class PlaceFrameDensityTest {

    private data class Row(val e: PoiEntity, val lat: Double, val lon: Double)

    /** Геометрія кадру A52 в градусах — те саме числення, що й у камери мапи. */
    private fun frameSpan(zoom: Double, lat: Double): Pair<Double, Double> {
        val world = 512.0 * Math.pow(2.0, zoom)
        val lonSpan = 360.0 * 411.0 / world
        val latSpan = 360.0 * 914.0 / world * cos(Math.toRadians(lat))
        return latSpan to lonSpan
    }

    private fun corpus(): List<PoiEntity> {
        val f = File("src/main/assets/country/ua/places.tsv")
        assertTrue("довідник місць мусить лежати за ${f.absolutePath}", f.exists())
        val out = ArrayList<PoiEntity>(180_000)
        f.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLine()
            var idx = 0L
            while (true) {
                val line = reader.readLine() ?: break
                val row = OfflinePlacePack.parseLine(line, idx++) ?: continue
                out += PoiEntity(
                    id = row.id, lat = row.lat, lon = row.lon, name = row.name,
                    category = row.category, subcategory = row.subcategory, tags = row.tags,
                )
            }
        }
        assertTrue("корпус підозріло малий: ${out.size}", out.size > 100_000)
        return out
    }

    /** Три найщільніші комірки 0.01° — справжні центри великих міст. */
    private fun densestCentres(all: List<PoiEntity>, count: Int): List<Pair<Double, Double>> =
        all.groupingBy { (it.lat * 100).toInt() to (it.lon * 100).toInt() }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(count)
            .map { (cell, _) -> cell.first / 100.0 + 0.005 to cell.second / 100.0 + 0.005 }

    private fun inFrame(all: List<PoiEntity>, cLat: Double, cLon: Double, zoom: Double): List<PoiEntity> {
        val (dLat, dLon) = frameSpan(zoom, cLat)
        return all.filter {
            kotlin.math.abs(it.lat - cLat) <= dLat / 2 && kotlin.math.abs(it.lon - cLon) <= dLon / 2
        }
    }

    /**
     * Найважливіша перевірка хвилі: жоден КРИТИЧНИЙ рід, наявний у кадрі, не
     * зникає під стелею. Було 28% на z13 — тобто сімох критичних родів із
     * десяти людина не бачила.
     */
    @Test
    fun `every critical kind present in a dense frame reaches the glass`() {
        val all = corpus()
        var checked = 0
        for ((cLat, cLon) in densestCentres(all, 3)) {
            for (zoom in listOf(13.0, 14.0, 15.0)) {
                val frame = inFrame(all, cLat, cLon, zoom)
                val cap = PoiDensity.capForZoom(zoom)
                if (frame.size <= cap) continue
                val kept = PoiDensity.rank(frame, cLat, cLon, cap)
                val wanted = frame.filter { PlaceTiers.of(it) == PlaceTier.CRITICAL }
                    .map { PlaceTiers.kindOf(it) }.toSet()
                val shown = kept.map { PlaceTiers.kindOf(it) }.toSet()
                assertTrue(
                    "z$zoom @ $cLat,$cLon: критичні роди зникли — ${wanted - shown}",
                    wanted.all { it in shown },
                )
                checked++
            }
        }
        assertTrue("жоден кадр не переповнився — перевірка нічого не довела", checked >= 6)
    }

    /**
     * Друга половина скарги власника — засмічення. Двадцять однакових дисків
     * не кажуть більше за один, а місце зʼїдають.
     */
    @Test
    fun `no single kind swallows a dense frame`() {
        val all = corpus()
        var checked = 0
        for ((cLat, cLon) in densestCentres(all, 3)) {
            for (zoom in listOf(13.0, 14.0)) {
                val frame = inFrame(all, cLat, cLon, zoom)
                val cap = PoiDensity.capForZoom(zoom)
                if (frame.size <= cap) continue
                val kept = PoiDensity.rank(frame, cLat, cLon, cap)
                val piles = kept.groupingBy { PlaceTiers.kindOf(it) }.eachCount()
                val biggest = piles.maxOf { it.value }
                val kinds = piles.size
                // Коло по родах: доки є не набрані роди, другого з одного роду
                // не беруть. Купа більша за чверть кадру означала б, що закон
                // не діє — до зміни вона була 12 із 25 і 17 із 40.
                assertTrue(
                    "z$zoom @ $cLat,$cLon: купа $biggest із $cap одного роду (${piles.maxByOrNull { it.value }?.key})",
                    biggest <= cap / 4,
                )
                assertTrue("z$zoom: у кадрі лише $kinds родів із $cap місць", kinds >= cap / 2)
                checked++
            }
        }
        assertTrue("жоден кадр не переповнився — перевірка нічого не довела", checked >= 4)
    }

    /**
     * Брід має власний шар, тож у суцільному його нема — і слота він не бере.
     * У корпусі 25 991 брід, кожне сьоме місце країни.
     */
    @Test
    fun `fords are in the corpus and never on the continuous layer`() {
        val all = corpus()
        val fords = all.count { it.category == "ford" }
        assertTrue("броди зникли з корпусу — перевірка порожня", fords > 20_000)
        val here = all.filter { it.category == "ford" }.take(200)
        val kept = PoiDensity.rank(here, here[0].lat, here[0].lon, cap = 25)
        assertEquals(emptyList<PoiEntity>(), kept)
    }

    /**
     * Дзеркало ярусів: те, що корпус позначає водою, мусить читатись критичним
     * саме тими рядками, які поїдуть у телефон. Закритий словник тут навмисний
     * — рід води в OSM злічéнний, а мовчазний провал коштував би 12 193 точок.
     */
    @Test
    fun `water in the shipped corpus is read as critical`() {
        val all = corpus()
        val water = all.filter { it.subcategory in setOf("spring", "water_well", "drinking_water") }
        assertTrue("води в корпусі підозріло мало: ${water.size}", water.size > 10_000)
        val wrong = water.filter { PlaceTiers.of(it) != PlaceTier.CRITICAL }
        assertEquals("вода не читається критичною: ${wrong.take(3).map { it.subcategory }}", 0, wrong.size)
    }
}
