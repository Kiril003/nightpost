package local.phantom.companion.core.geo.metro

import local.phantom.companion.core.net.map.OverpassCenter
import local.phantom.companion.core.net.map.OverpassElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Збирання графа станції з відповіді Overpass.
 *
 * Найтонше місце — ідентичність вершини. `out geom` віддає координати, але не
 * ідентифікатори вузлів усередині ліній, тож спільна точка двох шляхів
 * упізнається за координатою, квантованою до 1e-7 градуса, ПЛЮС рівень. Без
 * рівня граф злипається у пласку кашу, і маршрут «пройдіть крізь перекриття»
 * виглядає коротким і цілком законним. Ці тести перевіряють саме рівні,
 * вертикальні звʼязки й напрямок ескалатора з тегів — тобто те, що відрізняє
 * справжню станцію від плаского клубка ліній.
 */
class OsmIndoorParserTest {

    private val parser = OsmIndoorParser()

    private fun node(id: Long, lat: Double, lon: Double, tags: Map<String, String>) =
        OverpassElement(id = id, type = "node", lat = lat, lon = lon, tags = tags)

    private fun way(id: Long, geometry: List<Pair<Double, Double>>, tags: Map<String, String>) =
        OverpassElement(
            id = id,
            type = "way",
            geometry = geometry.map { OverpassCenter(it.first, it.second) },
            tags = tags,
        )

    // ————————————————————————————————————————————————————————————————
    // Рівні: level важливіший за layer
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `рівень береться з level, а layer не плутається з поверхом`() {
        // layer описує порядок перетину геометрії на площині, а не поверх.
        // Сплутати їх — класична помилка, від якої перехід опиняється на одному
        // рівні з платформою.
        assertEquals("одне ціле значення", listOf(-2.0), parser.levelsOf(mapOf("level" to "-2")))
        assertEquals("level має пріоритет над layer", listOf(-1.0), parser.levelsOf(mapOf("level" to "-1", "layer" to "-3")))
        assertEquals("дробовий проміжний майданчик", listOf(-1.5), parser.levelsOf(mapOf("level" to "-1.5")))
        assertEquals("перелік через крапку з комою", listOf(-2.0, 0.0), parser.levelsOf(mapOf("level" to "-2;0")))
    }

    @Test
    fun `тунель без тегів рівня — під землею, а не на вулиці`() {
        // Нуль (вулиця) для підземного тунелю неправильний за визначенням.
        assertEquals(listOf(-1.0), parser.levelsOf(mapOf("tunnel" to "yes")))
        assertEquals("без жодного тегу — рівень вулиці", listOf(0.0), parser.levelsOf(emptyMap()))
    }

    // ————————————————————————————————————————————————————————————————
    // Ліфт-вузол як шахта
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `ліфт із переліком рівнів стає вертикальним звʼязком`() {
        // `highway=elevator` з `level=-2;0` — це шахта, а не точка: вершина на
        // кожному рівні плюс ребро-ліфт між ними, яким тільки й можна проїхати
        // вертикально у візку.
        val graph = parser.parse(
            listOf(
                node(
                    id = 500, lat = 50.4501, lon = 30.5234,
                    tags = mapOf("highway" to "elevator", "level" to "-2;0", "wheelchair" to "yes"),
                ),
            ),
        )

        assertEquals("шахта дає вершину на кожному рівні", 2, graph.nodes.size)
        val elevatorEdges = graph.edges.filter { it.kind == IndoorEdgeKind.ELEVATOR }
        assertEquals("між двома рівнями — один ліфт", 1, elevatorEdges.size)
        assertEquals("ліфт успадкував доступність із тегу", Wheelchair.YES, elevatorEdges.first().wheelchair)

        val lower = graph.nodes.minByOrNull { it.level }!!
        val upper = graph.nodes.maxByOrNull { it.level }!!
        assertNotNull("ліфтом можна проїхати між рівнями шахти", graph.route(lower.id, upper.id))
    }

