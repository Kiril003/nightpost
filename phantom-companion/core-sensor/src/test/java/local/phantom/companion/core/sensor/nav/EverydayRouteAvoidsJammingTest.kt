package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ЗВИЧАЙНА ПОЇЗДКА ТЕЖ ОБХОДИТЬ ГЛУШИЛКУ.
 *
 * Тінь глушилки ([GnssIntegrityMap]) і відкрита земля ([CoverGrid]) уже
 * працювали — але вмикались рівно в одному місці, `SpatialRoutingService`,
 * тобто лише коли людина тікає в укриття. `AppContainer.offlineMeshRoute`,
 * той самий [LocalRouter] над тим самим [RoadGraph], яким оператор
 * користується щодня, кликався БЕЗ них.
 *
 * Цей тест тримає саме ту силу, з якою головний маршрут тепер їде
 * ([RouteCaution.EVERYDAY]), а не абстрактне «поправка існує»: число легко
 * переставити так, що обʼїзду вже не буде, і тоді ворота мусять почервоніти.
 *
 * Геометрія тут синтетична, але контракт справжній — виклик слово в слово
 * той, що стоїть у `AppContainer`.
 */
class EverydayRouteAvoidsJammingTest {

    private val lat0 = 50.4500
    private val lon0 = 30.5200
    private fun latAt(northM: Double) = lat0 + Math.toDegrees(northM / 6_371_000.0)
    private fun lonAt(eastM: Double) =
        lon0 + Math.toDegrees(eastM / (6_371_000.0 * Math.cos(Math.toRadians(lat0))))

    /**
     * Драбина: дві паралельні вулиці «північ–південь» (західна на x=0,
     * східна на x=[WIDTH]), зшиті перемичками на n=[RUNG_S] і n=[RUNG_N].
     *
     * Довжина вибрана НЕ на око. Прямий шлях по глухій західній — 2160 м;
     * обʼїзд східною — 3600 м, з них 240 м усе одно по глухій. При
     * [RouteCaution.EVERYDAY] = 1,2 прямий коштує 2160 × 2,2 = 4752, обʼїзд —
     * 3360 + 240 × 2,2 = 3888, І ЩЕ чотири прямі кути перемичок по 90 м = 360.
     * Разом 4248 проти 4752 — обʼїзд виграє із запасом ~12 %.
     *
     * Останній доданок зʼявився в цій арифметиці 18.09, і драбину довелось
     * розтягнути вшестеро саме через нього. Доти кут не коштував нічого —
     * платили лише за зміну назви, по 25 м, — тож обіцяні тут колись «18 %»
     * насправді були 5,5 %, і ніхто цього не лічив. Тепер обхід глушилки
     * міряється проти ПОВНОЇ ціни обходу.
     */
    private fun ladder(): RoadGraph = RoadGraph(
        listOf(
            RoadWay(
                1L,
                listOf(
                    latAt(0.0) to lonAt(0.0), latAt(RUNG_S) to lonAt(0.0),
                    latAt(RUNG_N) to lonAt(0.0), latAt(3600.0) to lonAt(0.0),
                ),
                name = "Західна",
            ),
            RoadWay(
                2L,
                listOf(
                    latAt(0.0) to lonAt(WIDTH), latAt(RUNG_S) to lonAt(WIDTH),
                    latAt(RUNG_N) to lonAt(WIDTH), latAt(3600.0) to lonAt(WIDTH),
                ),
                name = "Східна",
            ),
            RoadWay(3L, listOf(latAt(RUNG_S) to lonAt(0.0), latAt(RUNG_S) to lonAt(WIDTH)), name = "Рунг-A"),
            RoadWay(4L, listOf(latAt(RUNG_N) to lonAt(0.0), latAt(RUNG_N) to lonAt(WIDTH)), name = "Рунг-B"),
        ),
    )

