package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.nav.RoadProfile.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ЩО САМЕ ТУТ ДОВОДИТЬСЯ — що велосипедний профіль ДОСЯЖНИЙ, а не написаний.
 *
 * Це головне. [RoadProfile] умів рівно два профілі, бо питав `foot: Boolean`,
 * і всі три споживачі відповідали йому булем: `AppContainer` віддавав
 * велосипед як АВТО, `EvacuationEngine` — як ПІШОГО, `OsrmRoadRouter` про
 * нього не знав. Тобто таблиця, написана для велосипеда, могла б стояти в
 * файлі роками й не виконатись жодного разу — рівно та вада, на яку в цьому
 * дереві вже наступали («написане, але не викликане»).
 *
 * Тому перший тест не про числа, а про РОЗВИЛКУ: один і той самий граф мусить
 * дати ТРИ різні відповіді. Якщо профіль десь знову згорнеться в буль,
 * впаде саме він.
 *
 * Числа тегів у коментарях — перепис національного пакета
 * `phantom_road_mesh.ukraine-v4.db`, 2 597 639 унікальних доріг.
 */
class BikeIsNotACarTest {

    private val lat = 50.4500
    private val lon0 = 30.5000
    private val lon1 = 30.5600
    private val northLat = lat + 0.003 // ~334 м гака в кожен бік

    /** Пряма дорога між кінцями — первинна вулиця з потоком. */
    private fun straight(tags: Map<String, String>) = RoadWay(
        id = 10,
        points = listOf(lat to lon0, lat to lon1),
        tags = tags,
    )

    /** Довший обʼїзд тими самими кінцями — велодоріжка. */
    private fun detour(tags: Map<String, String>) = RoadWay(
        id = 11,
        points = listOf(
            lat to lon0,
            northLat to lon0,
            northLat to lon1,
            lat to lon1,
        ),
        tags = tags,
    )

    private fun route(ways: List<RoadWay>, mode: TravelMode) = LocalRouter().route(
        graph = RoadGraph(ways),
        fromLat = lat, fromLon = lon0,
        toLat = lat, toLon = lon1,
        mode = mode,
    )

    private fun tookTheCycleway(points: List<Pair<Double, Double>>) =
        points.any { it.first > lat + 0.001 }

    // ------------------------------------------------------------------
    // ДОСЯЖНІСТЬ
    // ------------------------------------------------------------------

    @Test
    fun `one graph, three answers — the bike profile is actually reached`() {
        val ways = listOf(
            straight(mapOf("highway" to "primary")),
            // 2 664 велодоріжки в країні, і всі вони лежали в
            // CAR_FORBIDDEN_CLASSES, тобто коштували велосипеду 500×.
            detour(mapOf("highway" to "cycleway")),
        )

        val byBike = route(ways, TravelMode.BIKE)
        assertNotNull("велосипед мусить дістатись", byBike)
        assertTrue(
            "велосипед знову обʼїхав велодоріжку: ${byBike!!.points}",
            tookTheCycleway(byBike.points),
        )

        val byCar = route(ways, TravelMode.CAR)
        assertNotNull(byCar)
        assertFalse(
            "авто поїхало велодоріжкою: ${byCar!!.points}",
            tookTheCycleway(byCar.points),
        )

        // І саме це було неможливо до цієї хвилі: булевий прапорець не мав
        // третього значення, тож велосипед НЕ МІГ відповісти інакше, ніж авто.
        assertTrue(
            "велосипед і авто дали однаковий шлях — профіль згорнувся в буль",
            byBike.points != byCar.points,
        )
    }

    @Test
    fun `bicycle=no is finally read — 9 289 ways in the pack`() {
        val ways = listOf(
            straight(mapOf("highway" to "primary")),
            detour(mapOf("highway" to "cycleway", "bicycle" to "no")),
        )
        val byBike = route(ways, TravelMode.BIKE)
        assertNotNull(byBike)
        assertFalse(
            "маршрут повів туди, куди велосипедом заборонено: ${byBike!!.points}",
            tookTheCycleway(byBike.points),
        )
    }

    // ------------------------------------------------------------------
    // ЗАБОРОНИ
    // ------------------------------------------------------------------

    @Test
    fun `a cycleway is forbidden to a car and open to a bike`() {
        val t = mapOf("highway" to "cycleway")
        assertTrue(RoadProfile.blocked(t, TravelMode.CAR))
        assertFalse(RoadProfile.blocked(t, TravelMode.BIKE))
        assertFalse(RoadProfile.blocked(t, TravelMode.FOOT))
    }

