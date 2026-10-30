package local.phantom.companion.feature.stream.ui.map

import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import local.phantom.companion.core.data.poi.PlaceTier
import local.phantom.companion.core.data.poi.PlaceTiers
import local.phantom.companion.core.data.poi.PoiDensity
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.feature.stream.ui.map.atlas.LensFrame
import local.phantom.companion.feature.stream.ui.map.atlas.LensRules
import local.phantom.companion.feature.stream.ui.map.atlas.SceneMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ЩО САМЕ ЛЯГАЄ НА СКЛО — увесь ланцюг, на справжньому корпусі країни.
 *
 * ЧОМУ ЦЬОГО НЕ ВИСТАЧАЛО РАНІШЕ. `PlaceFrameDensityTest` у `core-data` міряє
 * ПЕРШУ стелю — `PoiDensity.rank`, і доводить, що набраний нею кадр розмаїтий.
 * Але між нею і склом стоїть ДРУГА стеля, про яку той тест нічого не знає:
 * `PlaceLens.select` із бюджетом Лінзи (`LensRules.*_PLACES`). Вона ріже
 * ІНШИМ законом — за ярусом і порядком приходу, а не колом по родах, — і
 * мовчки могла б повернути в кадр рівно ту купу, яку перша стеля щойно
 * розібрала. Ніхто цього не міряв: обидві половини були зелені нарізно.
 *
 * ЩО ТУТ МІРЯЄТЬСЯ. Три найщільніші комірки країни × три зуми × усі режими,
 * повним шляхом: корпус → `PoiDensity.rank` → `PlaceLens.select`.
 * Геометрія кадру — A52 (411×914 dp) і плитка MapLibre, те саме числення, що
 * в `PlaceFrameDensityTest`: ширина в градусах = 360 · 411 / (512 · 2^z).
 *
 * ЗАМІРЯНО 17.09 НА ЦЬОМУ Ж КОРПУСІ (176 558 рядків), купа / родів зі слотів:
 *   z13, кадр 988–1416 рядків: CALM 1/25, WALK 1/25, TRANSIT 1/24,
 *        DRIVE 1/12, THREAT 1/8;
 *   z14, кадр 555–814:         CALM 1/40, TRANSIT 1/24, DRIVE 1/12, THREAT 1/8;
 *   z15, кадр 240–359:         CALM 2/41, WALK 2/26, TRANSIT 2/15,
 *        DRIVE 2/8, THREAT 2/5.
 * Тобто друга стеля купи НЕ повертає — і саме це тут тримається.
 */
class PlaceChainFrameDensityTest {

