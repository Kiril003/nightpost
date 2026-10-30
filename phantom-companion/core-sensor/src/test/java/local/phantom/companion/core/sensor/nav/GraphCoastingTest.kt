package local.phantom.companion.core.sensor.nav

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Обіцянки лічильника шляху під РЕБ, закріплені:
 *
 *  - поворот веде ДОРОГОЮ, а не полем (головний тест: він падає, якщо прибрати
 *    обмеження графом і лишити вільне числення);
 *  - «не знаю» — це окремий стан із причиною, а не тиха остання точка;
 *  - сумнів росте з пройденою відстанню і скидається ЛИШЕ довіреним фіксом;
 *  - після відмови лічильник не оживає сам.
 *
 * Сітка вулиць будується тут же, списком [RoadWay], у метрах від однієї точки
 * Києва — жодних справжніх даних, щоб тест говорив тільки про клас.
 */
class GraphCoastingTest {

    private companion object {
        const val BASE_LAT = 50.4500
        const val BASE_LON = 30.5200

        /** Метрів на градус широти на сфері 6 371 км. */
        const val M_PER_DEG_LAT = 111_194.93
    }

    private val mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(BASE_LAT))

    /** Точка за [northM] метрів на північ і [eastM] метрів на схід від бази. */
    private fun p(northM: Double, eastM: Double): Pair<Double, Double> =
        (BASE_LAT + northM / M_PER_DEG_LAT) to (BASE_LON + eastM / mPerDegLon)

    /** Точка за [m] метрів у напрямку [bearingDeg] від точки (northM, eastM). */
    private fun from(northM: Double, eastM: Double, m: Double, bearingDeg: Double): Pair<Double, Double> {
        val rad = Math.toRadians(bearingDeg)
        return p(northM + m * cos(rad), eastM + m * sin(rad))
    }

    private fun distM(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        hypot((a.first - b.first) * M_PER_DEG_LAT, (a.second - b.second) * mPerDegLon)

    /** Вулиця «схід-захід» довжиною [lengthM] від бази. */
    private fun eastWest(id: Long, lengthM: Double) = RoadWay(
        id = id,
        name = "Прямий проспект",
        points = listOf(p(0.0, 0.0), p(0.0, lengthM)),
    )

    private fun held(c: GraphCoasting.Coast): GraphCoasting.Coast.Held {
        assertTrue("очікували позицію, отримали $c", c is GraphCoasting.Coast.Held)
        return c as GraphCoasting.Coast.Held
    }

    private fun lost(c: GraphCoasting.Coast): GraphCoasting.Coast.Lost {
        assertTrue("очікували чесне «не знаю», отримали $c", c is GraphCoasting.Coast.Lost)
        return c as GraphCoasting.Coast.Lost
    }

    /**
     * ГОЛОВНИЙ ТЕСТ. Г-подібна вулиця: проспект на схід, від його кінця вулиця
     * строго на північ. Гіроскоп після повороту дає курс 25° — не 0°, бо
     * реальний курс не збігається з вулицею до градуса.
     *
     * Вільне числення (без графа) віднесло б точку на ~55 м у поле на схід від
     * вулиці. Обмежений графом лічильник МУСИТЬ іти вулицею. Прибери обмеження
     * графом — і цей тест червоніє на обох перевірках: і на id дороги, і на
     * відстані до «польової» точки.
     */
    @Test
    fun `поворот за курсом веде дорогою, а не полем`() {
        val prospekt = RoadWay(id = 1L, name = "Проспект", points = listOf(p(0.0, 0.0), p(0.0, 200.0)))
        val vulytsia = RoadWay(id = 2L, name = "Вулиця", points = listOf(p(0.0, 200.0), p(200.0, 200.0)))
        val coast = GraphCoasting(RoadGraph(listOf(prospekt, vulytsia)))

        held(coast.seed(p(0.0, 20.0).first, p(0.0, 20.0).second, courseDeg = 90.0, accuracyM = 5.0, atMs = 0L))
        held(coast.step(distanceM = 170.0, courseDeg = 90.0, atMs = 10_000L))

        val after = held(coast.step(distanceM = 150.0, courseDeg = 25.0, atMs = 20_000L))

        assertEquals("мусив перейти на поперечну вулицю", 2L, after.way.id)
        assertEquals("один дискретний вибір на перехресті", 1, after.junctionsPassed)

        val onRoad = p(150.0, 200.0)
        assertEquals(
            "позиція мусить бути на вулиці, за 150 м від перехрестя",
            0.0, distM(after.lat to after.lon, onRoad), 3.0,
        )

        // Куди б занесло вільне числення: 190 м на схід, далі 150 м курсом 25°.
        val inTheField = from(0.0, 190.0, 150.0, 25.0)
        assertTrue(
            "точка збіглася з вільним численням — обмеження графом не працює",
            distM(after.lat to after.lon, inTheField) > 40.0,
        )
    }

    @Test
    fun `неоднозначне розгалуження — це «не знаю», а не вгадування`() {
        val trunk = RoadWay(id = 1L, name = "Стовбур", points = listOf(p(0.0, 0.0), p(200.0, 0.0)))
        val left = RoadWay(id = 2L, name = "Ліва", points = listOf(p(200.0, 0.0), from(200.0, 0.0, 200.0, 350.0)))
        val right = RoadWay(id = 3L, name = "Права", points = listOf(p(200.0, 0.0), from(200.0, 0.0, 200.0, 10.0)))
        val coast = GraphCoasting(RoadGraph(listOf(trunk, left, right)))

        held(coast.seed(p(100.0, 0.0).first, p(100.0, 0.0).second, courseDeg = 0.0, accuracyM = 5.0, atMs = 0L))
        val result = lost(coast.step(distanceM = 150.0, courseDeg = 0.0, atMs = 5_000L))

        assertEquals(GraphCoasting.Reason.AMBIGUOUS_JUNCTION, result.reason)
        assertTrue("відмова мусить мати слова", result.sentenceUa.isNotBlank())
        assertNotNull("місце, де слід обірвався, показати можна", result.lostAtLat)
    }

    @Test
    fun `кінець дороги повертає «шлях вичерпано», а не тиху останню точку`() {
        val coast = GraphCoasting(RoadGraph(listOf(eastWest(1L, 200.0))))
        held(coast.seed(p(0.0, 20.0).first, p(0.0, 20.0).second, courseDeg = 90.0, accuracyM = 5.0, atMs = 0L))

        val result = coast.step(distanceM = 300.0, courseDeg = 90.0, atMs = 5_000L)

        assertTrue("тиха підстановка позиції заборонена", result !is GraphCoasting.Coast.Held)
        assertEquals(GraphCoasting.Reason.PATH_EXHAUSTED, lost(result).reason)
    }

    @Test
    fun `курс, що зійшов з графа, — це NO_ROAD_NEARBY, а не рух полем`() {
        val coast = GraphCoasting(RoadGraph(listOf(eastWest(1L, 200.0))))
        held(coast.seed(p(0.0, 100.0).first, p(0.0, 100.0).second, courseDeg = 90.0, accuracyM = 5.0, atMs = 0L))

        // Курс 0°: людина пішла двором, якого в графі немає.
        val result = lost(coast.step(distanceM = 50.0, courseDeg = 0.0, atMs = 5_000L))

        assertEquals(GraphCoasting.Reason.NO_ROAD_NEARBY, result.reason)
    }

    @Test
    fun `фікс поза графом не стартує лічильник`() {
        val coast = GraphCoasting(RoadGraph(listOf(eastWest(1L, 200.0))))

        val result = lost(coast.seed(p(2_000.0, 2_000.0).first, p(2_000.0, 2_000.0).second, 90.0, 5.0, 0L))

        assertEquals(GraphCoasting.Reason.NO_ROAD_NEARBY, result.reason)
    }

    @Test
    fun `крок без довіреного фікса — це NOT_SEEDED, а не нульові координати`() {
        val coast = GraphCoasting(RoadGraph(listOf(eastWest(1L, 200.0))))

        val result = lost(coast.step(distanceM = 10.0, courseDeg = 90.0, atMs = 1_000L))

        assertEquals(GraphCoasting.Reason.NOT_SEEDED, result.reason)
        assertNull("позиції не було й нема чого показувати", result.lostAtLat)
        assertNull(result.lostAtLon)
    }

    @Test
    fun `після «не знаю» лічильник не оживає сам — тільки фіксом`() {
        val coast = GraphCoasting(RoadGraph(listOf(eastWest(1L, 200.0))))
        held(coast.seed(p(0.0, 20.0).first, p(0.0, 20.0).second, courseDeg = 90.0, accuracyM = 5.0, atMs = 0L))
        lost(coast.step(distanceM = 300.0, courseDeg = 90.0, atMs = 5_000L))

        val stillLost = lost(coast.step(distanceM = 10.0, courseDeg = 90.0, atMs = 6_000L))
        assertEquals(GraphCoasting.Reason.PATH_EXHAUSTED, stillLost.reason)

        val revived = held(coast.seed(p(0.0, 50.0).first, p(0.0, 50.0).second, 90.0, 4.0, 7_000L))
        assertEquals(4.0, revived.uncertaintyM, 1e-6)
        assertEquals(0.0, revived.distanceSinceFixM, 1e-6)
    }

    @Test
    fun `невизначеність росте з відстанню і скидається лише фіксом`() {
        val coast = GraphCoasting(RoadGraph(listOf(eastWest(1L, 3_000.0))))
        val start = p(0.0, 0.0)
        val seeded = held(coast.seed(start.first, start.second, courseDeg = 90.0, accuracyM = 5.0, atMs = 0L))
        assertEquals("сам фікс не додає сумніву", 5.0, seeded.uncertaintyM, 1e-6)

        val after500 = held(coast.step(distanceM = 500.0, courseDeg = 90.0, atMs = 60_000L))
        assertEquals(5.0 + 0.18 * 500.0, after500.uncertaintyM, 0.5)

        val after1000 = held(coast.step(distanceM = 500.0, courseDeg = 90.0, atMs = 120_000L))
        assertEquals("сумнів росте, а не тримається", 5.0 + 0.18 * 1000.0, after1000.uncertaintyM, 0.5)
        assertTrue("це числений шлях, а не вимір", after1000.coasted)

        val refixed = held(coast.seed(after1000.lat, after1000.lon, 90.0, 6.0, 130_000L))
        assertEquals("довірений фікс скидає накопичене", 6.0, refixed.uncertaintyM, 1e-6)
        assertTrue("а це вже вимір", !refixed.coasted)
    }

    @Test
    fun `нульовий крок не додає ані метра, ані сумніву`() {
        val coast = GraphCoasting(RoadGraph(listOf(eastWest(1L, 500.0))))
        held(coast.seed(p(0.0, 100.0).first, p(0.0, 100.0).second, courseDeg = 90.0, accuracyM = 5.0, atMs = 0L))

        val stood = held(coast.step(distanceM = 0.0, courseDeg = 90.0, atMs = 30_000L))

        assertEquals(0.0, stood.distanceSinceFixM, 1e-6)
        assertEquals(5.0, stood.uncertaintyM, 1e-6)
        assertEquals(30_000L, stood.sinceFixMs)
    }

    @Test
    fun `розворот за курсом веде назад тією ж дорогою`() {
        val coast = GraphCoasting(RoadGraph(listOf(eastWest(1L, 200.0))))
        val start = p(0.0, 100.0)
        held(coast.seed(start.first, start.second, courseDeg = 90.0, accuracyM = 5.0, atMs = 0L))

        val back = held(coast.step(distanceM = 50.0, courseDeg = 270.0, atMs = 5_000L))

        assertEquals("та сама дорога", 1L, back.way.id)
        assertEquals("50 м назад від 100-го метра", 50.0, back.alongM, 1.0)
        assertTrue("рух мусив піти на захід", back.lon < start.second)
        assertEquals("перехресть тут немає", 0, back.junctionsPassed)
        assertTrue("вбік з дороги не зійшли", abs(back.lat - start.first) * M_PER_DEG_LAT < 1.0)
    }
}
