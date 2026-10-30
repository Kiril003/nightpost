package local.phantom.companion.core.geo.metro

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.geo.layers.LayerCatalog
import local.phantom.companion.core.geo.layers.defaultTtlMs
import local.phantom.companion.core.geo.license.GeoLayer
import local.phantom.companion.core.geo.license.LayerRole
import local.phantom.companion.core.geo.license.LicenseClass
import local.phantom.companion.core.geo.license.LicenseFirewall
import local.phantom.companion.core.net.map.OverpassCenter
import local.phantom.companion.core.net.map.OverpassElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Схема станції метро як живий шар карти.
 *
 * Це єдине місце, де перевіряється ЗЧЕПЛЕННЯ трьох раніше незалежних частин
 * модуля: Overpass-запит → [OsmIndoorParser] → [IndoorGraph] → шар, який
 * бачить оператор. Кожна з них має власні тести на своїй ділянці; тут
 * перевіряється те, чого жоден із них не бачить — що на виході виходить
 * ЧЕСНИЙ шар: правильний вхід рекомендовано, безбарʼєрний маршрут не
 * підмінено маршрутом сходами, а «мережа мовчить» не перетворилось на
 * «станції немає».
 *
 * Станція синтетична, але зібрана з реальної форми відповіді Overpass
 * (`out geom`), бо саме на формі — типи елементів, теги рівнів, замкнений
 * контур платформи — ламається розбір, а не на арифметиці.
 */
class MetroStationLayerTest {

    // Точка «оператор стоїть тут» — рівно на вході №1.
    private val hereLat = 50.4500
    private val hereLon = 30.5230

    // ————————————————————————————————————————————————————————————————
    // Фікстура: станція «Хрещатик» із трьох входів, сходів, ліфта,
    // коридору й платформи-полігона.
    // ————————————————————————————————————————————————————————————————

    private fun node(id: Long, lat: Double, lon: Double, tags: Map<String, String>) =
        OverpassElement(id = id, type = "node", lat = lat, lon = lon, tags = tags)

    private fun way(id: Long, geom: List<Pair<Double, Double>>, tags: Map<String, String>) =
        OverpassElement(
            id = id,
            type = "way",
            geometry = geom.map { OverpassCenter(it.first, it.second) },
            tags = tags,
        )

    /** Платформа-полігон: замкнений контур на рівні -1. */
    private val platformWay = way(
        204,
        listOf(
            50.4504 to 30.5234,
            50.4505 to 30.5234,
            50.4505 to 30.5235,
            50.4504 to 30.5235,
            50.4504 to 30.5234,
        ),
        mapOf("railway" to "platform", "level" to "-1", "name" to "Хрещатик"),
    )

    /** Коридор рівня -1: низ сходів → низ ліфта → край платформи. */
    private val corridorWay = way(
        203,
        listOf(50.4501 to 30.5231, 50.4502 to 30.5232, 50.4504 to 30.5234),
        mapOf("highway" to "footway", "indoor" to "corridor", "level" to "-1"),
    )

    /** Сходи з вулиці (рівень 0) у вестибюль (рівень -1). */
    private val stairsWay = way(
        201,
        listOf(50.4500 to 30.5230, 50.4501 to 30.5231),
        mapOf("highway" to "steps", "level" to "0;-1"),
    )

    /** Ліфт-шахта: вузол, що обслуговує рівні -1 і 0. */
    private val elevatorNode = node(
        104, 50.4502, 30.5232,
        mapOf("highway" to "elevator", "level" to "-1;0", "wheelchair" to "yes"),
    )

    private val entrances = listOf(
        node(
            101, 50.4500, 30.5230,
            mapOf(
                "railway" to "subway_entrance", "ref" to "1",
                "wheelchair" to "no", "name" to "Хрещатик",
            ),
        ),
        node(
            102, 50.4502, 30.5232,
            mapOf(
                "railway" to "subway_entrance", "ref" to "2",
                "wheelchair" to "yes", "name" to "Хрещатик",
            ),
        ),
        // Вхід №3 навмисно не приєднаний до жодного шляху: у реальному OSM
        // такі трапляються постійно, і шар зобовʼязаний його показати
        // маркером, а не проковтнути.
        node(
            103, 50.4510, 30.5240,
            mapOf("railway" to "subway_entrance", "ref" to "3", "name" to "Хрещатик"),
        ),
    )