    @Test
    fun `use_sidepath forbids the bike and nobody else`() {
        // 154 дороги. Значення каже буквально «тут їхати не можна, є окрема
        // велодоріжка» — це заборона, а не незручність.
        val t = mapOf("highway" to "primary", "bicycle" to "use_sidepath")
        assertTrue(RoadProfile.blocked(t, TravelMode.BIKE))
        assertFalse(RoadProfile.blocked(t, TravelMode.CAR))
        assertFalse(RoadProfile.blocked(t, TravelMode.FOOT))
        // А пішохідний `use_sidepath` (63 дороги) свідомо лишається дозволом.
        assertFalse(
            RoadProfile.blocked(mapOf("highway" to "primary", "foot" to "use_sidepath"), TravelMode.FOOT),
        )
    }

    @Test
    fun `steps are an expense to a bike, not a wall`() {
        // 25 109 обʼєктів `steps`. Заборона розривала б мережу там, де людина
        // просто зносить велосипед руками через пішохідний міст.
        assertFalse(RoadProfile.blocked(mapOf("highway" to "steps"), TravelMode.BIKE))
        assertTrue(RoadProfile.blocked(mapOf("highway" to "steps"), TravelMode.CAR))
        assertTrue(
            "сходи мусять коштувати велосипеду більше за будь-яку вулицю",
            RoadProfile.costFactor(mapOf("highway" to "steps"), TravelMode.BIKE) >
                RoadProfile.costFactor(mapOf("highway" to "trunk"), TravelMode.BIKE),
        )
    }

    @Test
    fun `impassable is the mapper's word, and it stops wheels only`() {
        // 667 доріг. Заборону написали не ми — її написав картограф.
        val t = mapOf("highway" to "track", "smoothness" to "impassable")
        assertTrue(RoadProfile.blocked(t, TravelMode.CAR))
        assertTrue(RoadProfile.blocked(t, TravelMode.BIKE))
        assertFalse("пішохід проходить те, чим не проїдеш", RoadProfile.blocked(t, TravelMode.FOOT))
    }

    @Test
    fun `motor_vehicle=no does not close a street to a bicycle`() {
        // Пішохідна зона з `motor_vehicle=no`: авто не можна, велосипеду можна.
        val t = mapOf("highway" to "residential", "motor_vehicle" to "no")
        assertTrue(RoadProfile.blocked(t, TravelMode.CAR))
        assertFalse(RoadProfile.blocked(t, TravelMode.BIKE))
    }

    @Test
    fun `vehicle=no closes the street to the bicycle too — it is a vehicle`() {
        val t = mapOf("highway" to "residential", "vehicle" to "no")
        assertTrue(RoadProfile.blocked(t, TravelMode.BIKE))
        assertTrue(RoadProfile.blocked(t, TravelMode.CAR))
        assertFalse(RoadProfile.blocked(t, TravelMode.FOOT))
    }

    // ------------------------------------------------------------------
    // ВАРТІСТЬ
    // ------------------------------------------------------------------

    @Test
    fun `the bike ladder never dips below one — A star stays admissible`() {
        // [RoadGraph.routeTraced] однаково затискає множник до ≥ 1; таблиця,
        // що обіцяє знижку, обіцяла б те, чого маршрутизатор не виконує.
        val classes = listOf(
            "cycleway", "path", "track", "residential", "living_street", "unclassified",
            "tertiary", "secondary", "primary", "trunk", "footway", "pedestrian",
            "steps", "service", "yes", "road",
        )
        for (h in classes) {
            for (b in listOf(null, "yes", "designated", "dismount", "permissive")) {
                val tags = buildMap {
                    put("highway", h)
                    if (b != null) put("bicycle", b)
                }
                val f = RoadProfile.costFactor(tags, TravelMode.BIKE)
                assertTrue("$tags дало $f", f >= 1.0)
            }
        }
    }

    @Test
    fun `a cycleway is the bike's yardstick and a trunk is its worst road`() {
        val cycle = RoadProfile.costFactor(mapOf("highway" to "cycleway"), TravelMode.BIKE)
        val street = RoadProfile.costFactor(mapOf("highway" to "residential"), TravelMode.BIKE)
        val primary = RoadProfile.costFactor(mapOf("highway" to "primary"), TravelMode.BIKE)
        val trunk = RoadProfile.costFactor(mapOf("highway" to "trunk"), TravelMode.BIKE)
        assertEquals(1.0, cycle, 1e-9)
        assertTrue(cycle < street)
        assertTrue(street < primary)
        assertTrue(primary < trunk)
    }

