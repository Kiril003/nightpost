package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Скільки коштує маршрут на графі, що лежить у пристрої.
 *
 * Питання не академічне: 29.08 порядок джерел перевернуто — граф тепер перший,
 * мережа лише коли граф каже «не знаю». Якщо A* по кешованій мережі коштує
 * секунди, інверсія була б регресом, а не лікуванням. Досі цього не міряв ніхто.
 *
 * Заміряно 29.08 на сітці 5×5 км (102 вулиці): побудова графа 19 мс, маршрут
 * через ~10 км — медіана 7,5 мс, найгірший 21,8 мс. Тобто сам пошук шляху не є
 * вузьким місцем навіть із десятикратним запасом на повільніше залізо.
 *
 * ЧОГО ЦЕЙ ВИМІР НЕ ПОКРИВАЄ, і це треба знати перш ніж на нього спиратись:
 *  1. Це JVM на ноутбуці, не ARM у кишені. Ворота «перебудова < 200 мс» цим НЕ
 *     доводяться — їх доводить секундомір на пристрої (рядок `route` у чорній
 *     скрині несе джерело й мілісекунди саме для цього).
 *  2. Тут НЕМАЄ читання сховища. У продукті перед графом стоїть
 *     `RoadMeshStore.waysIn(bbox)` — запит до SQLite, і на телефоні саме він
 *     може виявитись дорожчим за сам A*. Не заміряно.
 *  3. Сітка синтетична й регулярна. Справжня мережа — криві, тупики, розвʼязки;
 *     евристика A* на них працює гірше, ніж на решітці.
 */
class RoadGraphSpeedTest {

    /**
     * Сітка вулиць 5×5 км із кроком ~100 м — щільність центру міста: 51 вулиця
     * на північ, 51 на схід, кожна з 51 вузлом. Це та сама форма, яку віддає
     * `RoadMeshStore.waysIn` для коридору з двокілометровим запасом.
     */
    private fun cityGrid(): List<RoadWay> {
        val ways = mutableListOf<RoadWay>()
        val step = 0.0009 // ~100 м по широті
        val lonStep = 0.0014 // ~100 м по довготі на широті 50°
        var id = 1L
        for (i in 0..50) {
            val lat = 50.0 + i * step
            ways += RoadWay(
                id = id++,
                points = (0..50).map { lat to (30.0 + it * lonStep) },
                name = "Вулиця $i",
                tags = mapOf("highway" to "residential"),
            )
        }
        for (j in 0..50) {
            val lon = 30.0 + j * lonStep
            ways += RoadWay(
                id = id++,
                points = (0..50).map { (50.0 + it * step) to lon },
                name = "Проспект $j",
                tags = mapOf("highway" to "residential"),
            )
        }
        return ways
    }

    @Test
    fun `маршрут через усе місто рахується на графі, і його вартість названа`() {
        val ways = cityGrid()
        val buildStart = System.nanoTime()
        val graph = RoadGraph(ways)
        val buildMs = (System.nanoTime() - buildStart) / 1_000_000.0

        val router = LocalRouter()
        // Прогрів: перший прогін платить за завантаження класів, і брати його
        // за вартість маршруту означало б звинуватити граф у чужому рахунку.
        router.route(graph, 50.001, 30.001, 50.010, 30.010)

        val runs = 5
        val times = DoubleArray(runs)
        var route: LocalRouter.LocalRoute? = null
        for (i in 0 until runs) {
            val t0 = System.nanoTime()
            route = router.route(graph, 50.0005, 30.0005, 50.0445, 30.0695)
            times[i] = (System.nanoTime() - t0) / 1_000_000.0
        }
        times.sort()
        val median = times[runs / 2]

        assertNotNull("граф мусить знайти шлях у власній сітці", route)
        println(
            "RoadGraphSpeed: доріг=${ways.size}, побудова графа=${"%.0f".format(buildMs)} мс, " +
                "маршрут 5 км: медіана=${"%.1f".format(median)} мс, " +
                "найгірший=${"%.1f".format(times.last())} мс, " +
                "довжина=${"%.0f".format(route!!.distanceMeters)} м",
        )
        // Стеля свідомо велика: тут ловиться катастрофа (секунди), а не
        // ворота продукту. Точне число — на пристрої.
        assertTrue(
            "A* по кешованій мережі не має коштувати секунди: медіана $median мс",
            median < 1_000.0,
        )
    }

