package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стеля пошуку мусить лишатись достатньою для поїздки через МІСТО.
 *
 * Заміряно 16–17.09.2026 на справжньому пакеті `kyiv-v5` (165 250 доріг):
 * зі старою стелею 60 000 чотири з шести звичайних київських поїздок не
 * мали маршруту взагалі, а найдорожчій із них — Голосіїв → Виноградар —
 * знадобилось 180 000 розкриттів. Стеля 200 000 стоїть саме тому.
 *
 * Сторож не вірить константі на слово: він будує змійку, яку A* мусить
 * пройти вершина за вершиною, і доводить ОБИДВА боки — що на старій стелі
 * пошук здається й КАЖЕ про це, а на чинній доходить до мети. Якщо колись
 * стелю опустять назад (чи заново загублять слово «здався»), почервоніє
 * саме цей файл, а не людина в полі.
 */
class SearchCeilingHoldsTest {

    /** Стеля, з якої знято чотири з шести київських поїздок. */
    private val oldCeiling = 60_000

    /**
     * ЗМІЙКА — рядки, зшиті спільними вузлами. Прямого шляху немає: єдиний
     * звʼязок веде через кожну вершину, тож кількість розкриттів дорівнює
     * кількості вершин, і евристика її не зріже.
     */
    private fun serpentine(rows: Int, perRow: Int): RoadGraph {
        val lat0 = 50.4000
        val lon0 = 30.4000
        val dLat = 0.0006
        val dLon = 0.00002
        val ways = ArrayList<RoadWay>(rows)
        for (i in 0 until rows) {
            val lat = lat0 + i * dLat
            val pts = ArrayList<Pair<Double, Double>>(perRow + 1)
            for (j in 0 until perRow) {
                val k = if (i % 2 == 0) j else perRow - 1 - j
                pts.add(lat to lon0 + k * dLon)
            }
            // Останньою точкою рядок піднімається на широту наступного — саме
            // ця спільна точка й зшиває два рядки в один вузол графа.
            pts.add(lat0 + (i + 1) * dLat to pts.last().second)
            ways.add(
                RoadWay(
                    id = i.toLong() + 1,
                    points = pts,
                    name = "змійка $i",
                    tags = mapOf("highway" to "residential"),
                ),
            )
        }
        return RoadGraph(ways)
    }

    @Test
    fun `стеля тримає поїздку, що не влазила у стару`() {
        // 360 × 340 ≈ 122 400 вершин: більше за стару стелю, менше за чинну.
        val graph = serpentine(rows = 360, perRow = 340)
        val from = graph.near(50.4000, 30.4000, 120.0, 8).first()
        val to = graph.near(50.4000 + 359 * 0.0006, 30.4000, 120.0, 8).first()

        var whyOld: RoadGraph.Refusal? = null
        val onOldCeiling = graph.routeTraced(
            from, to, maxExpandedNodes = oldCeiling, onRefusal = { whyOld = it },
        )
        assertNull("на стелі $oldCeiling ця поїздка не складалась — саме це й заміряно", onOldCeiling)
        assertEquals(
            "здатись — це твердження про нас, і воно мусить лунати вголос",
            RoadGraph.Refusal.EXHAUSTED, whyOld,
        )

        var whyNow: RoadGraph.Refusal? = null
        val onCurrent = graph.routeTraced(from, to, onRefusal = { whyNow = it })
        assertNotNull(
            "чинна стеля ${RoadGraph.MAX_ASTAR_NODES} мусить доводити цю поїздку до мети",
            onCurrent,
        )
        assertNull("дійшовши до мети, пошук не має права називати причину", whyNow)
    }

    /**
     * Ратчет на саму константу. Заміряне число — 180 000 на найдорожчій
     * київській поїздці; усе, що нижче, знімає її з мапи мовчки.
     */
    @Test
    fun `стелю не опущено нижче за виміряну потребу`() {
        assertTrue(
            "Голосіїв → Виноградар вимагав 180 000 розкриттів (заміряно 16.09.2026 на kyiv-v5); " +
                "стеля ${RoadGraph.MAX_ASTAR_NODES} лишає місто без маршруту",
            RoadGraph.MAX_ASTAR_NODES >= 180_000,
        )
    }
}