    @Test
    fun `a pavement is pushed, not ridden — and the price is two speeds, not a taste`() {
        // 310 724 тротуари в країні (12% усієї мережі), і `bicycle` дозволяє
        // їхати лише на 8 810 з них. Заборона розірвала б мережу в містах
        // заради правила, яке людина обходить, зійшовши з сідла.
        val pavement = mapOf("highway" to "footway")
        val expected = LocalRouter.BIKE_SPEED_MPS / LocalRouter.FOOT_SPEED_MPS
        assertEquals(expected, RoadProfile.costFactor(pavement, TravelMode.BIKE), 1e-9)
        assertEquals(
            "по тротуару велосипед ВЕДУТЬ, а не їдуть",
            LocalRouter.FOOT_SPEED_MPS,
            RoadProfile.speedMps(pavement, TravelMode.BIKE),
            1e-9,
        )
        // Дозволений тротуар — це звичайна місцева дорога, і на ній їдуть.
        val allowed = pavement + ("bicycle" to "yes")
        assertTrue(RoadProfile.costFactor(allowed, TravelMode.BIKE) < RoadProfile.costFactor(pavement, TravelMode.BIKE))
        assertTrue(RoadProfile.speedMps(allowed, TravelMode.BIKE) > LocalRouter.FOOT_SPEED_MPS)
        // `bicycle=dismount` (1 466) — той самий стан, сказаний прямо.
        assertEquals(
            expected,
            RoadProfile.costFactor(mapOf("highway" to "residential", "bicycle" to "dismount"), TravelMode.BIKE),
            1e-9,
        )
        // Сходи несуть завжди — навіть якщо тег каже «велошлях».
        assertEquals(
            RoadProfile.costFactor(mapOf("highway" to "steps"), TravelMode.BIKE),
            RoadProfile.costFactor(mapOf("highway" to "steps", "bicycle" to "designated"), TravelMode.BIKE),
            1e-9,
        )
    }

    @Test
    fun `designated cycle infrastructure counts wherever it is painted`() {
        // 2 030 доріг. `designated` на звичайній вулиці — це оголошений
        // велошлях, і він мусить коштувати як велодоріжка.
        assertEquals(
            RoadProfile.costFactor(mapOf("highway" to "cycleway"), TravelMode.BIKE),
            RoadProfile.costFactor(
                mapOf("highway" to "secondary", "bicycle" to "designated"), TravelMode.BIKE,
            ),
            1e-9,
        )
        // А `bicycle=yes` (29 075) НЕ дає знижки: дозвіл — не комфорт.
        assertTrue(
            RoadProfile.costFactor(mapOf("highway" to "secondary", "bicycle" to "yes"), TravelMode.BIKE) >
                RoadProfile.costFactor(mapOf("highway" to "cycleway"), TravelMode.BIKE),
        )
    }

    // ------------------------------------------------------------------
    // ҐРУНТ І ГЛАДКІСТЬ
    // ------------------------------------------------------------------

    @Test
    fun `the old rough-surface numbers did not move`() {
        // Сім із девʼяти членів старого ROUGH_SURFACES: ряд LOOSE складено з
        // тих самих констант, тож жоден уже правильний маршрут не переставлено.
        val unchanged = listOf("ground", "dirt", "earth", "grass", "gravel", "pebblestone", "unpaved")
        for (s in unchanged) {
            assertEquals("$s авто", 1.8, RoadProfile.costFactor(mapOf("surface" to s), TravelMode.CAR), 1e-9)
            assertEquals("$s пішки", 1.35, RoadProfile.costFactor(mapOf("surface" to s), TravelMode.FOOT), 1e-9)
        }
        // sand (11 859) і mud (850) переїхали в SOFT навмисно — пісок і болото
        // не те саме, що жорства.
        for (s in listOf("sand", "mud")) {
            assertTrue(RoadProfile.costFactor(mapOf("surface" to s), TravelMode.CAR) > 1.8)
            assertTrue(RoadProfile.costFactor(mapOf("surface" to s), TravelMode.FOOT) > 1.35)
        }
    }

