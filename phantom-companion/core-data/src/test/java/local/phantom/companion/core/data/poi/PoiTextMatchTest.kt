package local.phantom.companion.core.data.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила зіставлення назв — без бази, без Android, без мережі.
 *
 * Кожне правило тут існує тому, що без нього людина набирала звичайне
 * українське слово й діставала порожньо.
 */
class PoiTextMatchTest {

    // ── згортання ───────────────────────────────────────────────────────

    @Test
    fun `апостроф пишуть пʼятьма символами, а місце одне`() {
        val forms = listOf("кавʼярня", "кавʼярня", "кавʼярня", "кав‘ярня", "кав`ярня")
        val folded = forms.map { PoiTextMatch.fold(it) }.toSet()
        assertEquals("усі написання мусять згорнутись в одне: $folded", 1, folded.size)
        assertEquals("кавярня", folded.first())
    }

    @Test
    fun `регістр, ё, є, ї, і, мʼякий знак і дефіс не роблять з місця інше місце`() {
        assertEquals(PoiTextMatch.fold("Львівська"), PoiTextMatch.fold("львивска"))
        assertEquals(PoiTextMatch.fold("Артём"), PoiTextMatch.fold("артем"))
        assertEquals(PoiTextMatch.fold("Єнакієве"), PoiTextMatch.fold("енакиеве"))
        assertEquals(PoiTextMatch.fold("Івано-Франківськ"), PoiTextMatch.fold("івано франківськ"))
        assertEquals(PoiTextMatch.fold("Ґалаґан"), PoiTextMatch.fold("галаган"))
    }

    // ── збіг за початком БУДЬ-ЯКОГО слова ───────────────────────────────

    @Test
    fun `«політех» знаходить «Київський політехнічний інститут»`() {
        // Не перше слово назви — а саме на цьому й ламався пошук за підрядком
        // з початку рядка.
        val v = PoiTextMatch.match("Київський політехнічний інститут", "", "політех")
        assertNotNull(v)
        assertEquals(PoiTextMatch.WORD_PREFIX, v!!.rank)
        assertTrue("це збіг саме за назвою", v.matchedName)
    }

    @Test
    fun `«Хрещатик» і «хрещатик» — одне й те саме`() {
        assertEquals(PoiTextMatch.EXACT, PoiTextMatch.match("Хрещатик", "", "хрещатик")!!.rank)
    }

    @Test
    fun `слова запиту зʼєднані через І, а не через АБО`() {
        // «окко азс» означає обидва: АБО поховало б потрібну заправку під
        // кожною, що просто зветься «АЗС».
        assertNotNull(PoiTextMatch.match("АЗС ОККО №14", "", "окко азс"))
        assertNull(PoiTextMatch.match("АЗС WOG", "", "окко азс"))
    }

    // ── одна помилкова літера ───────────────────────────────────────────

    @Test
    fun `«крещатик» знаходить «Хрещатик»`() {
        // Це не описка користувача, а інша орфографія тієї самої назви.
        val v = PoiTextMatch.match("Хрещатик", "", "крещатик")
        assertNotNull("одна літера різниці не має ховати вулицю", v)
        // Рятівний ранг, не прямий: точне написання завжди попереду.
        assertTrue(
            "рятунок мусить лишатися рятунком, а не вигравати видачу (ранг ${v!!.rank})",
            v.rank in PoiTextMatch.CROSS_SCRIPT..PoiTextMatch.TYPO,
        )
        assertTrue(v.rank > PoiTextMatch.match("Хрещатик", "", "хрещатик")!!.rank)
    }

    @Test
    fun `дві помилки — це вже інше слово`() {
        assertNull(PoiTextMatch.match("Хрещатик", "", "крешатек"))
    }

    @Test
    fun `на коротких словах одна літера різниці нічого не рятує`() {
        // «кава» і «сава» різняться однією літерою і не мають нічого спільного.
        assertNull(PoiTextMatch.match("Сава", "", "кава"))
        assertFalse(PoiTextMatch.withinOneEdit("абв", "абвгд"))
        assertTrue(PoiTextMatch.withinOneEdit("хрещатик", "крещатик"))
        assertTrue(PoiTextMatch.withinOneEdit("кав", "кава"))
        assertFalse(PoiTextMatch.withinOneEdit("аб", "ба"))
    }

    // ── дві абетки ──────────────────────────────────────────────────────