    /** Повна станція: є і сходи, і ліфт. */
    private val fullStation: List<OverpassElement> =
        entrances + elevatorNode + listOf(stairsWay, corridorWay, platformWay)

    /** Та сама станція без ліфта — типова стара станція. */
    private val stationWithoutElevator: List<OverpassElement> =
        entrances + listOf(stairsWay, corridorWay, platformWay)

    // ————————————————————————————————————————————————————————————————
    // Тестові двійники
    // ————————————————————————————————————————————————————————————————

    private class FakeSink : SpatialLayerSink {
        val pushed = mutableListOf<SpatialLayerSpec>()
        val focuses = mutableListOf<FocusTarget>()

        override suspend fun push(spec: SpatialLayerSpec): String {
            // Той самий контракт, що й у справжнього сховища (Закон 1):
            // порожній шар не існує.
            require(spec.elementCount > 0) { "порожній шар класти не можна" }
            pushed += spec
            return "layer-${pushed.size}"
        }

        override suspend fun setVisible(layerId: String, visible: Boolean) = Unit
        override suspend fun clear(layerId: String): Boolean = false
        override suspend fun clearThread(threadId: String?, origin: LayerOrigin?): Int = 0
        override suspend fun list(threadId: String?): List<SpatialLayer> = emptyList()
        override suspend fun requestFocus(target: FocusTarget) {
            focuses += target
        }
    }

    private fun layerOver(
        sink: FakeSink,
        elements: List<OverpassElement>?,
        threadId: String? = "thread-7",
    ) = MetroStationLayer(sink, StationIndoorSource { elements }, { threadId })

    // ————————————————————————————————————————————————————————————————
    // Головний шлях
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `станція стає шаром на мапі з входами, платформою і маршрутом`() = runTest {
        val sink = FakeSink()
        val result = layerOver(sink, fullStation).summon(hereLat, hereLon)

        assertTrue("повна станція має дати покладений шар", result is MetroStationResult.Mapped)
        result as MetroStationResult.Mapped

        assertEquals("шар кладеться рівно один раз", 1, sink.pushed.size)
        val spec = sink.pushed.single()
        assertEquals("шар належить родині «метро»", "metro", spec.kind)
        assertEquals("шар світовий, а не операторський", LayerOrigin.WORLD, spec.origin)
        assertEquals("шар належить нитці, з якої його покликали", "thread-7", spec.ownerThreadId)
        assertTrue("назва станції має бути в заголовку шару", spec.title.contains("Хрещатик"))

        assertEquals("усі три входи потрапили на мапу", 3, result.entranceCount)
        assertEquals("рівно один вхід має підтверджену доступність", 1, result.accessibleEntranceCount)
        assertTrue(
            "маркерів має бути щонайменше три входи плюс платформа",
            spec.markers.size >= 4,
        )
        assertEquals("маршрут усередині станції має бути намальований лінією", 1, spec.lines.size)
        assertTrue("лінія маршруту не може бути з однієї точки", spec.lines.single().points.size >= 2)
    }

    @Test
    fun `рекомендується найближчий вхід, і саме на нього наводиться камера`() = runTest {
        val sink = FakeSink()
        val result = layerOver(sink, fullStation).summon(hereLat, hereLon)
            as MetroStationResult.Mapped

        assertEquals("оператор стоїть на вході №1 — його і рекомендуємо", "1", result.nearestEntranceRef)
        assertEquals("до входу, на якому стоїш, нуль метрів", 0, result.nearestEntranceDistanceM)

        val star = sink.pushed.single().markers.filter { it.label?.startsWith("★") == true }
        assertEquals("зірочку отримує рівно один вхід — інакше вона нічого не означає", 1, star.size)
        assertTrue("зірочка стоїть саме на рекомендованому вході", star.single().label!!.contains("№1"))

        val focus = sink.focuses.single()
        assertEquals("камера летить на рекомендований вхід", star.single().lat, focus.lat, 1e-9)
        assertEquals(
            "зум береться з каталогу — нижче нього маркери входів злипаються",
            LayerCatalog.byId("metro-indoor")!!.minZoom.toDouble(), focus.zoom!!, 1e-9,
        )
        assertEquals("фокус привʼязаний до покладеного шару", "layer-1", focus.layerId)
    }

