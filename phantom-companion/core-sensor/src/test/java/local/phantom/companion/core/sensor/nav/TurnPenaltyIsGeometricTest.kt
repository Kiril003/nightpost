package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поворот коштує за КУТОМ, а не за назвою вулиці.
 *
 * Ключем штрафу була рівність назв, і вона хибила в обидва боки. Заміряно
 * 17.09.2026 на паку `kyiv-v5` (165 250 доріг), шість київських поїздок,
 * профіль АВТО: 28 справжніх поворотів (кут > 30°) проти 52 прямих ділянок,
 * за які взято як за поворот; один справжній поворот був безкоштовний; а
 * РОЗВОРОТ коштував нуль — обидві половини вулиці звуться однаково.
 *
 * Чотири випробування нижче — це рівно чотири боки тієї вади, і кожен з них
 * червоніє, якщо повернути рівність назв (доведено переставлянням 18.09):
 *  1. прямо крізь перехрестя, де вулиця міняє імʼя — не поворот;
 *  2. поворот на 90° усередині вулиці з одним іменем — поворот;
 *  3. розворот названий і оплачений окремо від повороту;
 *  4. розворот програє обхідному кварталу, який лише на 56 м довший.
 */
class TurnPenaltyIsGeometricTest {

    private val tags = mapOf("highway" to "residential")
    private val freeFlow = RoadProfile.speedMps(tags, RoadProfile.TravelMode.CAR)

    private fun way(id: Long, name: String, vararg pts: Pair<Double, Double>) =
        RoadWay(id = id, points = pts.toList(), name = name, tags = tags)

    private fun drive(
        ways: List<RoadWay>,
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
    ) = LocalRouter().route(
        graph = RoadGraph(ways),
        fromLat = from.first, fromLon = from.second,
        toLat = to.first, toLon = to.second,
        mode = RoadProfile.TravelMode.CAR,
    )

    /** Скільки секунд понад вільний хід — тобто рівно затримка на вузлах. */
    private fun delaySeconds(r: LocalRouter.LocalRoute) =
        r.durationSeconds - r.distanceMeters / freeFlow

    // ---- 1. пряма крізь зміну назви ------------------------------------------

    @Test
    fun `пряма крізь перехрестя, де вулиця міняє імʼя, не коштує повороту`() {
        // Одна пряма, розрізана на дві дороги з різними назвами. Так у OSM
        // виглядає кожна вулиця, що десь посеред кварталу міняє імʼя, — і так
        // само виглядає просте продовження вулиці за перехрестям.
        val ways = listOf(
            way(1, "Вулиця Богдана", 50.4500 to 30.5000, 50.4500 to 30.5010),
            way(2, "Вулиця Миколи", 50.4500 to 30.5010, 50.4500 to 30.5020),
        )
        val r = drive(ways, 50.4500 to 30.5000, 50.4500 to 30.5020)
        assertNotNull("пряма вулиця мусить мати маршрут", r)
        assertEquals(
            "їхати прямо — не поворот, хоч би як вулиця звалась: зайві секунди",
            0.0, delaySeconds(r!!), 0.01,
        )
    }

    // ---- 2. поворот усередині одного імені -----------------------------------

    @Test
    fun `поворот на 90° коштує, навіть коли вулиця по обидва боки одна`() {
        // Г-подібна вулиця: одне імʼя, справжній прямий кут на перехресті.
        val ways = listOf(
            way(1, "Кільцева", 50.4500 to 30.5000, 50.4510 to 30.5000),
            way(2, "Кільцева", 50.4510 to 30.5000, 50.4510 to 30.5014),
        )
        val r = drive(ways, 50.4500 to 30.5000, 50.4510 to 30.5014)
        assertNotNull("по Г-подібній вулиці мусить бути маршрут", r)
        assertEquals(
            "прямий кут — це поворот, і рівність назв цього не скасовує",
            LocalRouter.INTERSECTION_TURN_DELAY_S, delaySeconds(r!!), 0.01,
        )
    }

    // ---- 3. розворот ---------------------------------------------------------

    /**
     * Дві половини проспекту з одним іменем, що сходяться у вузлі J. Дійти до
     * південної можна лише розвернувшись — іншого шляху в цьому графі немає.
     */
    private fun avenueOnly() = listOf(
        RoadWay(
            id = 1,
            points = listOf(50.4491 to 30.5000, 50.4499 to 30.5000, 50.4500 to 30.5000),
            name = "Проспект",
            oneway = true,
            tags = tags,
        ),
        way(
            2, "Проспект",
            50.4500 to 30.5000, 50.44995 to 30.50001, 50.4498 to 30.50030,
            50.4496 to 30.50030, 50.4492 to 30.50030,
        ),
    )

