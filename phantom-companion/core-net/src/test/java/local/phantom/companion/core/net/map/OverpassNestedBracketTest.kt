package local.phantom.companion.core.net.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чому клауза селектора не сміє нести власну дужку.
 *
 * `around` загортає КОЖНУ клаузу у свої дужки — `node[$клауза]`. Тож клауза
 * `highway=footway[tunnel=yes]` дає `node[highway=footway[tunnel=yes]]`, а на
 * це Overpass відповідає HTTP 400 `parse error: ']' expected`. Клієнт показує
 * це як «джерело мовчить», тобто як обрив мережі: чіп натискається й завжди
 * порожньо. 08.09 таких у каталозі мап стояло три.
 *
 * Дзеркало цього тесту — `DiscoverySelectorTest` у `:feature-stream`: там
 * міряється сам каталог. Розділено по межі модулів, бо [OverpassQueries]
 * `internal`, і відкривати його заради тесту не можна.
 */
class OverpassNestedBracketTest {

    private val nested = Regex("""\[[^\]\n]*\[""")

    @Test
    fun `чиста клауза дає запит без вкладених дужок`() {
        for (selector in listOf(
            "amenity=pharmacy",
            "parking=underground",
            "bridge=yes",
            "tunnel=building_passage|covered=arcade",
            "tourism~\"attraction|viewpoint\"|historic~\"monument|memorial\"",
        )) {
            val q = OverpassQueries.around(50.4501, 30.5234, 3_000, selector)
            assertFalse("«$selector» дав вкладені дужки: $q", nested.containsMatchIn(q))
            assertTrue("«$selector» не потрапив у запит", q.contains("(around:3000,50.4501,30.5234)"))
        }
    }

    /**
     * Негативний контроль: доводить, що перевірка вище вміє почервоніти.
     * Якщо колись `around` перестане загортати клаузу — цей тест впаде першим.
     */
    @Test
    fun `клауза з власною дужкою і є та сама вада`() {
        val q = OverpassQueries.around(50.4501, 30.5234, 3_000, "highway=footway[tunnel=yes]")
        assertTrue(
            "around більше не загортає клаузу — перевимір усього класу вади",
            nested.containsMatchIn(q),
        )
    }
}
