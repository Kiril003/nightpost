package local.phantom.companion.core.geo.metro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пішохідна навігація в підземному просторі станції метро.
 *
 * Тут класична навігація неможлива за визначенням: супутника немає, а помилка на
 * один рівень або один вихід коштує людині десяти хвилин і зайвого підйому
 * сходами. Тому граф перевіряється на СИНТЕТИЧНІЙ станції, зібраній руками, — щоб
 * кожне правило (напрямок ескалатора, доступність ліфта, вертикальні звʼязки)
 * було ізольоване й не залежало від того, як саме розпарсився OSM.
 *
 * Станція нижче: вестибюль на рівні -1, платформа на рівні -2, а між ними — три
 * незалежні вертикальні звʼязки: сходи, ескалатор (їде вниз) і ліфт.
 */
class IndoorGraphTest {

    private val hallId = 1L
    private val platformId = 2L

    /** Вестибюль (level -1) і платформа (level -2), зʼєднані сходами, ескалатором вниз і ліфтом. */
    private fun station(
        stairsWheelchair: Wheelchair = Wheelchair.NO,
        elevatorWheelchair: Wheelchair = Wheelchair.YES,
    ): IndoorGraph {
        val hall = IndoorNode(hallId, 50.45010, 30.52340, level = -1.0, kind = IndoorNodeKind.CONCOURSE, name = "вестибюль")
        val platform = IndoorNode(platformId, 50.45030, 30.52360, level = -2.0, kind = IndoorNodeKind.PLATFORM, name = "платформа")

        val stairs = IndoorEdge(hallId, platformId, IndoorEdgeKind.STAIRS, lengthM = 22.0, wheelchair = stairsWheelchair)
        // Ескалатор креслиться вестибюль→платформа й conveying=FORWARD — тобто
        // полотно рухається саме вниз, у бік платформи.
        val escalator = IndoorEdge(hallId, platformId, IndoorEdgeKind.ESCALATOR, lengthM = 20.0, conveying = Conveying.FORWARD)
        val elevator = IndoorEdge(hallId, platformId, IndoorEdgeKind.ELEVATOR, lengthM = 0.0, wheelchair = elevatorWheelchair)

        return IndoorGraph(listOf(hall, platform), listOf(stairs, escalator, elevator))
    }

    // ————————————————————————————————————————————————————————————————
    // Базовий маршрут
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `маршрут від вестибюля до платформи знаходиться`() {
        val route = station().route(hallId, platformId)
        assertNotNull("зі станції з трьома звʼязками маршрут мусить існувати", route)
        assertEquals("маршрут має привести саме на платформу", platformId, route!!.nodeIds.last())
        assertEquals("маршрут перетинає рівно один рівень (-1 → -2)", 1, route.levelChanges)
        assertTrue("час проходження додатний", route.totalSeconds > 0.0)
    }

    @Test
    fun `маршрут у ту саму вершину — порожній, а не відмова`() {
        val route = station().route(hallId, hallId)
        assertNotNull("тривіальний маршрут існує", route)
        assertTrue("він не містить кроків", route!!.steps.isEmpty())
    }

    // ————————————————————————————————————————————————————————————————
    // Доступність — не опція, а вимога
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `у режимі доступності маршрут будується через ліфт, а не сходами`() {
        // Людина у візку: сходи й ескалатори відпадають повністю. Якщо єдиний
        // вертикальний звʼязок, який лишається, — ліфт, маршрут має піти ним; інакше
        // алгоритм тихо запропонував би сходи, а це фізично неможливий маршрут.
        val route = station().route(hallId, platformId, IndoorRouteOptions(requireWheelchair = true))

        assertNotNull("у візку до платформи є ліфт — маршрут мусить знайтись", route)
        assertTrue("маршрут доступності зобовʼязаний користуватись ліфтом", route!!.uses(IndoorEdgeKind.ELEVATOR))
        assertFalse("сходи в режимі доступності недопустимі", route.uses(IndoorEdgeKind.STAIRS))
        assertFalse("ескалатор у режимі доступності недопустимий", route.uses(IndoorEdgeKind.ESCALATOR))
    }