    /** Глушимо всю західну вулицю між перемичками. */
    private fun jammedWestAvenue(): GnssIntegrityMap {
        val map = GnssIntegrityMap(cellDeg = FINE_CELL)
        for (n in RUNG_S.toInt()..RUNG_N.toInt() step 20) {
            repeat(6) { map.observe(latAt(n.toDouble()), lonAt(0.0), 1.0f) }
        }
        return map
    }

    private fun usesEastAvenue(pts: List<Pair<Double, Double>>) =
        pts.any { Math.abs(it.second - lonAt(WIDTH)) < 1e-5 }

    @Test
    fun `the everyday route detours around a learned jammer shadow`() {
        val route = LocalRouter().route(
            ladder(), latAt(START), lonAt(0.0), latAt(GOAL), lonAt(0.0),
            RoadProfile.TravelMode.CAR,
            integrity = jammedWestAvenue(),
            jammingAvoidance = RouteCaution.EVERYDAY.jammingAvoidance,
        )
        assertNotNull("main route must still answer", route)
        assertTrue(
            "everyday route ignored the jammer shadow and drove straight up the west avenue",
            usesEastAvenue(route!!.points),
        )
    }

    /**
     * Контроль. Без цього попередній тест не доводить нічого: обʼїзд міг би
     * бути просто коротшим шляхом у цьому графі.
     */
    @Test
    fun `with caution OFF the same route takes the straight jammed avenue`() {
        val route = LocalRouter().route(
            ladder(), latAt(START), lonAt(0.0), latAt(GOAL), lonAt(0.0),
            RoadProfile.TravelMode.CAR,
            integrity = jammedWestAvenue(),
            jammingAvoidance = RouteCaution.OFF.jammingAvoidance,
        )
        assertNotNull(route)
        assertEquals("the direct path is 2160 m", 2160.0, route!!.distanceMeters, 40.0)
        assertTrue(
            "with avoidance off nothing should pull the route east",
            !usesEastAvenue(route.points),
        )
        assertTrue("the direct path is the jammed one", route.meanRisk > 0.5)
    }

    /** Обʼїзд коштує метрів — і звіт про них лишається ПРАВДИВИМ. */
    @Test
    fun `the detour is longer and reports its real length`() {
        val map = jammedWestAvenue()
        val straight = LocalRouter().route(
            ladder(), latAt(START), lonAt(0.0), latAt(GOAL), lonAt(0.0),
            RoadProfile.TravelMode.CAR, integrity = map, jammingAvoidance = 0.0,
        )!!
        val cautious = LocalRouter().route(
            ladder(), latAt(START), lonAt(0.0), latAt(GOAL), lonAt(0.0),
            RoadProfile.TravelMode.CAR,
            integrity = map, jammingAvoidance = RouteCaution.EVERYDAY.jammingAvoidance,
        )!!
        assertTrue("the safer route must cost real extra metres", cautious.distanceMeters > straight.distanceMeters)
        assertTrue("…and must actually buy lower risk", cautious.meanRisk < straight.meanRisk)
        // Жодної вигаданої довжини: заявлене число — це довжина ОБРАНОГО шляху.
        assertEquals(pathLen(cautious.points), cautious.distanceMeters, 1.0)
    }