    // ————————————————————————————————————————————————————————————————
    // Напрямок ескалатора з тега conveying
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `сходи з тегом conveying стають ескалатором із напрямком`() {
        // `highway=steps` + `conveying=backward`: ескалатор, що рухається проти
        // напрямку креслення. Пройти за кресленням (вниз) не можна, проти — можна.
        val graph = parser.parse(
            listOf(
                way(
                    id = 700,
                    geometry = listOf(50.4501 to 30.5234, 50.4503 to 30.5236),
                    tags = mapOf("highway" to "steps", "conveying" to "backward", "level" to "-1;-2"),
                ),
            ),
        )

        val escalators = graph.edges.filter { it.kind == IndoorEdgeKind.ESCALATOR }
        assertEquals("проліт зі steps+conveying — це ескалатор", 1, escalators.size)
        assertEquals("напрямок узятий з тегу", Conveying.BACKWARD, escalators.first().conveying)

        val e = escalators.first()
        assertNull("за напрямком креслення (вниз) проти ходу — не можна", graph.route(e.fromId, e.toId))
        assertNotNull("проти напрямку креслення (за ходом ескалатора) — можна", graph.route(e.toId, e.fromId))
    }

    // ————————————————————————————————————————————————————————————————
    // Ідентичність вершини
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `два шляхи зі спільною точкою на одному рівні зшиваються в одну вершину`() {
        // Спільна координата на однаковому рівні — це та сама вершина; інакше граф
        // розсипається на незвʼязані шматки й маршрут не знаходиться там, де фізично
        // є прохід.
        val shared = 50.4502 to 30.5235
        val graph = parser.parse(
            listOf(
                way(id = 800, geometry = listOf(50.4501 to 30.5234, shared), tags = mapOf("highway" to "footway", "level" to "-1")),
                way(id = 801, geometry = listOf(shared, 50.4503 to 30.5236), tags = mapOf("highway" to "footway", "level" to "-1")),
            ),
        )

        assertEquals("спільна точка не подвоюється у вершину", 3, graph.nodes.size)

        val ends = graph.nodes.filter { it.id != graph.node(nodeAt(graph, shared))!!.id }
        assertEquals(2, ends.size)
        assertNotNull(
            "через зшиту вершину маршрут між кінцями двох шляхів має існувати",
            graph.route(ends[0].id, ends[1].id),
        )
    }

    @Test
    fun `та сама точка на різних рівнях лишається різними вершинами`() {
        // Дві точки одна над одною на різних поверхах — це різні вершини, зʼєднати
        // які може лише ліфт, сходи чи ескалатор. Без цього людину «проводять»
        // крізь перекриття.
        val samePlanCoord = 50.4502 to 30.5235
        val graph = parser.parse(
            listOf(
                way(id = 900, geometry = listOf(50.4501 to 30.5234, samePlanCoord), tags = mapOf("highway" to "footway", "level" to "-1")),
                way(id = 901, geometry = listOf(samePlanCoord, 50.4503 to 30.5236), tags = mapOf("highway" to "footway", "level" to "-2")),
            ),
        )

        assertEquals("координата на двох рівнях дає дві окремі вершини", 4, graph.nodes.size)
        val onMinusOne = graph.nodes.filter { it.level == -1.0 }
        val onMinusTwo = graph.nodes.filter { it.level == -2.0 }
        assertTrue("є вершини на -1", onMinusOne.isNotEmpty())
        assertTrue("є вершини на -2", onMinusTwo.isNotEmpty())
        assertNull(
            "без вертикального звʼязку крізь перекриття маршруту бути не повинно",
            graph.route(onMinusOne.first().id, onMinusTwo.first().id),
        )
    }

    @Test
    fun `порожній вхід дає порожній граф, а не виняток`() {
        val graph = parser.parse(emptyList())
        assertTrue("немає вершин", graph.nodes.isEmpty())
        assertTrue("немає ребер", graph.edges.isEmpty())
    }

    /** Знайти id вершини за плановою координатою (будь-який рівень). */
    private fun nodeAt(graph: IndoorGraph, coord: Pair<Double, Double>): Long =
        graph.nodes.first {
            Math.round(it.latDeg * 1e7) == Math.round(coord.first * 1e7) &&
                Math.round(it.lonDeg * 1e7) == Math.round(coord.second * 1e7)
        }.id
}