    @Test
    fun `неприєднаний вхід усе одно показується маркером`() = runTest {
        // Вхід №3 не має жодного ребра: маршрут через нього неможливий, але
        // сам вхід у світі існує. Проковтнути його означало б сховати від
        // людини двері, повз які вона проходить.
        val sink = FakeSink()
        layerOver(sink, fullStation).summon(hereLat, hereLon)

        val labels = sink.pushed.single().markers.mapNotNull { it.label }
        assertTrue("вхід №3 має бути на мапі попри відсутність звʼязків", labels.any { it.contains("№3") })
    }

    // ————————————————————————————————————————————————————————————————
    // Доступність — головна причина, чому цей шар узагалі потрібен
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `у безбарʼєрному режимі рекомендується доступний вхід, а не просто найближчий`() = runTest {
        val sink = FakeSink()
        val result = layerOver(sink, fullStation)
            .summon(hereLat, hereLon, requireWheelchair = true) as MetroStationResult.Mapped

        assertEquals(
            "вхід №1 має wheelchair=no — у візку рекомендуємо №2 з ліфтом",
            "2", result.nearestEntranceRef,
        )
        assertTrue("безбарʼєрний маршрут існує і має бути позначений", result.wheelchairRouteExists)
        assertTrue("намальований маршрут мусить іти ліфтом", result.routeUsesElevator)
        assertEquals("лінія безбарʼєрного маршруту має бути одна", 1, sink.pushed.single().lines.size)
    }

    @Test
    fun `без ліфта безбарʼєрний маршрут не малюється зовсім`() = runTest {
        // Найдорожча можлива брехня цього шару: показати людині у візку
        // маршрут сходами. Правильна поведінка — лінії немає, а у відповіді
        // сказано прямо, що шляху немає.
        val sink = FakeSink()
        val result = layerOver(sink, stationWithoutElevator)
            .summon(hereLat, hereLon, requireWheelchair = true) as MetroStationResult.Mapped

        assertFalse("без ліфта безбарʼєрного шляху немає", result.wheelchairRouteExists)
        assertNull("часу маршруту теж не існує", result.routeSeconds)
        assertTrue(
            "жодної лінії маршруту в безбарʼєрному режимі бути не може",
            sink.pushed.single().lines.isEmpty(),
        )
        assertTrue(
            "відповідь мусить сказати про це словами: ${result.summary}",
            result.summary.contains("не потрапити"),
        )
        assertTrue(
            "входи при цьому лишаються на мапі — вони існують",
            sink.pushed.single().markers.isNotEmpty(),
        )
    }

    @Test
    fun `звичайний маршрут спокійно користується сходами`() = runTest {
        val sink = FakeSink()
        val result = layerOver(sink, stationWithoutElevator)
            .summon(hereLat, hereLon) as MetroStationResult.Mapped

        assertNotNull("пішки до платформи шлях є", result.routeSeconds)
        assertFalse("ліфта на цій станції немає — і маршрут ним не йде", result.routeUsesElevator)
        assertEquals("маршрут малюється лінією", 1, sink.pushed.single().lines.size)
    }

    // ————————————————————————————————————————————————————————————————
    // Чесність відмов
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `мовчання мережі не видається за відсутність станції`() = runTest {
        val sink = FakeSink()
        val result = layerOver(sink, elements = null).summon(hereLat, hereLon)

        assertTrue("жодне дзеркало не відповіло — це Unavailable", result is MetroStationResult.Unavailable)
        assertTrue("шар класти нема з чого", sink.pushed.isEmpty())
        assertTrue(
            "текст мусить називати причину звʼязком, а не порожнечею: ${result.summary}",
            result.summary.contains("звʼязку"),
        )
    }

    @Test
    fun `порожня відповідь Overpass — це чесне «даних немає», а не помилка`() = runTest {
        val sink = FakeSink()
        val result = layerOver(sink, emptyList()).summon(hereLat, hereLon)

        assertTrue("порожньо — це NoIndoorData", result is MetroStationResult.NoIndoorData)
        assertTrue("порожній шар не кладеться (Закон 1)", sink.pushed.isEmpty())
        assertTrue("камеру нікуди вести", sink.focuses.isEmpty())
    }

    @Test
    fun `відповідь без входів і платформ не породжує шару-привида`() = runTest {
        // Overpass віддав коридор, але ні входу, ні платформи. Малювати
        // «шматок коридору посеред нічого» немає сенсу.
        val sink = FakeSink()
        val result = layerOver(sink, listOf(corridorWay)).summon(hereLat, hereLon)

        assertTrue("без входів і платформ показувати нічого", result is MetroStationResult.NoIndoorData)
        assertTrue("шар не кладеться", sink.pushed.isEmpty())
    }