    /**
     * Відкрита земля: та сама поправка, що в евакуації, подана тим самим
     * входом `extraCostFactor`, яким її подає `AppContainer`.
     */
    /**
     * Відкрита земля: та сама поправка, що в евакуації, подана тим самим
     * входом `extraCostFactor`, яким її подає `AppContainer`.
     *
     * Масштаб тут НЕ як у драбини, і це не недбалість: клітина [CoverGrid] —
     * ~100 м на широту й ~64 м на довготу, плюс сусіднє кільце. Дві вулиці за
     * 80 м одна від одної лежать усередині одного кільця, тобто сітка стін
     * фізично не вміє їх розрізнити — і вдавати, що вміє, було б брехнею про
     * прилад. Тому стіну міряємо там, де вона справді щось значить.
     */
    @Test
    fun `open ground costs more than a wall's shadow at everyday strength`() {
        val cover = RouteCaution.EVERYDAY.coverAvoidance
        val wall = CoverGrid.build(
            listOf(
                CoverGrid.Companion.Footprint(
                    listOf(
                        latAt(280.0) to lonAt(0.0),
                        latAt(320.0) to lonAt(0.0),
                        latAt(320.0) to lonAt(40.0),
                        latAt(280.0) to lonAt(40.0),
                    ),
                    heightM = 24f,
                ),
            ),
        )
        val sheltered = wall.costFactorAt(latAt(300.0), lonAt(20.0), cover)
        // Півкілометра геть — поза стіною й поза її сусіднім кільцем.
        val open = wall.costFactorAt(latAt(1500.0), lonAt(600.0), cover)
        assertTrue("open ground must cost more than a wall's shadow", open > sheltered)
        assertEquals("open ground costs exactly 1 + coverAvoidance", 1.0 + cover, open, 1e-9)
        assertTrue("the correction only ever steers — never below 1×", sheltered >= 1.0)
    }

    /**
     * Поправка КЕРМУЄ, а не забороняє: підсунута в той самий `extraCostFactor`,
     * вона не має права прибрати маршрут — навіть коли вся земля гола.
     */
    @Test
    fun `an all-open cover grid never removes the route`() {
        val cover = RouteCaution.EVERYDAY.coverAvoidance
        val empty = CoverGrid.build(emptyList()) // жодної стіни — усе відкрите
        val route = LocalRouter().route(
            ladder(), latAt(START), lonAt(0.0), latAt(GOAL), lonAt(0.0),
            RoadProfile.TravelMode.FOOT,
            integrity = jammedWestAvenue(),
            jammingAvoidance = RouteCaution.EVERYDAY.jammingAvoidance,
            extraCostFactor = { lat, lon -> empty.costFactorAt(lat, lon, cover) },
        )
        assertNotNull("the composed jamming × cover field must not kill the route", route)
        assertEquals(pathLen(route!!.points), route.distanceMeters, 1.0)
    }

    /**
     * Сам тумблер. Число — ЗАГЛУШКА до слова власника, і взята вона рівно з
     * евакуації; цей тест не дає двом шляхам розповзтися мовчки.
     */
    @Test
    fun `everyday caution starts from the proven evacuation baseline`() {
        assertEquals(
            "everyday routing must start at the evacuation strength, not a fresh guess",
            RouteCaution.EVACUATION, RouteCaution.EVERYDAY,
        )
        assertEquals(LocalRouter.DEFAULT_AVOIDANCE, RouteCaution.AVOIDANT.jammingAvoidance, 1e-9)
        assertEquals(0.7, RouteCaution.AVOIDANT.coverAvoidance, 1e-9)
        assertEquals(0.0, RouteCaution.OFF.jammingAvoidance, 1e-9)
        assertEquals(0.0, RouteCaution.OFF.coverAvoidance, 1e-9)
        // CAUTIOUS не має власного числа — воно похідне, і це навмисно.
        assertEquals(
            RouteCaution.AVOIDANT.jammingAvoidance / 2.0,
            RouteCaution.CAUTIOUS.jammingAvoidance,
            1e-9,
        )
        assertEquals(
            RouteCaution.AVOIDANT.coverAvoidance / 2.0,
            RouteCaution.CAUTIOUS.coverAvoidance,
            1e-9,
        )
    }

    private fun pathLen(pts: List<Pair<Double, Double>>): Double {
        var d = 0.0
        for (i in 1 until pts.size) {
            d += haversineM(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
        }
        return d
    }

    private companion object {
        /** ~22 m tiles — fine enough to tell the two avenues apart.
         *  Production uses ~278 m and should not pretend to more. */
        const val FINE_CELL = 0.0002

        /** Відстань між вулицями й місця перемичок — див. [ladder]. */
        const val WIDTH = 480.0
        const val RUNG_S = 600.0
        const val RUNG_N = 3000.0
        const val START = 720.0
        const val GOAL = 2880.0
    }
}
