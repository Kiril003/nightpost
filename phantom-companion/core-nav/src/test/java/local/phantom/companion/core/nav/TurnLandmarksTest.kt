package local.phantom.companion.core.nav

import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnLandmarksTest {

    private val north = (0..10).map { (50.0 + it * 0.001) to 30.0 }
    private val east = (1..9).map { 50.010 to 30.0 + it * 0.001 }
    private val points = north + east
    private val turnAt = points[10]

    private fun variant(profile: RouteProfile = RouteProfile.FOOT, extra: List<RouteStep> = emptyList()) = RouteVariant(
        points = points,
        distanceMeters = 1700.0,
        durationSeconds = 1000.0,
        steps = listOf(
            RouteStep("depart", null, "", 0.0, 0.0, points[0].first, points[0].second),
            RouteStep("turn", "right", "Хрещатик", 0.0, 0.0, turnAt.first, turnAt.second),
        ) + extra + RouteStep("arrive", null, "", 0.0, 0.0, points.last().first, points.last().second),
        speedsMps = emptyList(),
        profile = profile,
    )

    private fun hintsFor(field: LandmarkField, profile: RouteProfile = RouteProfile.FOOT): Map<Int, List<StepHint>> {
        val v = variant(profile)
        return TurnLandmarks.plan(v, RoutePath.of(v)!!, field)
    }

    private val atb = Landmark(LandmarkKind.SUPERMARKET, "АТБ", 50.0097, 30.0002)
    private val noon = 1_790_000_000_000L

    private fun step(type: String, modifier: String?, road: String = "") =
        RouteStep(type, modifier, road, 0.0, 0.0, 0.0, 0.0)

    @Test
    fun `кожен відмінок кожного орієнтира прописаний і не збігається з називним`() {
        LandmarkKind.values().forEach { k ->
            assertTrue(k.name, k.ins != k.nom && k.gen != k.nom)
        }
        assertEquals(
            listOf("мостом", "аркою", "церквою", "храмом", "заправкою", "супермаркетом", "зупинкою", "ринком", "лікарнею", "аптекою", "поштою", "банком", "поліцією", "магазином"),
            LandmarkKind.values().map { it.ins },
        )
        assertEquals(
            listOf("мосту", "арки", "церкви", "храму", "заправки", "супермаркету", "зупинки", "ринку", "лікарні", "аптеки", "пошти", "банку", "поліції", "магазину"),
            LandmarkKind.values().map { it.gen },
        )
    }

    @Test
    fun `назва або незмінна, або в лапках після відмінюваного роду`() {
        assertEquals("АТБ", landmarkRefUk(LandmarkKind.SUPERMARKET, "АТБ", UkCase.INS))
        assertEquals("ОККО", landmarkRefUk(LandmarkKind.FUEL, "ОККО", UkCase.GEN))
        assertEquals("Сільпо", landmarkRefUk(LandmarkKind.SUPERMARKET, "Сільпо", UkCase.INS))
        assertEquals("аптекою «Подорожник»", landmarkRefUk(LandmarkKind.PHARMACY, "Подорожник", UkCase.INS))
        assertEquals("супермаркетом «Фора»", landmarkRefUk(LandmarkKind.SUPERMARKET, "Фора", UkCase.INS))
        assertEquals("зупинки «Площа Ринок»", landmarkRefUk(LandmarkKind.STOP, "Площа Ринок", UkCase.GEN))
        assertEquals("зупинкою «ЦУМ»", landmarkRefUk(LandmarkKind.STOP, "ЦУМ", UkCase.INS))
        assertEquals("церкви", landmarkRefUk(LandmarkKind.CHURCH, null, UkCase.GEN))
        assertEquals("арку", landmarkRefUk(LandmarkKind.ARCH, null, UkCase.ACC))
    }

    @Test
    fun `вивіска очищується до того, що людина прочитає`() {
        assertEquals("АТБ", landmarkName("АТБ-Маркет", LandmarkKind.SUPERMARKET))
        assertEquals("Подорожник", landmarkName("Аптека Подорожник", LandmarkKind.PHARMACY))
        assertEquals("Нова Пошта", landmarkName("Нова Пошта №12", LandmarkKind.POST))
        assertEquals("Оля", landmarkName("Магазин \"Оля\"", LandmarkKind.SHOP))
        assertNull(landmarkName("Аптека оптових цін", LandmarkKind.PHARMACY))
        assertEquals("Бажаємо здоровʼя", landmarkName("Бажаємо здоровʼя", LandmarkKind.PHARMACY))
        assertNull(landmarkName("Продукти", LandmarkKind.SHOP))
        assertNull(landmarkName("Аптека", LandmarkKind.PHARMACY))
        assertNull(landmarkName("Храм святої великомучениці Параскеви", LandmarkKind.CHURCH))
        assertEquals(LandmarkKind.CHURCH, landmarkKindOf("amenity", "place_of_worship", "Церква Святого Юра"))
        assertEquals(LandmarkKind.TEMPLE, landmarkKindOf("amenity", "place_of_worship", "Синагога"))
        assertNull(landmarkKindOf("amenity", "atm", "Банкомат"))
        assertNull(landmarkKindOf("street", null, "Хрещатик"))
        assertNull(landmarkOf("highway", "bus_stop", null, 0.0, 0.0, null))
    }

    @Test
    fun `фрази кожної форми`() {
        val right = step("turn", "right")
        val hint = { k: LandmarkKind, n: String?, r: LandmarkRelation, soon: Boolean -> StepHint(k, n, r, soon) }
        assertEquals("За АТБ поверніть праворуч", guidancePhraseUk(right, hint(LandmarkKind.SUPERMARKET, "АТБ", LandmarkRelation.AFTER, false), null))
        assertEquals(
            "За аптекою «Подорожник» поверніть ліворуч на Городоцьку",
            guidancePhraseUk(step("turn", "left", "Городоцьку"), hint(LandmarkKind.PHARMACY, "Подорожник", LandmarkRelation.AFTER, false), null),
        )
        assertEquals("Біля заправки «Укрнафта» поверніть праворуч", guidancePhraseUk(right, hint(LandmarkKind.FUEL, "Укрнафта", LandmarkRelation.AT, false), null))
        assertEquals("Одразу за мостом поверніть праворуч", guidancePhraseUk(right, hint(LandmarkKind.BRIDGE, null, LandmarkRelation.AFTER, true), null))
        assertEquals("Поверніть праворуч через арку", guidancePhraseUk(right, hint(LandmarkKind.ARCH, null, LandmarkRelation.THROUGH, false), null))
        assertEquals("Ідіть у бік церкви", guidancePhraseUk(step("depart", null), hint(LandmarkKind.CHURCH, null, LandmarkRelation.TOWARD, false), null))
        assertEquals("Ідіть у бік мосту", guidancePhraseUk(step("depart", null), hint(LandmarkKind.BRIDGE, null, LandmarkRelation.TOWARD, false), null))
        assertEquals(
            "Через 300 метрів, за АТБ, поверніть праворуч",
            guidancePhraseUk(right, hint(LandmarkKind.SUPERMARKET, "АТБ", LandmarkRelation.AFTER, false), 310),
        )
        assertEquals("Поверніть праворуч і одразу ліворуч", guidancePhraseUk(right, null, null, chainNext = step("turn", "left")))
        assertEquals(
            "Тримайтесь лівіше, а за мостом поверніть праворуч",
            guidancePhraseUk(step("fork", "slight left"), null, null, following = step("turn", "right") to hint(LandmarkKind.BRIDGE, null, LandmarkRelation.AFTER, false)),
        )
    }

    @Test
    fun `назва вулиці у знахідному або табличкою, але не зламана`() {
        assertEquals(" на вулицю Шевченка", ontoRoadUk("вулиця Шевченка"))
        assertEquals(" на Цвинтарну вулицю", ontoRoadUk("Цвинтарна вулиця"))
        assertEquals(" на Велику Васильківську вулицю", ontoRoadUk("Велика Васильківська вулиця"))
        assertEquals(" на Камʼяну вулицю", ontoRoadUk("Камʼяна вулиця"))
        assertEquals(" на вулицю Молодіжну", ontoRoadUk("вулиця Молодіжна"))
        assertEquals(" на вулицю Шкільну", ontoRoadUk("вул. Шкільна"))
        assertEquals(" — вулиця Садова", ontoRoadUk("вулиця Садова"))
        assertEquals(" на площу Ринок", ontoRoadUk("площа Ринок"))
        assertEquals(" на проспект Свободи", ontoRoadUk("проспект Свободи"))
        assertEquals(" на Личаківський провулок", ontoRoadUk("Личаківський провулок"))
        assertEquals(" на Хрещатик", ontoRoadUk("Хрещатик"))
        assertEquals(" на Городоцьку", ontoRoadUk("Городоцька"))
        assertEquals(" на Набережну вулицю", ontoRoadUk("Набережна вулиця"))
        assertEquals(" — Стежка над водосховищем.", ontoRoadUk("Стежка над водосховищем."))
        assertEquals(" на вулицю Зелену", ontoRoadUk("вулиця Зелена"))
        assertEquals(" на вулицю Камʼяну", ontoRoadUk("вулиця Камʼяна"))
        assertEquals(" — вулиця Петрова", ontoRoadUk("вулиця Петрова"))
        assertEquals(" на вулицю Івана Франка", ontoRoadUk("вулиця Івана Франка"))
        assertEquals(" — вулиця Братів Бережницьких Бічна", ontoRoadUk("вулиця Братів Бережницьких Бічна"))
        assertEquals("", ontoRoadUk("2"))
        assertEquals("", ontoRoadUk("вулиця Повстанців / вулиця Олександра Довженка"))
        assertEquals("Поверніть ліворуч на вулицю Шевченка", maneuverPhraseUk("turn", "left", "вулиця Шевченка", null))
    }

    @Test
    fun `без орієнтира фраза та сама, що в запеченому голосі`() {
        val s = step("turn", "right", "Хрещатик")
        assertEquals(maneuverPhraseUk("turn", "right", "Хрещатик", 250, null), guidancePhraseUk(s, null, 250))
        assertEquals(maneuverPhraseUk("turn", "right", "Хрещатик", null, null), guidancePhraseUk(s, null, null))
    }

    @Test
    fun `жодної механіки у фразах`() {
        val forbidden = Regex("""OSM|\bid\b|±|тег|\d{5,}|null|[{}<>]""")
        val names = listOf(null, "АТБ", "Подорожник", "Площа Ринок")
        LandmarkKind.values().forEach { k ->
            LandmarkRelation.values().forEach { r ->
                names.forEach { n ->
                    listOf(null, 420).forEach { d ->
                        val text = guidancePhraseUk(step("turn", "left", "Шевченка"), StepHint(k, n, r, soon = d == null), d)
                        assertTrue(text, forbidden.find(text) == null)
                        assertTrue(text, text.first().isUpperCase())
                    }
                }
            }
        }
    }

    @Test
    fun `вивіска перед поворотом стає «за», на розі — «біля»`() {
        assertEquals(LandmarkRelation.AFTER, hintsFor(LandmarkField(listOf(atb)))[1]!!.first().relation)
        val corner = atb.copy(lat = 50.01002, lon = 30.00012)
        assertEquals(LandmarkRelation.AT, hintsFor(LandmarkField(listOf(corner)))[1]!!.first().relation)
        val farOff = atb.copy(lon = 30.0006)
        assertNull(hintsFor(LandmarkField(listOf(farOff)))[1])
    }

    @Test
    fun `дві однакові аптеки на підході — не орієнтир`() {
        val a = Landmark(LandmarkKind.PHARMACY, null, 50.0097, 30.0002)
        val b = Landmark(LandmarkKind.PHARMACY, null, 50.0088, 30.0002)
        assertNull(hintsFor(LandmarkField(listOf(a, b)))[1])
        assertEquals(LandmarkKind.PHARMACY, hintsFor(LandmarkField(listOf(a)))[1]!!.single().kind)
    }

    @Test
    fun `міст уздовж дороги — орієнтир, міст над дорогою — ні`() {
        val along = RouteLine(LandmarkKind.BRIDGE, listOf(50.0082 to 30.0, 50.0096 to 30.0))
        val h = hintsFor(LandmarkField(lines = listOf(along)), RouteProfile.CAR)[1]!!.first()
        assertEquals(LandmarkKind.BRIDGE to true, h.kind to h.soon)
        val over = RouteLine(LandmarkKind.BRIDGE, listOf(50.009 to 29.9995, 50.009 to 30.0, 50.009 to 30.0005))
        assertNull(hintsFor(LandmarkField(lines = listOf(over)), RouteProfile.CAR)[1])
    }

    @Test
    fun `арка одразу за поворотом — «через арку»`() {
        val arch = RouteLine(LandmarkKind.ARCH, listOf(50.0100 to 30.0001, 50.0100 to 30.0004))
        assertEquals(LandmarkRelation.THROUGH, hintsFor(LandmarkField(lines = listOf(arch)))[1]!!.first().relation)
    }

    @Test
    fun `темна вітрина вночі не орієнтир, цілодобова — так`() {
        val shop = StepHint(LandmarkKind.SUPERMARKET, "АТБ", LandmarkRelation.AFTER)
        val stop = StepHint(LandmarkKind.STOP, "Ринок", LandmarkRelation.AFTER)
        assertEquals(shop, TurnLandmarks.choose(listOf(shop, stop), noon) { false })
        assertEquals(stop, TurnLandmarks.choose(listOf(shop, stop), noon) { true })
        assertNull(TurnLandmarks.choose(listOf(shop), noon) { true })
        val allNight = shop.copy(hours = "24/7")
        assertEquals(allNight, TurnLandmarks.choose(listOf(allNight, stop), noon) { true })
    }

    @Test
    fun `голос ведення каже орієнтир, коли він є`() {
        val v = variant()
        val session = GuidanceSession.of(v) { null }!!
        session.attachLandmarks(TurnLandmarks.plan(v, RoutePath.of(v)!!, LandmarkField(listOf(atb))))
        val base = 1_000_000_000L
        session.onFix(50.009, 30.0, 1.4f, base)
        val approach = session.onFix(50.0095, 30.0, 1.4f, base + 1_000)
        assertEquals("Через 60 метрів, за АТБ, поверніть праворуч на Хрещатик", approach.cues.single().text)
        assertEquals("за АТБ", approach.progress.landmark?.let(::landmarkLeadUk))
        val now = session.onFix(50.00985, 30.0, 1.4f, base + 2_000)
        assertEquals("За АТБ поверніть праворуч на Хрещатик", now.cues.single().text)
    }

    @Test
    fun `вночі голос мовчить про темну вітрину і каже відстань`() {
        val v = variant()
        val session = GuidanceSession.of(v, darkAt = { _, _, _ -> true }) { null }!!
        session.attachLandmarks(TurnLandmarks.plan(v, RoutePath.of(v)!!, LandmarkField(listOf(atb))))
        val base = 1_000_000_000L
        session.onFix(50.009, 30.0, 1.4f, base)
        val approach = session.onFix(50.0095, 30.0, 1.4f, base + 1_000)
        assertEquals("Через 60 метрів поверніть праворуч на Хрещатик", approach.cues.single().text)
    }

    @Test
    fun `два повороти впритул звучать однією фразою`() {
        val second = RouteStep("turn", "left", "Липова вулиця", 0.0, 0.0, 50.010, 30.0005)
        val v = variant(RouteProfile.CAR, extra = listOf(second))
        val session = GuidanceSession.of(v) { null }!!
        val u = session.onFix(50.0099, 30.0, 8f, 1_000_000_000L)
        assertEquals("Поверніть праворуч і одразу ліворуч на Липову вулицю", u.cues.single().text)
    }
}
