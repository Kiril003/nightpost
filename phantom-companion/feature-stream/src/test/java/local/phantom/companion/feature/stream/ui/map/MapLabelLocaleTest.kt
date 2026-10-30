package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ЛАТИНКА ДЛЯ ЧУЖИНЦЯ — останній блокер гонтлету-1.
 *
 * Персона «турист без української» не могла користуватись мапою взагалі:
 * кожен підпис кирилицею — це орнамент, а не назва. Тут перевіряється сам
 * закон вибору поля; що він застосований до потрібних шарів, доводить
 * `MapThemeWiringTest`-подібний прохід у [MapSymbolSpaceTest].
 */
class MapLabelLocaleTest {

    @Test
    fun `кирилічні читачі лишаються з українською мапою`() {
        listOf("uk", "ru", "be", "bg", "mk", "sr").forEach {
            assertTrue("$it читає кирилицю", readsCyrillic(it))
        }
    }

    @Test
    fun `решта світу отримує латинку`() {
        listOf("en", "de", "pl", "tr", "fr", "es").forEach {
            assertFalse("$it кирилиці не читає", readsCyrillic(it))
        }
    }

    @Test
    fun `тег мови з регіоном читається так само`() {
        assertTrue(readsCyrillic("uk-UA"))
        assertTrue(readsCyrillic("uk_UA"))
        assertFalse(readsCyrillic("en-GB"))
    }

    /**
     * Порядок полів — це і є правило, і воно має бути видно в тексті виразу:
     * англійська, латинка тайла, міжнародна, і аж тоді рідна.
     */
    @Test
    fun `порядок полів для чужинця — латинка попереду рідної`() {
        assertEquals(
            listOf("name:en", "name:latin", "int_name", "name", "name:uk"),
            LATIN_NAME_FIELDS,
        )
    }

    /** Вираз мусить будуватись — саме його движок і покладе в text-field. */
    @Test
    fun `вираз підпису збирається без винятку`() {
        assertTrue(latinTextField().toArray().isNotEmpty())
    }

    /** Жодної транслітерації від себе: немає латинки — лишається кирилиця. */
    @Test
    fun `без латинки в тегах беремо те, що вирішив репозиторій`() {
        assertEquals(
            "Пекарня на розі",
            latinPlaceName(mapOf("name" to "Пекарня на розі"), "Пекарня на розі"),
        )
    }

    @Test
    fun `англійська назва місця виграє в української`() {
        val tags = mapOf("name" to "Ощадбанк", "name:uk" to "Ощадбанк", "name:en" to "Oschadbank")
        assertEquals("Oschadbank", latinPlaceName(tags, "Ощадбанк"))
    }

    @Test
    fun `міжнародна назва йде другою`() {
        val tags = mapOf("name" to "Хрещатик", "int_name" to "Khreshchatyk")
        assertEquals("Khreshchatyk", latinPlaceName(tags, "Хрещатик"))
    }

    @Test
    fun `порожній тег не витісняє справжню назву`() {
        val tags = mapOf("name:en" to "   ", "name" to "Ощадбанк")
        assertEquals("Ощадбанк", latinPlaceName(tags, "Ощадбанк"))
    }
}
