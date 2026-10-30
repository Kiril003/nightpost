package local.phantom.companion.core.geo.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceGazetteerTest {

    @Test
    fun `table parses and every row is a usable box`() {
        val places = PlaceGazetteer.places
        assertTrue("gazetteer is empty — the table stopped parsing", places.size > 100)
        places.forEach { p ->
            assertTrue("${p.id} has an inverted box", p.valid)
            assertTrue("${p.id} is outside Ukraine's latitudes", p.southLat > 43.0 && p.northLat < 53.0)
            assertTrue("${p.id} is outside Ukraine's longitudes", p.westLon > 21.0 && p.eastLon < 41.0)
        }
    }

    @Test
    fun `ids are unique`() {
        val ids = PlaceGazetteer.places.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `the three the owner named are all findable`() {
        listOf("Київ", "Житомирська", "Україна").forEach { q ->
            assertTrue("«$q» finds nothing", PlaceGazetteer.search(q).isNotEmpty())
        }
    }

    @Test
    fun `apostrophe spellings do not hide a city`() {
        // «Міжгірʼя» is spelled with four different apostrophes across sources
        // and a fifth on the phone keyboard.
        val direct = PlaceGazetteer.places.filter { it.nameUa.contains("Міжгір") }
        if (direct.isNotEmpty()) {
            assertTrue(PlaceGazetteer.search("міжгірʼя").isNotEmpty())
            assertTrue(PlaceGazetteer.search("міжгірʼя").isNotEmpty())
        }
    }

    /**
     * Міста, чия область НЕ містить їх географічно. Славутич збудували після
     * Чорнобиля для працівників станції на землі Чернігівщини, а підпорядкували
     * Київщині — підпорядкування й вкладеність тут розходяться насправді, а не
     * через похибку рамки. Викидати місто означало б стерти його з мапи для
     * тих, хто там живе.
     */
    private val DETACHED = setOf("Славутич")

    @Test
    fun `every raion and city sits inside its own oblast box`() {
        val oblasts = PlaceGazetteer.places
            .filter { it.kind == PlaceKind.Oblast }
            .associateBy { it.nameUa }
        val children = PlaceGazetteer.places.filter { it.parentUa != null }
        assertTrue("no children in the table at all", children.size > 100)
        children.forEach { c ->
            val parent = oblasts[c.parentUa]
            assertNotNull("${c.id} points at an oblast that is not in the table", parent)
            parent!!
            if (c.nameUa in DETACHED) return@forEach
            val cLat = (c.northLat + c.southLat) / 2
            val cLon = (c.eastLon + c.westLon) / 2
            assertTrue("${c.nameUa} sits outside ${parent.nameUa}", parent.contains(cLat, cLon))
        }
    }

    @Test
    fun `the country box holds every oblast`() {
        val country = PlaceGazetteer.country
        assertNotNull(country)
        PlaceGazetteer.places.filter { it.kind == PlaceKind.Oblast }.forEach { o ->
            assertTrue(
                "${o.nameUa} sticks out of the country box",
                o.northLat <= country!!.northLat && o.southLat >= country.southLat &&
                    o.eastLon <= country.eastLon && o.westLon >= country.westLon,
            )
        }
    }

    @Test
    fun `a stated land area is a polygon area, never the box it sits in`() {
        // Nominatim hands back a box, not a polygon. Printing the box's own area
        // as the place's area would be a number nobody measured — so rows built
        // from a box carry null, and rows built from a polygon must come out
        // SMALLER than their box.
        PlaceGazetteer.places.forEach { p ->
            val land = p.landAreaKm2 ?: return@forEach
            assertTrue("${p.id} has a non-positive land area", land > 0.0)
            assertTrue(
                "${p.id} claims $land km² inside a ${p.boxAreaKm2} km² box",
                land <= p.boxAreaKm2,
            )
        }
        assertTrue(
            "no raion should carry a land area — none of them came from a polygon",
            PlaceGazetteer.places.none { it.kind == PlaceKind.Raion && it.landAreaKm2 != null },
        )
    }

    @Test
    fun `an empty query still offers something to tap`() {
        val head = PlaceGazetteer.search("")
        assertTrue(head.isNotEmpty())
        assertEquals(PlaceKind.Country, head.first().kind)
    }

    /**
     * Столиці — та сама перевірка, якою рядки [WORLD_TABLE] відбиралися, тільки
     * тепер вона проганяється на кожній збірці, а не один раз у скрипті.
     * Координати зняті ОКРЕМИМ запитом до Nominatim: столиця поза рамкою
     * означає, що рамка не та. У APK цей список не їде — це тест.
     */
    private data class Capital(val nameUa: String, val lat: Double, val lon: Double)

    private val CAPITALS: Map<String, Capital> = mapOf(
        "w-ad" to Capital("Andorra la Vella", 42.4979, 1.5032),
        "w-ae" to Capital("Абу-Дабі", 24.4538, 54.3774),
        "w-af" to Capital("Кабул", 34.5270, 69.1851),
        "w-ag" to Capital("Сент-Джонс", 17.1185, -61.8449),
        "w-al" to Capital("Тирана", 41.3281, 19.8184),
        "w-am" to Capital("Єреван", 40.1777, 44.5126),
        "w-ao" to Capital("Луанда", -8.8273, 13.2440),
        "w-ar" to Capital("Буенос-Айрес", -34.6096, -58.3888),
        "w-at" to Capital("Відень", 48.2084, 16.3725),
        "w-au" to Capital("Канберра", -35.2976, 149.1013),
        "w-az" to Capital("Баку", 40.3756, 49.8328),
        "w-ba" to Capital("Сараєво", 43.8571, 18.4126),
        "w-bb" to Capital("Бриджтаун", 13.0978, -59.6184),
        "w-bd" to Capital("Дакка", 23.7644, 90.3890),
        "w-be" to Capital("Брюссель", 50.8467, 4.3525),
        "w-bf" to Capital("Уагадугу", 12.3682, -1.5271),
        "w-bg" to Capital("Софія", 42.6977, 23.3217),
        "w-bh" to Capital("Манама", 26.2235, 50.5822),
        "w-bi" to Capital("Гітега", -3.4285, 29.9250),
        "w-bj" to Capital("Порто-Ново", 6.4991, 2.6253),
        "w-bn" to Capital("Бандар-Сері-Бегаван", 4.8895, 114.9418),
        "w-bo" to Capital("Сукре", -19.0477, -65.2594),
        "w-br" to Capital("Бразилія", -15.7940, -47.8828),
        "w-bs" to Capital("Нассау", 25.0782, -77.3383),
        "w-bt" to Capital("Тхімпху", 27.4714, 89.6337),
        "w-bw" to Capital("Габороне", -24.6581, 25.9088),
        "w-by" to Capital("Мінськ", 53.9025, 27.5618),
        "w-bz" to Capital("Бельмопан", 17.2502, -88.7700),
        "w-ca" to Capital("Оттава", 45.4209, -75.6901),
        "w-cd" to Capital("Кіншаса", -4.3217, 15.3123),
        "w-cf" to Capital("Бангі", 4.3635, 18.5836),
        "w-cg" to Capital("Браззавіль", -4.2694, 15.2712),
        "w-ch" to Capital("Берн", 46.9485, 7.4522),
        "w-ci" to Capital("Ямусукро", 6.8200, -5.2776),
        "w-cl" to Capital("Сантьяго-де-Чилі", -33.4377, -70.6511),
        "w-cm" to Capital("Яунде", 3.8690, 11.5213),
        "w-cn" to Capital("Пекін", 39.9057, 116.3913),
        "w-co" to Capital("Богота", 4.6534, -74.0836),
        "w-cr" to Capital("Сан-Хосе", 9.9328, -84.0796),
        "w-cu" to Capital("Гавана", 23.1353, -82.3590),
        "w-cv" to Capital("Прая", 14.9163, -23.5095),
        "w-cy" to Capital("Нікосія", 35.1747, 33.3639),
        "w-cz" to Capital("Прага", 50.0875, 14.4213),
        "w-de" to Capital("Берлін", 52.5174, 13.3951),
        "w-dj" to Capital("Джибуті", 11.5937, 43.1473),
        "w-dk" to Capital("Копенгаген", 55.6867, 12.5701),
        "w-dm" to Capital("Розо", 15.2992, -61.3873),
        "w-do" to Capital("Санто-Домінго", 18.4714, -69.8918),
        "w-dz" to Capital("Алжир", 36.7729, 3.0588),
        "w-ec" to Capital("Кіто", -0.2202, -78.5123),
        "w-ee" to Capital("Таллінн", 59.4372, 24.7573),
        "w-eg" to Capital("Каїр", 30.0444, 31.2357),
        "w-er" to Capital("Асмара", 15.3390, 38.9327),
        "w-es" to Capital("Мадрид", 40.4168, -3.7035),
        "w-et" to Capital("Аддис-Абеба", 9.0358, 38.7524),
        "w-fi" to Capital("Гельсінкі", 60.1666, 24.9435),
        "w-fj" to Capital("Сува", -18.1416, 178.4422),
        "w-fm" to Capital("Палікір", 6.9207, 158.1627),
        "w-fr" to Capital("Париж", 48.8535, 2.3484),
        "w-ga" to Capital("Лібревіль", 0.4087, 9.4419),
        "w-gb" to Capital("Великий Лондон", 51.5074, -0.1278),
        "w-gd" to Capital("Сент-Джорджес", 12.0535, -61.7518),
        "w-ge" to Capital("Тбілісі", 41.6935, 44.8014),
        "w-gh" to Capital("Аккра", 5.5571, -0.2012),
        "w-gm" to Capital("Banjul", 13.4410, -16.5628),
        "w-gn" to Capital("Конакрі", 9.5171, -13.6998),
        "w-gq" to Capital("Малабо", 3.7419, 8.7741),
        "w-gr" to Capital("Афіни", 37.9756, 23.7348),
        "w-gt" to Capital("Гватемала", 14.6416, -90.5133),
        "w-gw" to Capital("Бісау", 11.8613, -15.5831),
        "w-gy" to Capital("Джорджтаун", 6.8033, -58.1455),
        "w-hn" to Capital("Тегусігальпа", 14.1058, -87.2047),
        "w-hr" to Capital("Загреб", 45.8131, 15.9773),
        "w-ht" to Capital("Порт-о-Пренс", 18.5473, -72.3396),
        "w-hu" to Capital("Будапешт", 47.4979, 19.0402),
        "w-id" to Capital("Джакарта", -6.1754, 106.8272),
        "w-ie" to Capital("Дублін", 53.3494, -6.2606),
        "w-il" to Capital("Єрусалим", 31.7788, 35.2258),
        "w-in" to Capital("Нью-Делі", 28.6139, 77.2090),
        "w-iq" to Capital("Багдад", 33.3062, 44.3872),
        "w-ir" to Capital("Тегеран", 35.6893, 51.3896),
        "w-is" to Capital("Рейкʼявік", 64.1460, -21.9422),
        "w-it" to Capital("Рим", 41.8933, 12.4829),
        "w-jm" to Capital("Кінгстон", 17.9712, -76.7928),
        "w-jo" to Capital("Амман", 31.9516, 35.9240),
        "w-jp" to Capital("Префектура Токіо", 35.6769, 139.7639),
        "w-ke" to Capital("Найробі", -1.2890, 36.8173),
        "w-kg" to Capital("Бішкек", 42.8761, 74.6037),
        "w-kh" to Capital("Пномпень", 11.5683, 104.9224),
        "w-ki" to Capital("Тарава", 1.4846, 172.9690),
        "w-km" to Capital("Мороні", -11.7040, 43.2519),
        "w-kn" to Capital("Бастер", 17.2961, -62.7223),
        "w-kp" to Capital("Пхеньян", 39.0168, 125.7474),
        "w-kr" to Capital("Сеул", 37.5667, 126.9783),
        "w-kw" to Capital("Ель-Кувейт", 29.3797, 47.9734),
        "w-kz" to Capital("Астана", 51.1283, 71.4305),
        "w-la" to Capital("Вʼєнтьян", 17.9641, 102.6134),
        "w-lb" to Capital("Бейрут", 33.8959, 35.4784),
        "w-lc" to Capital("Кастрі", 14.0096, -60.9902),
        "w-li" to Capital("Вадуц", 47.1393, 9.5228),
        "w-lk" to Capital("Sri Lanka Family Stay", 6.8979, 79.9048),
        "w-lr" to Capital("Монровія", 6.3204, -10.8060),
        "w-ls" to Capital("Масеру", -29.3101, 27.4782),
        "w-lt" to Capital("Вільнюс", 54.6870, 25.2829),
        "w-lu" to Capital("Люксембург", 49.6113, 6.1298),
        "w-lv" to Capital("Рига", 56.9494, 24.1052),
        "w-ly" to Capital("Триполі", 32.8967, 13.1778),
        "w-ma" to Capital("Рабат", 34.0218, -6.8409),
        "w-mc" to Capital("Монако", 43.7310, 7.4248),
        "w-md" to Capital("Кишинэу", 47.0245, 28.8323),
        "w-me" to Capital("Подгориця", 42.4415, 19.2621),
        "w-mg" to Capital("Антананаріву", -18.9100, 47.5256),
        "w-mh" to Capital("Маджуро", 7.0910, 171.3816),
        "w-mk" to Capital("Скопʼє", 41.9962, 21.4319),
        "w-ml" to Capital("Бамако", 12.6493, -8.0003),
        "w-mm" to Capital("Найпʼїдо", 19.7753, 96.1033),
        "w-mn" to Capital("Улан-Батор", 47.9185, 106.9177),
        "w-mr" to Capital("Нуакшот", 18.0792, -15.9780),
        "w-mt" to Capital("Валлетта", 35.8990, 14.5137),
        "w-mu" to Capital("Порт-Луї", -20.1625, 57.5028),
        "w-mv" to Capital("Мале", 4.1780, 73.5107),
        "w-mw" to Capital("Лілонгве", -13.9875, 33.7681),
        "w-mx" to Capital("Мехіко", 19.4326, -99.1332),
        "w-my" to Capital("Куала-Лумпур", 3.1517, 101.6942),
        "w-mz" to Capital("Мапуту", -25.9662, 32.5675),
        "w-na" to Capital("Windhoek", -22.5336, 17.0455),
        "w-ne" to Capital("Ніамей", 13.5248, 2.1098),
        "w-ng" to Capital("Абуджа", 9.0643, 7.4893),
        "w-ni" to Capital("Манагуа", 12.1547, -86.2737),
        "w-nl" to Capital("Амстердам", 52.3731, 4.8925),
        "w-no" to Capital("Осло", 59.9133, 10.7390),
        "w-np" to Capital("Катманду", 27.7083, 85.3206),
        "w-nr" to Capital("Ярен", -0.5471, 166.9163),
        "w-nz" to Capital("Веллінгтон", -41.2888, 174.7772),
        "w-om" to Capital("Маскат", 23.6124, 58.5938),
        "w-pa" to Capital("Панама", 8.9714, -79.5342),
        "w-pe" to Capital("Ліма", -12.0460, -77.0306),
        "w-pg" to Capital("Порт-Морсбі", -9.4743, 147.1600),
        "w-ph" to Capital("Маніла", 14.5904, 120.9804),
        "w-pk" to Capital("Ісламабад", 33.6938, 73.0652),
        "w-pl" to Capital("Варшава", 52.2320, 21.0067),
        "w-pt" to Capital("Лісабон", 38.7078, -9.1366),
        "w-pw" to Capital("Нгерулмуд", 7.5006, 134.6243),
        "w-py" to Capital("Асунсьйон", -25.2800, -57.6344),
        "w-qa" to Capital("Доха", 25.2856, 51.5264),
        "w-ro" to Capital("Бухарест", 44.4361, 26.1027),
        "w-rs" to Capital("Белград", 44.8178, 20.4569),
        "w-ru" to Capital("Москва", 55.7505, 37.6175),
        "w-rw" to Capital("Кігалі", -1.9534, 30.1140),
        "w-sa" to Capital("Ер-Ріяд", 24.6389, 46.7160),
        "w-sb" to Capital("Хоніара", -9.4311, 159.9553),
        "w-sc" to Capital("Вікторія", -4.6232, 55.4524),
        "w-sd" to Capital("Хартум", 15.5636, 32.5349),
        "w-se" to Capital("Стокгольм", 59.3251, 18.0711),
        "w-sg" to Capital("Сінгапур", 1.2899, 103.8519),
        "w-si" to Capital("Любляна", 46.0500, 14.5069),
        "w-sk" to Capital("Братислава", 48.1517, 17.1093),
        "w-sl" to Capital("Фрітаун", 8.4790, -13.2680),
        "w-sm" to Capital("Сан-Марино", 43.9364, 12.4467),
        "w-sn" to Capital("Дакар", 14.6934, -17.4479),
        "w-so" to Capital("Могадішо", 2.0349, 45.3419),
        "w-sr" to Capital("Парамарибо", 5.8248, -55.1704),
        "w-ss" to Capital("Джуба", 4.8459, 31.5959),
        "w-st" to Capital("Сан-Томе", 0.3389, 6.7313),
        "w-sv" to Capital("Сан-Сальвадор", 13.6976, -89.1912),
        "w-sy" to Capital("Дамаск", 33.5131, 36.3096),
        "w-sz" to Capital("Мбабане", -26.3257, 31.1447),
        "w-td" to Capital("Нджамена", 12.1192, 15.0503),
        "w-tg" to Capital("Ломе", 6.1304, 1.2158),
        "w-th" to Capital("Бангкок", 13.7525, 100.4935),
        "w-tj" to Capital("Душанбе", 38.5767, 68.7854),
        "w-tl" to Capital("Ділі", -8.5537, 125.5784),
        "w-tm" to Capital("Ашгабат", 37.9378, 58.2359),
        "w-tn" to Capital("Туніс", 36.8002, 10.1858),
        "w-to" to Capital("Нукуʼалофа", -21.1343, -175.2018),
        "w-tr" to Capital("Анкара", 39.9208, 32.8540),
        "w-tt" to Capital("Порт-оф-Спейн", 10.6573, -61.5180),
        "w-tv" to Capital("Фунафуті", -8.5200, 179.1983),
        "w-tw" to Capital("Тайбей", 25.0375, 121.5637),
        "w-tz" to Capital("Додома", -6.1791, 35.7468),
        "w-ua" to Capital("Київ", 50.4500, 30.5241),
        "w-ug" to Capital("Кампала", 0.3177, 32.5814),
        "w-us" to Capital("Вашингтон", 38.8951, -77.0364),
        "w-uy" to Capital("Монтевідео", -34.9059, -56.1913),
        "w-uz" to Capital("Ташкент", 41.3123, 69.2787),
        "w-va" to Capital("Ватикан", 41.9035, 12.4528),
        "w-vc" to Capital("Кінгстаун", 13.1562, -61.2280),
        "w-ve" to Capital("Каракас", 10.5061, -66.9146),
        "w-vn" to Capital("Ханой", 21.0283, 105.8540),
        "w-vu" to Capital("Порт-Віла", -17.7415, 168.3150),
        "w-ws" to Capital("Апіа", -13.8345, -171.7631),
        "w-xk" to Capital("Приштина", 42.6639, 21.1641),
        "w-ye" to Capital("Сана", 15.3539, 44.2059),
        "w-za" to Capital("Преторія", -25.7459, 28.1879),
        "w-zm" to Capital("Лусака", -15.4163, 28.2818),
        "w-zw" to Capital("Хараре", -17.8318, 31.0457),
    )

    @Test
    fun `every country holds its own capital`() {
        val world = PlaceGazetteer.world
        assertTrue("the world table stopped parsing", world.size > 180)
        world.forEach { c ->
            val cap = CAPITALS[c.id]
            assertNotNull("${c.nameUa} (${c.id}) has no capital to check against", cap)
            cap!!
            assertTrue(
                "${c.nameUa}: ${cap.nameUa} at ${cap.lat},${cap.lon} falls outside " +
                    "${c.southLat}..${c.northLat} / ${c.westLon}..${c.eastLon}",
                c.contains(cap.lat, cap.lon),
            )
        }
    }

    @Test
    fun `every world row is a usable country box`() {
        PlaceGazetteer.world.forEach { c ->
            assertTrue("${c.id} has an inverted box", c.valid)
            assertEquals(PlaceKind.Country, c.kind)
            assertTrue("${c.id} carries a parent", c.parentUa == null)
            assertTrue("${c.id} has no Ukrainian name", c.nameUa.isNotBlank())
            assertTrue("${c.id} has no English name", c.nameEn.isNotBlank())
            assertTrue("${c.id} is not prefixed as a world row", PlaceGazetteer.isWorldRow(c))
        }
    }

    @Test
    fun `own country is not listed twice`() {
        // Україна живе в PLACE_TABLE з рамкою з полігонів і 314 місцями під
        // нею. Її рядок зі світової таблиці мусить бути прибраний, інакше в
        // списку два різні числа під однією назвою.
        assertTrue(PlaceGazetteer.world.none { it.nameUa == PlaceGazetteer.country?.nameUa })
        assertEquals(1, PlaceGazetteer.countries().count { it.nameUa == "Україна" })
        assertEquals("Україна", PlaceGazetteer.countries().first().nameUa)
    }

    @Test
    fun `the country under the fix comes first`() {
        // Варшава.
        assertEquals("Польща", PlaceGazetteer.countries(52.23, 21.01).first().nameUa)
    }

    @Test
    fun `no world box spans the whole planet`() {
        // Рамка від -180 до 180 — це не країна, це відмова від пакета: планер
        // не запропонує жодної глибини, і держава стане недоступною.
        PlaceGazetteer.world.forEach { c ->
            assertTrue(
                "${c.nameUa} spans ${c.eastLon - c.westLon}° of longitude",
                c.eastLon - c.westLon < 180.0,
            )
        }
    }

    @Test
    fun `the mainland-framed list names real countries and nothing else`() {
        // Список звужених рамок не має права рости мовчки: кожен його рядок —
        // це заявка «рамка не така, як у межі OSM», і вона мусить бути видимою.
        val ids = PlaceGazetteer.world.map { it.id }.toSet()
        PlaceGazetteer.MAINLAND_FRAMED.forEach {
            assertTrue("$it is framed as mainland but is not in the table", it in ids)
        }
        assertEquals(13, PlaceGazetteer.MAINLAND_FRAMED.size)
    }

    @Test
    fun `a foreign country is findable by name in both languages`() {
        listOf("Польща", "Poland", "польща", "німеччина", "Japan").forEach { q ->
            assertTrue("«$q» finds nothing", PlaceGazetteer.searchEverywhere(q).isNotEmpty())
        }
    }

    @Test
    fun `a Ukrainian place outranks a country on the same query`() {
        // Той, хто набирає три літери, майже завжди шукає землю під ногами.
        val hits = PlaceGazetteer.searchEverywhere("Україна")
        assertEquals("Україна", hits.first().nameUa)
    }

    @Test
    fun `only the home country is described from the inside`() {
        assertTrue(PlaceGazetteer.hasDetail(PlaceGazetteer.country!!))
        PlaceGazetteer.world.forEach {
            assertFalse("${it.nameUa} claims a breakdown it does not have", PlaceGazetteer.hasDetail(it))
            assertTrue(PlaceGazetteer.oblastsOf(it).isEmpty())
        }
    }
}