    @Test
    fun `cobbles are a nuisance to a car and the worst paved thing under a bike`() {
        // paving_stones 33 044 · sett 5 270 · cobblestone 1 702 — усе це
        // коштувало рівно нічого, бо не входило в старий набір.
        for (s in listOf("paving_stones", "sett", "cobblestone")) {
            val car = RoadProfile.costFactor(mapOf("surface" to s), TravelMode.CAR)
            val bike = RoadProfile.costFactor(mapOf("surface" to s), TravelMode.BIKE)
            assertTrue("$s: авто $car", car > 1.0)
            assertTrue("$s: велосипед $bike мусить бути дорожчим за авто $car", bike > car)
        }
        // compacted 10 813 — не асфальт, і більше не безкоштовно.
        assertTrue(RoadProfile.costFactor(mapOf("surface" to "compacted"), TravelMode.CAR) > 1.0)
        // Асфальт лишається еталоном для всіх трьох.
        for (m in TravelMode.values()) {
            assertEquals(1.0, RoadProfile.costFactor(mapOf("surface" to "asphalt"), m), 1e-9)
        }
    }

    @Test
    fun `smoothness finally costs something — 118 047 ways carried it in silence`() {
        // Ключ стоїть на БІЛЬШІЙ кількості доріг, ніж maxspeed (123 590 — той
        // самий порядок), лежав у ROUTING_KEYS і не читався ніде.
        val plain = mapOf("highway" to "residential", "surface" to "asphalt")
        val rungs = listOf("excellent", "good", "intermediate", "bad", "very_bad", "horrible", "very_horrible")
        var prev = 0.0
        for (s in rungs) {
            val f = RoadProfile.costFactor(plain + ("smoothness" to s), TravelMode.CAR)
            assertTrue("$s ($f) мусить коштувати не менше за попередній ($prev)", f >= prev)
            prev = f
        }
        assertTrue("розбитий асфальт мусить коштувати дорожче за цілий", prev > 1.0)
        // 15 002 дороги мають very_bad або гірше — саме їх маршрутизатор
        // пропонував нарівні зі свіжим асфальтом.
        assertTrue(
            RoadProfile.costFactor(plain + ("smoothness" to "very_bad"), TravelMode.CAR) >
                RoadProfile.costFactor(plain, TravelMode.CAR),
        )
        // Яма коштує велосипеду дорожче, ніж авто, і пішоходу — найменше.
        // Порівнюються саме ВІДНОШЕННЯ на тій самій дорозі, а не підсумки:
        // підсумок несе ще й щабель класу, а щаблі в профілів різні, тож
        // порівняння підсумків міряло б не яму, а перевагу дороги.
        val bad = plain + ("smoothness" to "very_bad")
        fun pothole(m: TravelMode) =
            RoadProfile.costFactor(bad, m) / RoadProfile.costFactor(plain, m)
        assertTrue(
            "велосипед ${pothole(TravelMode.BIKE)} проти авто ${pothole(TravelMode.CAR)}",
            pothole(TravelMode.BIKE) > pothole(TravelMode.CAR),
        )
        assertTrue(
            "пішохід ${pothole(TravelMode.FOOT)} проти авто ${pothole(TravelMode.CAR)}",
            pothole(TravelMode.FOOT) < pothole(TravelMode.CAR),
        )
    }

    // ------------------------------------------------------------------
    // ЧАС І ПІДСТАВА
    // ------------------------------------------------------------------

    @Test
    fun `a car's posted limit is never a bicycle's basis`() {
        // Це і був найтихіший наслідок булевого прапорця: велосипедна
        // тривалість рахувалась зі стелі для АВТО і оголошувала себе POSTED —
        // «за знаками».
        val trunk = mapOf("highway" to "trunk", "maxspeed" to "90")
        assertEquals(SpeedBasisOf(trunk, TravelMode.CAR), RoadProfile.SpeedBasis.POSTED)
        assertTrue(
            "велосипед не їде трасою 63 км/год",
            RoadProfile.speedMps(trunk, TravelMode.BIKE) < RoadProfile.speedMps(trunk, TravelMode.CAR),
        )
        assertEquals(
            "90 км/год — не підстава для велосипеда",
            RoadProfile.SpeedBasis.ASSUMED,
            SpeedBasisOf(trunk, TravelMode.BIKE),
        )
    }

    @Test
    fun `a yard sign of 5 kmh binds the bicycle too — and says so`() {
        // maxspeed=5 стоїть на 3 225 дорогах. Велосипед — транспортний засіб,
        // і знак стосується його; тоді підстава чесно POSTED, бо число
        // вирішив саме знак.
        val yard = mapOf("highway" to "service", "maxspeed" to "5")
        assertEquals(RoadProfile.SpeedBasis.POSTED, SpeedBasisOf(yard, TravelMode.BIKE))
        assertEquals(5 * 1000.0 / 3600.0, RoadProfile.speedMps(yard, TravelMode.BIKE), 1e-9)
    }

