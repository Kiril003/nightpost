package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «Здався» і «доріг немає» — РІЗНІ речі, і людина мусить бачити, яка з них.
 *
 * Досі [LocalRouter.route] на всі випадки віддавав один `null`, а
 * `AppContainer.offlineMeshRoute` перекладав його одним реченням «доріг цієї
 * ділянки на пристрої немає — потрібен дорожній пакет». Заміряно 16.09.2026
 * на київському пакеті: чотири з шести звичайних поїздок по місту впирались у
 * стелю A* і діставали саме цю пораду — при вже встановленому пакеті на 54 МБ.
 *
 * Сторож тримає рівно те, що мовчання не повернеться: кожна відмова має
 * назватись, і назватись ПРАВИЛЬНО.
 */
class RouteRefusalIsNamedTest {

    private fun straightWay(id: Long, lat0: Double, lon0: Double, lat1: Double, lon1: Double) =
        RoadWay(
            id = id,
            points = listOf(lat0 to lon0, lat1 to lon1),
            name = "вулиця $id",
            tags = mapOf("highway" to "residential"),
        )

    @Test
    fun `порожній граф зветься порожнім графом`() {
        var why: LocalRouter.Refusal? = null
        val route = LocalRouter().route(
            RoadGraph(emptyList()), 50.45, 30.52, 50.46, 30.53,
            RoadProfile.TravelMode.CAR, onRefusal = { why = it },
        )
        assertNull(route)
        assertEquals(LocalRouter.Refusal.EMPTY_GRAPH, why)
    }

    @Test
    fun `старт у полі зветься сходом з дороги, а не відсутністю даних`() {
        val graph = RoadGraph(listOf(straightWay(1, 50.4500, 30.5200, 50.4500, 30.5400)))
        var why: LocalRouter.Refusal? = null
        // ~2 км на північ від єдиної дороги: далі за SNAP_RADIUS_M = 120 м.
        val route = LocalRouter().route(
            graph, 50.4680, 30.5300, 50.4500, 30.5300,
            RoadProfile.TravelMode.CAR, onRefusal = { why = it },
        )
        assertNull(route)
        assertEquals(
            "точка за 2 км від дороги — це не «пакета немає»",
            LocalRouter.Refusal.SNAP_FROM, why,
        )
    }

    @Test
    fun `мета у полі зветься сходом з дороги`() {
        val graph = RoadGraph(listOf(straightWay(1, 50.4500, 30.5200, 50.4500, 30.5400)))
        var why: LocalRouter.Refusal? = null
        val route = LocalRouter().route(
            graph, 50.4500, 30.5250, 50.4680, 30.5300,
            RoadProfile.TravelMode.CAR, onRefusal = { why = it },
        )
        assertNull(route)
        assertEquals(LocalRouter.Refusal.SNAP_TO, why)
    }

    /**
     * Дві дороги поруч, які НЕ мають спільного вузла: обидва кінці чіпляються,
     * фронт вичерпується, мети не досягнуто. Це і є єдиний чесний «доріг між
     * цими точками в завантаженій окрузі немає».
     */
    @Test
    fun `дві незвʼязані дороги звуться відсутністю шляху`() {
        val graph = RoadGraph(
            listOf(
                straightWay(1, 50.4500, 30.5200, 50.4500, 30.5230),
                straightWay(2, 50.4600, 30.5300, 50.4600, 30.5330),
            ),
        )
        var why: LocalRouter.Refusal? = null
        val route = LocalRouter().route(
            graph, 50.4500, 30.5210, 50.4600, 30.5320,
            RoadProfile.TravelMode.CAR, onRefusal = { why = it },
        )
        assertNull(route)
        assertEquals(LocalRouter.Refusal.NO_PATH, why)
    }

    /**
     * ГОЛОВНИЙ сторож цього файлу: звʼязана мережа, у якій шлях Є, але стеля
     * розкриттів не дає його знайти. Вимагається [RoadGraph.Refusal.EXHAUSTED]
     * — саме те, що досі мовчки ставало «доріг немає».
     *
     * Ланцюг із 400 ланок дає A* понад 800 вершин; стеля в 5 розкриттів
     * гарантовано впирається, хоч звʼязок існує.
     */
    @Test
    fun `стеля пошуку зветься стелею, а не відсутністю доріг`() {
        val ways = (0 until 400).map { i ->
            straightWay(
                i.toLong() + 1,
                50.4500, 30.5200 + i * 0.0010,
                50.4500, 30.5200 + (i + 1) * 0.0010,
            )
        }
        val graph = RoadGraph(ways)
        val from = graph.near(50.4500, 30.5205, 120.0, 8).first()
        val to = graph.near(50.4500, 30.6195, 120.0, 8).first()

        var why: RoadGraph.Refusal? = null
        val cramped = graph.routeTraced(
            from, to, maxExpandedNodes = 5, onRefusal = { why = it },
        )
        assertNull("зі стелею в 5 розкриттів шляху не знайти", cramped)
        assertEquals(
            "пошук здався — це твердження про нас, не про місцевість",
            RoadGraph.Refusal.EXHAUSTED, why,
        )

        // Та сама мережа, та сама пара точок, повна стеля: шлях Є. Саме це й
        // доводить, що EXHAUSTED вище був про стелю, а не про дороги.
        var whyFull: RoadGraph.Refusal? = null
        val full = graph.routeTraced(from, to, onRefusal = { whyFull = it })
        assertNull("повна стеля не мусить відмовляти зовсім", whyFull)
        assert(full != null) { "шлях у звʼязаному ланцюгу мусить знайтись" }
    }

    /** Успіх не кличе [LocalRouter.Refusal] взагалі — інакше споживач писав би
     *  причину поверх уже відданого маршруту. */
    @Test
    fun `успішний маршрут не називає жодної причини`() {
        val graph = RoadGraph(listOf(straightWay(1, 50.4500, 30.5200, 50.4500, 30.5400)))
        var why: LocalRouter.Refusal? = null
        val route = LocalRouter().route(
            graph, 50.4500, 30.5210, 50.4500, 30.5390,
            RoadProfile.TravelMode.CAR, onRefusal = { why = it },
        )
        assert(route != null) { "пряма дорога мусить прокластись" }
        assertNull("причина при успіху — це шум, який споживач прийме за відмову", why)
    }
}