    // ————————————————————————————————————————————————————————————————
    // Ліцензійний контур
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `атрибуція ODbL доїжджає і до відповіді, і до маркера`() = runTest {
        val sink = FakeSink()
        val result = layerOver(sink, fullStation).summon(hereLat, hereLon)
            as MetroStationResult.Mapped

        val expected = LayerCatalog.byId("metro-indoor")!!.layer.attribution
        assertEquals("атрибуція береться з каталогу, а не вписується в код", expected, result.attribution)
        assertTrue("атрибуція має бути у відповіді оператору", result.summary.contains(expected))

        val star = sink.pushed.single().markers.single { it.label?.startsWith("★") == true }
        assertTrue(
            "атрибуція їде на тому маркері, який людина відкриє: ${star.detail}",
            star.detail!!.contains(expected),
        )
    }

    @Test
    fun `картка шару метро проходить ліцензійний фаєрвол`() = runTest {
        // Рантайм-ворота в [MetroStationLayer] мають сенс лише тоді, коли вони
        // справді пропускають чинну картку — інакше шар просто ніколи б не
        // зʼявився, і «захист» був би просто вимкненою функцією.
        val card = LayerCatalog.byId("metro-indoor")!!.layer
        assertTrue("чинна картка шару метро не порушує нічого", LicenseFirewall.audit(listOf(card)).isEmpty())
        assertEquals("шар метро лишається під ODbL", LicenseClass.ODBL, card.license)
        assertFalse(
            "граф станції будується ЛИШЕ з OSM — власні дані в нього не вливаються, " +
                "інакше комерційна база стала б похідною",
            card.joinsOsmTopology,
        )
    }

    @Test
    fun `зіпсована картка шару зупиняє показ, а не просто псує атрибуцію`() {
        // Дзеркало попереднього тесту: якщо колись хтось перепише картку так,
        // що вона порушить умови джерела, фаєрвол зобовʼязаний це побачити —
        // саме на цьому спрацьовує гілка LicenseBlocked.
        val tampered = GeoLayer(
            id = "metro-indoor",
            title = "Метро та внутрішня навігація",
            source = "OpenStreetMap / OpenStationMap",
            license = LicenseClass.ODBL,
            role = LayerRole.PROPRIETARY_DATA,
            attribution = "",
            joinsOsmTopology = true,
        )
        val rules = LicenseFirewall.audit(listOf(tampered)).map { it.rule }.toSet()
        assertTrue("втрачену атрибуцію ODbL має бути видно", "missing-attribution" in rules)
        assertTrue("топологічне зчеплення з OSM — це зараження share-alike", "share-alike-contamination" in rules)
    }

    @Test
    fun `час життя шару береться з каталогу, а не вигадується на місці`() = runTest {
        val sink = FakeSink()
        layerOver(sink, fullStation).summon(hereLat, hereLon)

        val catalog = LayerCatalog.byId("metro-indoor")!!
        assertEquals(
            "шар живе рівно стільки, скільки дозволяє його спосіб оновлення",
            catalog.update.defaultTtlMs, sink.pushed.single().ttlMs,
        )
    }

    // ————————————————————————————————————————————————————————————————
    // Текст для людини
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `відповідь написана українською без числових покручів`() = runTest {
        val sink = FakeSink()
        val result = layerOver(sink, fullStation).summon(hereLat, hereLon)
            as MetroStationResult.Mapped

        assertTrue("три входи — «3 входи», а не «3 входів»: ${result.summary}", result.summary.contains("3 входи"))
        assertTrue("станція має бути названа", result.summary.contains("Хрещатик"))
        assertTrue("доступність згадується окремим рядком", result.summary.contains("У візку"))
    }

    @Test
    fun `радіус запиту не виходить за межі, які має сенс питати`() = runTest {
        // Величезний радіус перетворив би запит станції на запит пів міста
        // пішохідних доріжок; нульовий — не знайшов би нічого. Обидва
        // затискаються мовчки, бо це технічна межа, а не рішення оператора.
        val sink = FakeSink()
        val huge = layerOver(sink, fullStation).summon(hereLat, hereLon, radiusM = 100_000)
        assertTrue("надто великий радіус не має ламати виклик", huge is MetroStationResult.Mapped)

        val tiny = layerOver(FakeSink(), fullStation).summon(hereLat, hereLon, radiusM = 0)
        assertTrue("нульовий радіус теж затискається, а не падає", tiny is MetroStationResult.Mapped)
    }
}