    @Test
    fun `латиниця в даних знаходиться кирилицею і навпаки`() {
        assertEquals(PoiTextMatch.skeleton(PoiTextMatch.fold("Львів")), PoiTextMatch.skeleton("lviv"))
        assertEquals(PoiTextMatch.skeleton(PoiTextMatch.fold("Київ")), PoiTextMatch.skeleton("kyiv"))
        assertEquals(PoiTextMatch.skeleton(PoiTextMatch.fold("Харків")), PoiTextMatch.skeleton("kharkiv"))
        assertEquals(PoiTextMatch.skeleton(PoiTextMatch.fold("ВОГ")), PoiTextMatch.skeleton("wog"))

        val v = PoiTextMatch.match("Lviv Coffee", "", "львів")
        assertNotNull("назва латиницею мусить знаходитись кирилицею", v)
        assertEquals(PoiTextMatch.CROSS_SCRIPT, v!!.rank)
    }

    @Test
    fun `скелет не перебиває прямий збіг`() {
        // Ранг скелета мусить лишатися ГІРШИМ за прямі збіги — інакше грубе
        // зведення абеток почало б вигравати видачу.
        assertTrue(PoiTextMatch.CROSS_SCRIPT > PoiTextMatch.WORD_PREFIX)
        assertTrue(PoiTextMatch.CROSS_SCRIPT > PoiTextMatch.CONTAINS)
    }

    // ── номер будинку ───────────────────────────────────────────────────

    @Test
    fun `«хрещатик 1» знаходить «Хрещатик» навіть без номера в даних`() {
        // Показати вулицю незрівнянно корисніше, ніж показати порожньо.
        val plain = PoiTextMatch.match("Хрещатик", "", "хрещатик")!!
        val numbered = PoiTextMatch.match("Хрещатик", "", "хрещатик 1")
        assertNotNull("номер будинку не сміє зʼїдати вулицю", numbered)
        assertEquals("і не сміє псувати їй ранг", plain.rank, numbered!!.rank)

        // Коли номер у даних Є — рядок не гіршає від того, що його знайшли.
        val withNumber = PoiTextMatch.match("Хрещатик", "хрещатик 1", "хрещатик 1")!!
        assertEquals(plain.rank, withNumber.rank)
    }

    @Test
    fun `сам номер нікого не описує`() {
        assertNull(PoiTextMatch.match("Хрещатик", "", "1"))
    }

    // ── назва проти суті ────────────────────────────────────────────────

    @Test
    fun `«аптека» знаходить «Подорожник», але це не збіг за назвою`() {
        // Слово «аптека» ніде в назві немає — воно в колонці keywords, куди
        // його поклав PoiVocabulary з тега amenity=pharmacy.
        val v = PoiTextMatch.match("Подорожник", "аптека ліки pharmacy", "аптека")
        assertNotNull(v)
        assertEquals(PoiTextMatch.KEYWORD_ONLY, v!!.rank)
        assertFalse("оку треба знати, що назва тут ні до чого", v.matchedName)
    }

    @Test
    fun `порожній запит нічого не описує`() {
        assertNull(PoiTextMatch.match("Хрещатик", "", ""))
        assertNull(PoiTextMatch.match("Хрещатик", "", "   "))
        assertNull(PoiTextMatch.match("Хрещатик", "", "!!! ---"))
    }

    // ── дві абетки як ЗАПИТ, а не лише як порівняння ────────────────────

    @Test
    fun `латиниця стає кирилицею`() {
        assertEquals("хрещатик", PoiTextMatch.cyrillize("khreshchatyk"))
        assertEquals("шевченка", PoiTextMatch.cyrillize("shevchenka"))
        assertEquals("сілпо", PoiTextMatch.cyrillize("silpo"))
    }

    @Test
    fun `кирилиця стає латиницею`() {
        assertEquals("khreshchatik", PoiTextMatch.latinize(PoiTextMatch.fold("Хрещатик")))
        assertEquals("silpo", PoiTextMatch.latinize(PoiTextMatch.fold("Сільпо")))
    }

    @Test
    fun `корені двох абеток сходяться на перших трьох літерах`() {
        // Саме цим і живе кореневий прохід: точності тут не треба, треба
        // початок, за яким FTS віддасть кандидата.
        assertTrue(PoiTextMatch.cyrillize("khreshchatyk").startsWith("хре"))
        assertTrue(PoiTextMatch.fold("Хрещатик").startsWith("хре"))
        assertTrue(PoiTextMatch.latinize(PoiTextMatch.fold("Сільпо")).startsWith("sil"))
    }
}