    /**
     * СКІЛЬКИ ЙОГО НАСПРАВДІ. Сітка вище — 102 вулиці; справжній коридор
     * більший у 574 рази, і саме тому вимір на ній нічого не доводив.
     *
     * Заміряно 03.09.2026 на самому пакеті, що поїде в пристрій
     * (`~/phantom_ai/road-mesh-bake/phantom_road_mesh.kyiv-v3.db`), запитом
     * дім власника (50.504668, 30.410170) → центр (50.4501, 30.5234) з тим
     * самим скиртом `pad = 0.02°`, що його бере `AppContainer.offlineMeshRoute`:
     *
     *   54 комірки · **58 533 дороги** · **5,85 МБ** тексту геометрії ·
     *   **295 246 вершин** · 1,84 МБ тегів
     *
     * Це вартість ОДНОГО маршруту: стільки рядків пристрій читає, розбирає в
     * обʼєкти й індексує, перш ніж A* зробить перший крок. Числа тут — не
     * вигадана «щільність центру», а перепис того самого коридору за класами:
     * див. [CORRIDOR] і `scratchpad/corridor_classes.py`.
     *
     * ЩО ЦЕЙ ВИМІР ДОДАЄ до трьох застережень у шапці класу: він накриває
     * розбір рядків і побудову графа в НАТУРАЛЬНОМУ масштабі. Чого й далі НЕ
     * накриває — читання з `android.database.sqlite` (CursorWindow, ART): це
     * лишається єдиною незаміряною ланкою, і вона доводиться лише на апараті.
     *
     * ЗАМІРЯНО 04.09 (58 358 доріг, 296 940 вершин): розбір 200 мс, побудова
     * 313 мс, пошук 40 мс — разом 552 мс на JVM. До 04.09 маршрут ТУТ НЕ
     * ЗНАХОДИВСЯ, і рядок друкував «пошук=15 мс» — ціну обходу, що вперся в
     * порожнечу. Причина була у фікстурі, не в маршрутизаторі: кістяк ставив
     * лінії з кроком /39, а вершини з кроком /50, тож проспекти перетинались,
     * не маючи спільного вузла (4 спільні вузли на 1600 перетинів). Невдалий
     * пошук дешевший за вдалий — тому «вимір, лишений як вимір» показував не
     * просто червоне, а менше число, ніж правда.
     */
    @Test
    fun `коридор натурального масштабу — розбір, побудова і пошук названі окремо`() {
        val rows = corridorRows()

        val decodeAt = System.nanoTime()
        val ways = ArrayList<RoadWay>(rows.size)
        for (r in rows) {
            val pts = RoadWayCodec.decodePoints(r.pts) ?: continue
            ways += RoadWay(
                id = r.id,
                points = pts,
                name = null,
                oneway = false,
                tags = RoadWayCodec.decodeTags(r.tags),
            )
        }
        val decodeMs = (System.nanoTime() - decodeAt) / 1_000_000.0

        val buildAt = System.nanoTime()
        val graph = RoadGraph(ways)
        val buildMs = (System.nanoTime() - buildAt) / 1_000_000.0

        val router = LocalRouter()
        val routeAt = System.nanoTime()
        val route = router.route(
            graph = graph,
            fromLat = SOUTH + 0.004, fromLon = WEST + 0.004,
            toLat = SOUTH + SPAN_LAT - 0.004, toLon = WEST + SPAN_LON - 0.004,
            footProfile = false,
        )
        val routeMs = (System.nanoTime() - routeAt) / 1_000_000.0

        val verts = ways.sumOf { it.points.size }
        println(
            "RoadGraphScale: доріг=${ways.size}, вершин=$verts | " +
                "розбір=${"%.0f".format(decodeMs)} мс, " +
                "побудова=${"%.0f".format(buildMs)} мс, " +
                "пошук=${"%.0f".format(routeMs)} мс | " +
                "разом=${"%.0f".format(decodeMs + buildMs + routeMs)} мс",
        )

        // Коридор мусить лишатись коридором, а не порідшати непомітно: якщо
        // генератор колись зʼїде, вимір мовчки почне міряти іншу річ.
        assertTrue("коридор здрібнів: ${ways.size} доріг", ways.size > 55_000)
        assertTrue("коридор здрібнів: $verts вершин", verts > 270_000)
        assertNotNull("по кістяку проспектів маршрут мусить існувати", route)

        // Ворота продукту — 200 мс НА ПРИСТРОЇ. Тут стеля вчетверо мʼякша і
        // ловить лише катастрофу: JVM на столі не є оцінкою для ARM у кишені
        // (див. застереження 1 у шапці класу).
        assertTrue(
            "підготовка коридору коштує секунди: розбір $decodeMs + побудова $buildMs мс",
            decodeMs + buildMs < 4_000.0,
        )
    }

    // ---- натуральний коридор -------------------------------------------------

    private class Row(val id: Long, val pts: String, val tags: String)

    /**
     * Клас дороги, скільки їх у коридорі й скільки в них вершин — з перепису
     * київського пакета, не з голови. `ways × vertsPerWay` дає ті самі
     * 295 246 вершин, що їх лічив `corridor_classes.py`.
     */
    private class ClassSpec(
        val highway: String,
        val ways: Int,
        val vertsPerWay: Double,
        val extraTags: String,
    )

