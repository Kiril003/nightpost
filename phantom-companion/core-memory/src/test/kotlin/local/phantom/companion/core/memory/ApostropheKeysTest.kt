package local.phantom.companion.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ключі памʼяті (стоп-слова, підказки предикатів, регекси сторожа «не знаю»)
 * пишуться ʼ, як увесь продукт, а [Text.normalize] — дзеркало ПК — зводить
 * апостроф до ASCII. Ключ зустрічається з набраним лише тому, що проходить те
 * саме згортання [Text.foldApostrophes]; без нього кожне слово з апострофом
 * мовчки випадає. Людина набирає будь-який із чотирьох знаків.
 */
class ApostropheKeysTest {

    private val typed = listOf("ʼ", "'", "’", "`")

    @Test fun `«памʼятаєш» with any apostrophe is a question about the owner`() {
        for (a in typed) {
            // Ні «мій», ні «я», ні підказки: власника тут називає лише «памʼятаєш».
            assertTrue("пам${a}ятаєш?", Grounding.ownerQuestion("Пам${a}ятаєш?"))
            assertTrue("ти пам${a}ятаєш", Grounding.ownerQuestion("Ти пам${a}ятаєш, куди ми їздили влітку"))
        }
        assertFalse(Grounding.ownerQuestion("Котра година?"))
    }

    @Test fun `«памʼятаєш» with any apostrophe is a stop word, not a search term`() {
        for (a in typed) assertEquals(a, Text.terms("бублик"), Text.terms("пам${a}ятаєш бублик"))
    }

    @Test fun `name and diet hints hear any apostrophe`() {
        for (a in typed) {
            assertTrue(a, "name" in Text.hintedPredicates("Моє ім${a}я Тарас"))
            // Лише «мʼяс»: «їм », «дієт» і решта ключів дієти тут не стоять.
            assertTrue(a, "diet" in Text.hintedPredicates("Ніякого м${a}яса"))
        }
    }
}