    private companion object {
        /** Ознаки води в корпусі — те, чого шукають не за назвою. */
        val WATER = setOf("spring", "water_well", "drinking_water")

        /**
         * Корпус читається ОДИН раз на клас: 176 558 рядків × кожен тест — це
         * секунди на порожньому місці.
         */
        val corpus: List<PoiEntity> by lazy(::readCorpus)

        /**
         * Розбір тут свій, і це не дублювання з примхи: `OfflinePlacePack.parseLine`
         * оголошений `internal` у `core-data`, тобто з цього модуля недосяжний.
         * Тому колонки звіряються явно нижче — розійтись мовчки вони не можуть.
         */
        private fun readCorpus(): List<PoiEntity> {
            val f = File("../core-data/src/main/assets/country/ua/places.tsv")
            assertTrue("довідник місць мусить лежати за ${f.absolutePath}", f.exists())
            val out = ArrayList<PoiEntity>(180_000)
            f.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readLine()
                var idx = 0L
                while (true) {
                    val line = reader.readLine() ?: break
                    val c = line.split('\t')
                    if (c.size < 6) continue
                    val lat = c[0].toDoubleOrNull() ?: continue
                    val lon = c[1].toDoubleOrNull() ?: continue
                    out += PoiEntity(
                        id = idx++,
                        lat = lat,
                        lon = lon,
                        name = c[5],
                        category = c[2],
                        subcategory = c[3].takeIf { it.isNotBlank() },
                        tags = emptyMap(),
                    )
                }
            }
            assertTrue("корпус підозріло малий: ${out.size}", out.size > 100_000)
            return out
        }
    }

    /** Геометрія кадру A52 в градусах — те саме числення, що й у камери мапи. */
    private fun frameSpan(zoom: Double, lat: Double): Pair<Double, Double> {
        val world = 512.0 * Math.pow(2.0, zoom)
        val lonSpan = 360.0 * 411.0 / world
        val latSpan = 360.0 * 914.0 / world * cos(Math.toRadians(lat))
        return latSpan to lonSpan
    }

    private fun densestCentres(count: Int): List<Pair<Double, Double>> =
        corpus.groupingBy { (it.lat * 100).toInt() to (it.lon * 100).toInt() }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(count)
            .map { (cell, _) -> cell.first / 100.0 + 0.005 to cell.second / 100.0 + 0.005 }

    private fun inFrame(cLat: Double, cLon: Double, zoom: Double): List<PoiEntity> {
        val (dLat, dLon) = frameSpan(zoom, cLat)
        return corpus.filter { abs(it.lat - cLat) <= dLat / 2 && abs(it.lon - cLon) <= dLon / 2 }
    }

    private fun budgetOf(scene: SceneMode): Int = when (scene) {
        SceneMode.DRIVE -> LensRules.DRIVE_PLACES
        SceneMode.WALK -> LensRules.WALK_PLACES
        SceneMode.TRANSIT -> LensRules.TRANSIT_PLACES
        SceneMode.THREAT -> LensRules.THREAT_PLACES
        else -> LensFrame.UNLIMITED
    }

    /** Кадр так, як його побачить око: обидві стелі поспіль, нічого між ними. */
    private fun onGlass(frame: List<PoiEntity>, cLat: Double, cLon: Double, zoom: Double, scene: SceneMode) =
        PlaceLens.select(
            PoiDensity.rank(frame, cLat, cLon, PoiDensity.capForZoom(zoom)),
            scene,
            zoom,
            budgetOf(scene),
        )

    /** Кадр звертається до колонок за номером — розбіжність тут = тихий сміттєвий корпус. */
    @Test
    fun `the shipped corpus still has the six columns this test reads`() {
        val first = File("../core-data/src/main/assets/country/ua/places.tsv")
            .bufferedReader(Charsets.UTF_8).use { it.readLine(); it.readLine() }
        val cols = first.orEmpty().split('\t')
        assertEquals("колонок у рядку корпусу: $first", 6, cols.size)
        assertTrue("перша колонка мусить бути широтою: ${cols[0]}", cols[0].toDoubleOrNull() != null)
        assertTrue("друга колонка мусить бути довготою: ${cols[1]}", cols[1].toDoubleOrNull() != null)
    }

    /**
     * ДРУГА СТЕЛЯ НЕ ПОВЕРТАЄ КУПИ.
     *
     * Скарга власника має дві половини — порожнеча й засмічення, — і ця
     * половина про друге: двадцять однакових знаків не кажуть більше за один,
     * а місце зʼїдають. Перша стеля це вже вирішила колом по родах; тут
     * доводиться, що бюджет Лінзи того рішення не скасовує.
     */
    @Test
    fun `no scene lets one kind pile up on the glass`() {
        var checked = 0
        for ((cLat, cLon) in densestCentres(3)) {
            for (zoom in listOf(13.0, 14.0, 15.0)) {
                val frame = inFrame(cLat, cLon, zoom)
                if (frame.size <= PoiDensity.capForZoom(zoom)) continue
                for (scene in SceneMode.entries) {
                    val glass = onGlass(frame, cLat, cLon, zoom, scene)
                    if (glass.isEmpty()) continue
                    val piles = glass.groupingBy { PlaceTiers.kindOf(it) }.eachCount()
                    val biggest = piles.maxOf { it.value }
                    assertTrue(
                        "z$zoom/$scene @ $cLat,$cLon: купа $biggest однакових із ${glass.size} " +
                            "(${piles.maxByOrNull { it.value }?.key})",
                        biggest <= 2,
                    )
                    // Заміряно: найгірше — 41 рід зі 60 слотів на z15. Половина
                    // лишає запас і при цьому червоніє на будь-якій справжній купі.
                    assertTrue(
                        "z$zoom/$scene @ $cLat,$cLon: лише ${piles.size} родів на ${glass.size} місць",
                        piles.size >= glass.size / 2,
                    )
                    checked++
                }
            }
        }
        assertTrue("жоден кадр не переповнився — перевірка нічого не довела", checked >= 20)
    }

    /**
     * ДРУГА ПОЛОВИНА СКАРГИ — ПОРОЖНЕЧА. Вода, наявна в кадрі, мусить дійти до
     * скла, а не згоріти на другій стелі.
     *
     * Сирена тут свідомо осторонь і НЕ послаблення: під сиреною бюджет вісім
     * ([LensRules.THREAT_PLACES]), і вісім найближчих критичних місць у центрі
     * великого міста — це вісім аптек і лікарень, тобто вода може не влізти за
     * жодного порядку, поки вона рівна їм у ярусі. Заміряно 17.09 на восьми
     * найщільніших комірках: у 84 кадрах вода була в джерелі, і зникала вона
     * РІВНО в трьох — усі три під сиреною. Питання «чи вода старша за аптеку,
     * коли екран вісім слотів» — рішення власника, не цього тесту; тому сирена
     * тримається нижче за тим, що вона СПРАВДІ обіцяє.
     */
    @Test
    fun `water in the frame reaches the glass in every scene but the siren`() {
        var checked = 0
        for ((cLat, cLon) in densestCentres(3)) {
            for (zoom in listOf(13.0, 14.0, 15.0)) {
                val frame = inFrame(cLat, cLon, zoom)
                if (frame.size <= PoiDensity.capForZoom(zoom)) continue
                for (scene in SceneMode.entries - SceneMode.THREAT) {
                    val ranked = PoiDensity.rank(frame, cLat, cLon, PoiDensity.capForZoom(zoom))
                    val available = ranked.count { it.subcategory in WATER }
                    if (available == 0) continue
                    val shown = onGlass(frame, cLat, cLon, zoom, scene).count { it.subcategory in WATER }
                    assertTrue(
                        "z$zoom/$scene @ $cLat,$cLon: води в джерелі $available, на склі $shown",
                        shown > 0,
                    )
                    checked++
                }
            }
        }
        assertTrue("вода не траплялась у жодному кадрі — перевірка порожня", checked >= 10)
    }

    /**
     * Те, що сирена справді обіцяє: на склі лишається ЛИШЕ критичне. Бюджет
     * вісім вирішує скільки, але не має права впустити крамницю.
     */
    @Test
    fun `under the siren the glass carries nothing but critical places`() {
        var checked = 0
        for ((cLat, cLon) in densestCentres(3)) {
            for (zoom in listOf(13.0, 14.0, 15.0)) {
                val frame = inFrame(cLat, cLon, zoom)
                if (frame.size <= PoiDensity.capForZoom(zoom)) continue
                val glass = onGlass(frame, cLat, cLon, zoom, SceneMode.THREAT)
                assertTrue("під сиреною порожньо на z$zoom @ $cLat,$cLon", glass.isNotEmpty())
                val intruders = glass.filter { PlaceTiers.of(it) != PlaceTier.CRITICAL }
                assertEquals(
                    "z$zoom @ $cLat,$cLon: під сиреною некритичне на склі — " +
                        "${intruders.take(3).map { it.name to it.subcategory }}",
                    0,
                    intruders.size,
                )
                assertTrue(glass.size <= LensRules.THREAT_PLACES)
                checked++
            }
        }
        assertTrue("жоден кадр не переповнився", checked >= 6)
    }

    /**
     * БЕЗІМЕННЕ НЕ СМІЄ ПОТОНУТИ.
     *
     * 52 604 рядки корпусу йдуть під РОДОВИМ іменником («Джерело», «Криниця»,
     * «Питна вода») — навмисно, бо воду шукають не за назвою, і власної назви
     * в джерела немає за побудовою. Рівно між ними і склом стоїть закритий
     * словник [POIOverlay.poiRenderable]: порожнє, «Unknown», `GENERIC_NAMES`,
     * `BARRIER_FURNITURE_NAMES`. Жодне з цих правил не бачить ярусу — тож
     * одне слово, дописане в той словник, тихо зняло б із мапи всю воду
     * країни, і жоден наявний тест би не почервонів.
     *
     * Заміряно 17.09 на всьому корпусі: `poiRenderable` відмовляє РІВНО двом
     * рядкам зі 176 558 (обидва з іменем «shop»), і жоден із них не критичний.
     */
    @Test
    fun `no critical place in the shipped corpus is refused for its name`() {
        val critical = corpus.filter {
            PlaceTiers.of(it) == PlaceTier.CRITICAL && !PlaceTiers.hasOwnLayer(it)
        }
        assertTrue("критичних у корпусі підозріло мало: ${critical.size}", critical.size > 10_000)
        val refused = critical.filterNot { POIOverlay.drawableOnGround(it) }
        assertEquals(
            "закритий словник імен зʼїв критичні місця: ${refused.take(5).map { it.name to it.subcategory }}",
            0,
            refused.size,
        )
        val water = corpus.filter { it.subcategory in WATER }
        assertTrue("води в корпусі підозріло мало: ${water.size}", water.size > 10_000)
        assertEquals(
            "вода не дійшла до шару: ${water.filterNot { POIOverlay.drawableOnGround(it) }.take(5).map { it.name }}",
            water.size,
            water.count { POIOverlay.drawableOnGround(it) },
        )
    }
}
