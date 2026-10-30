package local.phantom.companion.feature.stream.ui.map.dossier

import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.feature.stream.ui.map.MapText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0 — тест на той самий баг, який побачив оператор на пристрої:
 * «Бібліотека ім. Андрія Головка» з довідкою «Проспект Георгія Гонгадзе».
 *
 * Тут пінимо контракт: під заголовком «ДОВІДКА» може стояти лише
 * [WikiVerdict.ENTITY], і жодна евристика не має права підвищити до нього
 * найближчу вулицю.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class WikiEntityResolutionTest {

    @Before
    fun setUp() {
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    private val library = PoiEntityRef(
        name = "Бібліотека ім. Андрія Головка",
        category = DossierCategory.LIBRARY,
        osmId = 42L,
    )

    /** Точний текст із пристрою — перше речення статті про проспект. */
    private val gongadzeAvenue = WikiCandidate(
        title = "Проспект Георгія Гонгадзе",
        extract = "Проспект Георгія Гонгадзе — проспект у Подільському районі міста Києва, " +
            "у місцевостях Виноградар, Вітряні Гори.",
    )

    // ── Головний випадок ────────────────────────────────────────────────────

    @Test
    fun `вулиця під бібліотекою НЕ стає довідкою про бібліотеку`() {
        assertEquals(WikiVerdict.NEARBY, judgeWikiCandidate(library, gongadzeAvenue))
    }

    @Test
    fun `найближча стаття ніколи не підміняє сутнісну`() {
        val ownArticle = WikiCandidate(
            title = "Бібліотека імені Андрія Головка",
            extract = "Бібліотека імені Андрія Головка — публічна бібліотека Подільського " +
                "району Києва, розташована на проспекті Георгія Гонгадзе.",
        )
        // Геопошук віддає найближчу першою — вибір усе одно за сутністю.
        val picked = pickWikiForEntity(library, listOf(gongadzeAvenue, ownArticle))
        assertEquals(ownArticle to WikiVerdict.ENTITY, picked)
    }

    @Test
    fun `коли сутнісної статті нема - лишається чесний контекст ПОРУЧ`() {
        val picked = pickWikiForEntity(library, listOf(gongadzeAvenue))
        assertEquals(gongadzeAvenue to WikiVerdict.NEARBY, picked)
    }

    @Test
    fun `без жодного кандидата секції просто нема`() {
        assertNull(pickWikiForEntity(library, emptyList()))
    }

    // ── Авторитетні ідентифікатори ──────────────────────────────────────────

    @Test
    fun `збіг wikidata доводить тотожність`() {
        val entity = library.copy(wikidataId = "Q123")
        val candidate = gongadzeAvenue.copy(wikidataId = "Q123")
        assertEquals(WikiVerdict.ENTITY, judgeWikiCandidate(entity, candidate))
    }

    @Test
    fun `різні wikidata спростовують тотожність навіть при схожій назві`() {
        val entity = library.copy(wikidataId = "Q123")
        val candidate = WikiCandidate(
            title = "Бібліотека імені Андрія Головка",
            extract = "Інша бібліотека з тією ж назвою в іншому місті.",
            wikidataId = "Q999",
        )
        assertEquals(WikiVerdict.NEARBY, judgeWikiCandidate(entity, candidate))
    }

    @Test
    fun `тег wikipedia робить названу ним статтю довідкою`() {
        val entity = library.copy(wikipedia = WikiTitleRef("uk", "Бібліотека імені Андрія Головка"))
        val candidate = WikiCandidate(
            title = "Бібліотека імені Андрія Головка",
            extract = "Публічна бібліотека Подільського району Києва.",
        )
        assertEquals(WikiVerdict.ENTITY, judgeWikiCandidate(entity, candidate))
    }

    @Test
    fun `тег wikipedia відхиляє будь-яку іншу статтю`() {
        val entity = library.copy(wikipedia = WikiTitleRef("uk", "Бібліотека імені Андрія Головка"))
        assertEquals(WikiVerdict.NEARBY, judgeWikiCandidate(entity, gongadzeAvenue))
    }

    @Test
    fun `стаття витягнута за ідентифікатором сутності виживає редирект`() {
        // «uk:Хрещатик (вулиця)» веде на «Хрещатик» — назви не збігаються, але
        // це та сама стаття, і порівняння назв тут було б хибним запереченням.
        val entity = PoiEntityRef(
            name = "Хрещатик",
            category = DossierCategory.PLACE,
            wikipedia = WikiTitleRef("uk", "Хрещатик (вулиця)"),
            isThoroughfare = true,
        )
        val redirected = WikiCandidate(
            title = "Хрещатик",
            extract = "Хрещатик — головна вулиця Києва.",
            fromEntityTag = true,
        )
        assertEquals(WikiVerdict.ENTITY, judgeWikiCandidate(entity, redirected))
    }

    // ── Назви ───────────────────────────────────────────────────────────────

    @Test
    fun `точний збіг назв з різним записом імені`() {
        val candidate = WikiCandidate(
            title = "Бібліотека імені Андрія Головка",
            extract = "Публічна бібліотека.",
        )
        assertEquals(WikiVerdict.ENTITY, judgeWikiCandidate(library, candidate))
    }

    @Test
    fun `наголоси Вікіпедії не ламають збіг`() {
        val candidate = WikiCandidate(
            title = "Бібліотека імені Андрі́я Голо́вка",
            extract = "Публічна бібліотека.",
        )
        assertEquals(WikiVerdict.ENTITY, judgeWikiCandidate(library, candidate))
    }

    @Test
    fun `дужкове уточнення Вікіпедії не заважає збігу`() {
        val entity = PoiEntityRef(name = "Гулівер", category = DossierCategory.SHOP)
        val candidate = WikiCandidate(
            title = "Гулівер (торговельний центр)",
            extract = "Гулівер — багатофункціональний комплекс у Києві.",
        )
        assertEquals(WikiVerdict.ENTITY, judgeWikiCandidate(entity, candidate))
    }

    @Test
    fun `відмінювання власної назви не розриває збіг`() {
        val entity = PoiEntityRef(name = "Парк Шевченка", category = DossierCategory.LEISURE)
        val candidate = WikiCandidate(
            title = "Парк імені Тараса Шевченко",
            extract = "Парк у центрі міста.",
        )
        assertEquals(WikiVerdict.ENTITY, judgeWikiCandidate(entity, candidate))
    }

    @Test
    fun `спільне родове слово саме по собі нічого не доводить`() {
        val other = WikiCandidate(
            title = "Національна бібліотека України імені Вернадського",
            extract = "Найбільша бібліотека України.",
        )
        assertEquals(WikiVerdict.NEARBY, judgeWikiCandidate(library, other))
    }

    @Test
    fun `тап у порожнє місце не привласнює жодної статті`() {
        val nowhere = PoiEntityRef(name = null, category = DossierCategory.PLACE)
        assertEquals(WikiVerdict.NEARBY, judgeWikiCandidate(nowhere, gongadzeAvenue))
    }

    @Test
    fun `порожній витяг не показуємо взагалі`() {
        val empty = WikiCandidate(title = "Щось", extract = "  ")
        assertEquals(WikiVerdict.NONE, judgeWikiCandidate(library, empty))
    }

    // ── Вето вулиці ─────────────────────────────────────────────────────────

    @Test
    fun `для самої вулиці стаття про вулицю - це довідка`() {
        val street = PoiEntityRef(
            name = "Проспект Георгія Гонгадзе",
            category = DossierCategory.PLACE,
            isThoroughfare = true,
        )
        assertEquals(WikiVerdict.ENTITY, judgeWikiCandidate(street, gongadzeAvenue))
    }

    @Test
    fun `район не привласнюється кафе через спільне прізвище`() {
        val cafe = PoiEntityRef(name = "Кафе Шевченка", category = DossierCategory.FOOD)
        val district = WikiCandidate(
            title = "Шевченківський район (Київ)",
            extract = "Шевченківський район — район міста Києва.",
        )
        assertEquals(WikiVerdict.NEARBY, judgeWikiCandidate(cafe, district))
    }

    @Test
    fun `вулиця розпізнається за головою визначення навіть без родового слова в назві`() {
        val candidate = WikiCandidate(
            title = "Хрещатик",
            extract = "Хрещатик — головна вулиця Києва, довжина 1,2 км.",
        )
        assertTrue(describesThoroughfareOrArea(candidate))
    }

    @Test
    fun `згадка вулиці всередині статті про заклад не робить її статтею про вулицю`() {
        val candidate = WikiCandidate(
            title = "Бібліотека імені Андрія Головка",
            extract = "Бібліотека імені Андрія Головка — публічна бібліотека, розташована " +
                "на проспекті Георгія Гонгадзе у Києві.",
        )
        assertFalse(describesThoroughfareOrArea(candidate))
    }

    @Test
    fun `районна бібліотека не плутається з районом`() {
        val candidate = WikiCandidate(
            title = "Районна бібліотека імені Андрія Головка",
            extract = "Районна бібліотека — публічна бібліотека Подільського району.",
        )
        assertFalse(describesThoroughfareOrArea(candidate))
        assertEquals(WikiVerdict.ENTITY, judgeWikiCandidate(library, candidate))
    }

    @Test
    fun `голова визначення береться після тире і до коми`() {
        assertEquals(
            "проспект у Подільському районі міста Києва",
            definitionHead(gongadzeAvenue.extract),
        )
    }

    // ── Розбір тегів ────────────────────────────────────────────────────────

    @Test
    fun `тег wikipedia читається у трьох формах`() {
        assertEquals(
            WikiTitleRef("uk", "Хрещатик"),
            parseWikipediaTag(mapOf("wikipedia" to "uk:Хрещатик")),
        )
        assertEquals(
            WikiTitleRef("uk", "Хрещатик"),
            parseWikipediaTag(mapOf("wikipedia:uk" to "Хрещатик")),
        )
        assertEquals(
            WikiTitleRef("uk", "Хрещатик"),
            parseWikipediaTag(mapOf("wikipedia" to "https://uk.wikipedia.org/wiki/%D0%A5%D1%80%D0%B5%D1%89%D0%B0%D1%82%D0%B8%D0%BA")),
        )
    }

    @Test
    fun `назва без мовного префікса читається як українська`() {
        assertEquals(
            WikiTitleRef("uk", "Хрещатик"),
            parseWikipediaTag(mapOf("wikipedia" to "Хрещатик")),
        )
    }

    @Test
    fun `сутність збирає ідентифікатори і назву з тегів`() {
        val entity = entityRefOf(
            tags = mapOf(
                "name" to "Бібліотека ім. Андрія Головка",
                "amenity" to "library",
                "wikidata" to "Q7777",
                "wikipedia" to "uk:Бібліотека імені Андрія Головка",
            ),
            osmId = 7L,
        )
        assertEquals("Бібліотека ім. Андрія Головка", entity.name)
        assertEquals(DossierCategory.LIBRARY, entity.category)
        assertEquals("Q7777", entity.wikidataId)
        assertEquals("Бібліотека імені Андрія Головка", entity.wikipedia?.title)
        assertTrue(entity.hasAuthoritativeId)
        assertFalse(entity.isThoroughfare)
    }

    @Test
    fun `сміттєвий wikidata ігнорується`() {
        assertNull(entityRefOf(tags = mapOf("wikidata" to "не-Q")).wikidataId)
    }

    @Test
    fun `вулиця в OSM позначається як вулиця`() {
        assertTrue(isThoroughfareTags(mapOf("highway" to "primary")))
        assertTrue(isThoroughfareTags(mapOf("place" to "square")))
        assertFalse(isThoroughfareTags(mapOf("amenity" to "library")))
    }
}