    @Test
    fun `без ліфта режим доступності чесно не знаходить шляху`() {
        // Станція без ліфта (лише сходи й ескалатор) для візка недосяжна. Правильна
        // відповідь — null («сюди не потрапити»), а не підмінений маршрут сходами.
        val hall = IndoorNode(hallId, 50.45010, 30.52340, -1.0, IndoorNodeKind.CONCOURSE)
        val platform = IndoorNode(platformId, 50.45030, 30.52360, -2.0, IndoorNodeKind.PLATFORM)
        val stairs = IndoorEdge(hallId, platformId, IndoorEdgeKind.STAIRS, lengthM = 22.0, wheelchair = Wheelchair.NO)
        val escalator = IndoorEdge(hallId, platformId, IndoorEdgeKind.ESCALATOR, lengthM = 20.0, conveying = Conveying.FORWARD)
        val noElevator = IndoorGraph(listOf(hall, platform), listOf(stairs, escalator))

        assertNull(
            "без ліфта в візку до платформи шляху немає — і це має бути видима відмова",
            noElevator.route(hallId, platformId, IndoorRouteOptions(requireWheelchair = true)),
        )
    }

    @Test
    fun `вимкнені сходи не залишають без маршруту, поки є ескалатор чи ліфт`() {
        val route = station().route(hallId, platformId, IndoorRouteOptions(allowStairs = false))
        assertNotNull("сходи не єдиний звʼязок — маршрут лишається", route)
        assertFalse("вимкнені сходи не мають зʼявлятись у маршруті", route!!.uses(IndoorEdgeKind.STAIRS))
    }

    // ————————————————————————————————————————————————————————————————
    // Напрямок ескалатора
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `проти напрямку ескалатора пройти не можна`() {
        // Єдиний звʼязок — ескалатор, що їде ВГОРУ (BACKWARD відносно креслення
        // вестибюль→платформа). Спуск ним неможливий; підйом — так.
        val hall = IndoorNode(hallId, 50.45010, 30.52340, -1.0, IndoorNodeKind.CONCOURSE)
        val platform = IndoorNode(platformId, 50.45030, 30.52360, -2.0, IndoorNodeKind.PLATFORM)
        val upOnly = IndoorEdge(hallId, platformId, IndoorEdgeKind.ESCALATOR, lengthM = 20.0, conveying = Conveying.BACKWARD)
        val graph = IndoorGraph(listOf(hall, platform), listOf(upOnly))

        assertNull("вниз проти ескалатора, що їде вгору, — неможливо", graph.route(hallId, platformId))
        assertNotNull("вгору за ходом ескалатора — можна", graph.route(platformId, hallId))
    }

    // ————————————————————————————————————————————————————————————————
    // Недосяжність
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `до ізольованої вершини маршруту нема`() {
        val isolated = IndoorNode(99L, 50.45100, 30.52500, -1.0, IndoorNodeKind.CONCOURSE)
        val hall = IndoorNode(hallId, 50.45010, 30.52340, -1.0, IndoorNodeKind.CONCOURSE)
        val platform = IndoorNode(platformId, 50.45030, 30.52360, -2.0, IndoorNodeKind.PLATFORM)
        val stairs = IndoorEdge(hallId, platformId, IndoorEdgeKind.STAIRS, lengthM = 22.0)
        val graph = IndoorGraph(listOf(hall, platform, isolated), listOf(stairs))

        assertNull("до вершини без жодного ребра шляху немає", graph.route(hallId, 99L))
        assertNull("до неіснуючої вершини — теж чесний null", graph.route(hallId, 123456L))
    }

    @Test
    fun `односторонній прохід не пропускає у зворотний бік`() {
        // Турнікет на вихід або розділені потоки: oneway=true проходиться лише
        // за напрямком from→to.
        val a = IndoorNode(hallId, 50.45010, 30.52340, -1.0, IndoorNodeKind.CONCOURSE)
        val b = IndoorNode(platformId, 50.45030, 30.52360, -1.0, IndoorNodeKind.JUNCTION)
        val gate = IndoorEdge(hallId, platformId, IndoorEdgeKind.WALKWAY, lengthM = 8.0, oneway = true)
        val graph = IndoorGraph(listOf(a, b), listOf(gate))

        assertNotNull("за напрямком турнікета пройти можна", graph.route(hallId, platformId))
        assertNull("проти одностороннього проходу шляху немає", graph.route(platformId, hallId))
    }
}
