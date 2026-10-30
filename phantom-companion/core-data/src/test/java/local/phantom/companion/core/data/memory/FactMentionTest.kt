package local.phantom.companion.core.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ці збіги — уся підстава відбирати факти в підказку. Якщо вони перестануть
 * ловитися, PHANTOM тихо повернеться до «памʼяті», яка нічого не добирає.
 */
class FactMentionTest {

    @Test
    fun `apostrophe does not split a word`() {
        assertEquals(listOf("мясо"), FactMention.tokens("мʼясо"))
        assertEquals(listOf("мясо"), FactMention.tokens("мʼясо"))
        assertTrue("три різні апострофи мусять зійтися", FactMention.matches("мʼяса", "мʼясо"))
    }

    @Test
    fun `declensions of the same word match`() {
        val pairs = listOf(
            "Марта" to "Марті",
            "Марта" to "Мартою",
            "машина" to "машині",
            "сестра" to "сестри",
            "кава" to "каві",
            "Рекс" to "Рекса",
            "собака" to "собаці",
            "валіза" to "валізу",
            "сірий" to "сірого",
        )
        for ((a, b) in pairs) {
            assertTrue("«$a» і «$b» — одне слово", FactMention.matches(a.lowercase(), b.lowercase()))
        }
    }

    @Test
    fun `different words do not match`() {
        val pairs = listOf(
            "кава" to "кавун",
            "море" to "морква",
            "сир" to "сирена",
            "дім" to "діти",
        )
        for ((a, b) in pairs) {
            assertFalse("«$a» і «$b» — різні слова", FactMention.matches(a, b))
        }
    }

    @Test
    fun `stop words carry no signal`() {
        assertTrue(FactMention.tokens("я не знаю що це таке").isEmpty())
    }

    @Test
    fun `a fact is scored only when the phrase touches it`() {
        val q = FactMention.tokens("що приготувати на вечерю? може мясо")
        assertTrue(FactMention.relevance("не їсть мʼяса", q) > 0f)
        assertEquals(0f, FactMention.relevance("їздить на сірому Passat", q), 0f)
    }

    @Test
    fun `a name outweighs a common word`() {
        val q = FactMention.tokens("Марта дзвонила про машину")
        val name = FactMention.relevance("Марта — сестра оператора", q)
        val common = FactMention.relevance("машина стоїть у дворі", q)
        assertTrue("власна назва мусить важити більше: $name vs $common", name > common)
    }

    @Test
    fun `latin model names survive tokenisation`() {
        val q = FactMention.tokens("де я поставив Passat?")
        assertTrue(FactMention.relevance("його авто — сірий Passat", q) > 0f)
    }
}