    /**
     * Перепис коридору дім→центр на kyiv-v3. Порядок — за вагою в байтах:
     * тротуари й службові проїзди дають 72% усієї геометрії, і саме вони
     * роблять підготовку дорогою.
     */
    private val CORRIDOR = listOf(
        ClassSpec("footway", 25_060, 4.79, "surface=paving_stones"),
        ClassSpec("service", 19_871, 4.65, "service=driveway"),
        ClassSpec("path", 3_228, 7.34, "surface=ground"),
        ClassSpec("steps", 3_431, 2.30, ""),
        ClassSpec("residential", 1_750, 8.33, "surface=asphalt"),
        ClassSpec("secondary", 1_588, 5.09, "maxspeed=50|lanes=2"),
        ClassSpec("track", 1_044, 10.65, "surface=unpaved"),
        ClassSpec("primary", 819, 5.27, "maxspeed=60|lanes=4"),
        ClassSpec("tertiary", 676, 6.07, "maxspeed=50"),
        ClassSpec("cycleway", 296, 12.13, ""),
        ClassSpec("living_street", 148, 6.19, ""),
        ClassSpec("unclassified", 126, 7.13, ""),
        ClassSpec("pedestrian", 123, 10.62, "surface=paving_stones"),
        ClassSpec("primary_link", 118, 6.41, "maxspeed=60"),
    )

    /**
     * Кістяк проспектів — те, чим A* справді поїде. Свідомо крихітний (80 ліній
     * проти 58 тисяч): у справжньому коридорі проїзна мережа теж губиться в
     * масі тротуарів і дворових проїздів, і саме це робить підготовку дорогою,
     * а пошук — дешевим.
     */
    private fun corridorRows(): List<Row> {
        val out = ArrayList<Row>(60_000)
        var id = 1L
        val skeleton = 40
        // Вершини мусять лежати на ТІЙ САМІЙ ґратці, що й самі лінії. `RoadGraph`
        // зшиває дороги лише у спільному вузлі — координаті, округленій до 1e-5
        // (~1 м), — а послідовності i/39 (де стоять лінії) і t/50 (де стояли
        // вершини) збігаються лише в кутах. Заміряно 04.09: за двох кроків
        // спільними були 4 вузли з 1600 перетинів, тобто проспекти
        // перетинались, не торкаючись, і маршруту по кістяку не існувало.
        // Це була вада фікстури, не маршрутизатора.
        val cells = skeleton - 1
        for (i in 0 until skeleton) {
            val lat = SOUTH + SPAN_LAT * i / cells
            out += Row(
                id++,
                (0..cells).joinToString(";") {
                    fmt(lat, WEST + SPAN_LON * it / cells)
                },
                "highway=secondary|maxspeed=50",
            )
            val lon = WEST + SPAN_LON * i / cells
            out += Row(
                id++,
                (0..cells).joinToString(";") {
                    fmt(SOUTH + SPAN_LAT * it / cells, lon)
                },
                "highway=secondary|maxspeed=50",
            )
        }

        // Маса. Насіння фіксоване — вимір мусить бути тим самим числом завтра.
        val rnd = java.util.Random(4)
        for (spec in CORRIDOR) {
            val tags = if (spec.extraTags.isEmpty()) {
                "highway=${spec.highway}"
            } else {
                "highway=${spec.highway}|${spec.extraTags}"
            }
            for (k in 0 until spec.ways) {
                // Дробова середня довжина розкладається на цілі: інакше 4,79
                // вершини перетворились би на 4 і коридор схуд би на 16%.
                val n = spec.vertsPerWay.toInt() +
                    if (rnd.nextDouble() < spec.vertsPerWay % 1.0) 1 else 0
                val lat0 = SOUTH + SPAN_LAT * rnd.nextDouble()
                val lon0 = WEST + SPAN_LON * rnd.nextDouble()
                val dLat = (rnd.nextDouble() - 0.5) * 0.0004
                val dLon = (rnd.nextDouble() - 0.5) * 0.0006
                out += Row(
                    id++,
                    (0 until maxOf(n, 2)).joinToString(";") {
                        fmt(lat0 + dLat * it, lon0 + dLon * it)
                    },
                    tags,
                )
            }
        }
        return out
    }

    /** Той самий формат, що й у сховищі: 6 знаків, крапка, Locale.US. */
    private fun fmt(lat: Double, lon: Double) =
        String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon)

    private companion object {
        /** Коридор дім→центр зі скиртом 0,02°: 0,095° × 0,153°. */
        const val SOUTH = 50.4301
        const val WEST = 30.3902
        const val SPAN_LAT = 0.0946
        const val SPAN_LON = 0.1532
    }
}