    @Test
    fun `an untagged way keeps the migration promise for the bike as well`() {
        // Та сама обіцянка, що її вже тримають два інші профілі: дорога, про
        // яку не відомо нічого, дістає рівно пласке число і зветься здогадом.
        for (tags in listOf(emptyMap(), mapOf("surface" to "asphalt"))) {
            assertEquals(
                "$tags",
                LocalRouter.BIKE_SPEED_MPS,
                RoadProfile.speedMps(tags, TravelMode.BIKE),
                1e-12,
            )
            assertEquals("$tags", RoadProfile.SpeedBasis.ASSUMED, SpeedBasisOf(tags, TravelMode.BIKE))
        }
    }

    @Test
    fun `ground and smoothness slow the bike, and never below a push`() {
        val base = RoadProfile.speedMps(emptyMap(), TravelMode.BIKE)
        val cobbles = RoadProfile.speedMps(mapOf("surface" to "sett"), TravelMode.BIKE)
        assertTrue("бруківка мусить бути повільнішою за асфальт", cobbles < base)
        assertEquals(RoadProfile.SpeedBasis.CLASS, SpeedBasisOf(mapOf("surface" to "sett"), TravelMode.BIKE))
        // Велосипед завжди можна штовхати, тож темп не падає нижче пішохідного.
        val worst = mapOf("surface" to "mud", "smoothness" to "very_horrible")
        assertTrue(
            RoadProfile.speedMps(worst, TravelMode.BIKE) >= LocalRouter.FOOT_SPEED_MPS,
        )
        // Сходи — велосипед несуть, і не швидше за пішохода по сходах.
        assertTrue(
            RoadProfile.speedMps(mapOf("highway" to "steps"), TravelMode.BIKE) <=
                RoadProfile.speedMps(mapOf("highway" to "steps"), TravelMode.FOOT),
        )
    }

    // ------------------------------------------------------------------
    // СУМІСНІСТЬ
    // ------------------------------------------------------------------

    @Test
    fun `the boolean form still means exactly what it meant`() {
        // Обіцянка, на якій тримається вся ця хвиля: жоден наявний виклик і
        // жоден наявний тест не змінює поведінки.
        val matrix = listOf(
            emptyMap(),
            mapOf("highway" to "residential"),
            mapOf("highway" to "trunk", "maxspeed" to "90"),
            mapOf("highway" to "service", "service" to "driveway"),
            mapOf("highway" to "steps"),
            mapOf("highway" to "cycleway"),
            mapOf("highway" to "track", "surface" to "gravel"),
            mapOf("highway" to "primary", "access" to "private"),
        )
        for (tags in matrix) {
            for ((foot, mode) in listOf(true to TravelMode.FOOT, false to TravelMode.CAR)) {
                assertEquals("$tags foot=$foot", RoadProfile.blocked(tags, foot), RoadProfile.blocked(tags, mode))
                assertEquals(
                    "$tags foot=$foot",
                    RoadProfile.costFactor(tags, foot),
                    RoadProfile.costFactor(tags, mode),
                    1e-12,
                )
                assertEquals(
                    "$tags foot=$foot",
                    RoadProfile.speedMps(tags, foot),
                    RoadProfile.speedMps(tags, mode),
                    1e-12,
                )
                assertEquals("$tags foot=$foot", RoadProfile.speedBasis(tags, foot), RoadProfile.speedBasis(tags, mode))
            }
        }
    }

    @Test
    fun `the trunk shoulder is measured for the rider too`() {
        // Вантажівка, що проходить у метрі, не питає, на чому ти їдеш.
        val shoulder = listOf(
            RoadWay(id = 20, points = listOf(lat to lon0, lat to lon1), tags = mapOf("highway" to "trunk")),
        )
        val byBike = route(shoulder, TravelMode.BIKE)
        assertNotNull(byBike)
        assertTrue("узбіччя не порахували велосипеду", byBike!!.trunkShoulderMeters > 0.0)
        assertTrue(byBike.walksTrunkShoulder)
        // Для авто траса — це просто дорога.
        assertEquals(0.0, route(shoulder, TravelMode.CAR)!!.trunkShoulderMeters, 1e-9)
    }

    private fun SpeedBasisOf(tags: Map<String, String>, mode: TravelMode) =
        RoadProfile.speedBasis(tags, mode)
}