    @Test
    fun `розворот названий розворотом і оплачений окремо`() {
        val graph = RoadGraph(avenueOnly())
        val from = graph.near(50.4491, 30.5000, 30.0, 8).first()
        val to = graph.near(50.4494, 30.50030, 30.0, 8).first()
        val traced = graph.routeTraced(from, to, respectOneway = true)
        assertNotNull("розворот — це шлях, а не відмова", traced)
        assertEquals(
            "рівно один розворот на цьому маршруті: ${traced!!.turnKinds}",
            1, traced.turnKinds.count { it == RoadGraph.TurnKind.U_TURN },
        )

        val r = drive(avenueOnly(), 50.4491 to 30.5000, 50.4494 to 30.50030)
        assertNotNull(r)
        assertEquals(
            "розворот мусить коштувати ЯВНО, а не нуль через спільне імʼя",
            LocalRouter.U_TURN_DELAY_S, delaySeconds(r!!), 0.01,
        )
    }

    // ---- 4. вибір: розворот проти кварталу ------------------------------------

    /**
     * ВИБІР. Дві дороги до тієї самої точки на південній половині проспекту:
     *
     *  - розворотом на вершині: 177 м і один розворот;
     *  - перемичкою «Ланка», не доїжджаючи до вершини: 158 м і два повороти.
     *
     * Числа підібрані так, щоб розвести дві мірки, а не щоб одна з них була
     * очевидною: за старою (розворот безкоштовний, перемичка — два штрафи за
     * зміну назви) дешевший розворот, 177 проти 208; за геометричною —
     * перемичка, 338 проти 467.
     */
    private fun avenueWithLink() = avenueOnly() + listOf(
        way(3, "Ланка", 50.4499 to 30.5000, 50.4498 to 30.50030),
    )

    @Test
    fun `маршрут іде перемичкою замість розвертатись`() {
        val graph = RoadGraph(avenueWithLink())
        val from = graph.near(50.4491, 30.5000, 30.0, 8).first()
        val to = graph.near(50.4494, 30.50030, 30.0, 8).first()
        val traced = graph.routeTraced(from, to, respectOneway = true)
        assertNotNull("перемичка існує — відмовляти нема підстав", traced)

        assertEquals(
            "розвороту тут бути не має: ${traced!!.turnKinds}",
            0, traced.turnKinds.count { it == RoadGraph.TurnKind.U_TURN },
        )
        // Вершина проспекту (50.44995) лежить ЛИШЕ на шляху розворотом. Це
        // перевірка самої обраної геометрії, а не лічильника — лічильник
        // можна було б задовольнити й інакше.
        assertTrue(
            "маршрут усе одно поїхав розвертатись: ${traced.points}",
            traced.points.none { kotlin.math.abs(it.first - 50.44995) < 1e-7 },
        )
    }

    // ---- дзеркало вироку про кут ---------------------------------------------

    /**
     * Поріг має ТРИ стани, і кожен названий. Якщо колись розворот зроблять
     * просто «крутим поворотом», почервоніє тут.
     */
    @Test
    fun `кут ділиться на пряму, поворот і розворот`() {
        // Вхід — на північ; вихід крутиться за годинниковою.
        fun kindAt(deg: Double) = RoadGraph.turnKind(
            0.0, 1.0,
            kotlin.math.sin(Math.toRadians(deg)), kotlin.math.cos(Math.toRadians(deg)),
        )
        assertEquals(RoadGraph.TurnKind.STRAIGHT, kindAt(0.0))
        assertEquals(RoadGraph.TurnKind.STRAIGHT, kindAt(29.0))
        assertEquals(RoadGraph.TurnKind.TURN, kindAt(31.0))
        assertEquals(RoadGraph.TurnKind.TURN, kindAt(90.0))
        assertEquals(RoadGraph.TurnKind.TURN, kindAt(149.0))
        assertEquals(RoadGraph.TurnKind.U_TURN, kindAt(151.0))
        assertEquals(RoadGraph.TurnKind.U_TURN, kindAt(180.0))
        // Дзеркально вліво — та сама відповідь.
        assertEquals(RoadGraph.TurnKind.U_TURN, kindAt(-170.0))
        assertEquals(RoadGraph.TurnKind.TURN, kindAt(-90.0))

        assertTrue(
            "розворот мусить коштувати помітно дорожче за поворот",
            RoadGraph.turnPenaltyM(RoadGraph.TurnKind.U_TURN) >=
                RoadGraph.turnPenaltyM(RoadGraph.TurnKind.TURN) * 2,
        )
        assertEquals(0.0, RoadGraph.turnPenaltyM(RoadGraph.TurnKind.STRAIGHT), 0.0)
    }
}
