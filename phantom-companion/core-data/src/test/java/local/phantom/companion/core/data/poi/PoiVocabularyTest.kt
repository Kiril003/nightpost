package local.phantom.companion.core.data.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug these guard: a pharmacy is named «Подорожник» and the operator types
 * «аптека». Indexing only the name answered that with nothing, forever.
 */
class PoiVocabularyTest {

    private fun words(tags: Map<String, String>, sub: String? = null) =
        PoiVocabulary.keywords(tags, tags.keys.firstOrNull(), sub).split(" ").toSet()

    @Test
    fun `kind becomes searchable words in both languages`() {
        val w = words(mapOf("amenity" to "pharmacy"), "pharmacy")
        assertTrue("аптека" in w)
        assertTrue("ліки" in w)
        assertTrue("pharmacy" in w)
    }

    @Test
    fun `a named brand is findable by its chain, not only its node name`() {
        val w = words(mapOf("amenity" to "fuel", "brand" to "ОККО"), "fuel")
        assertTrue("окко" in w)
        assertTrue("заправка" in w)
        assertTrue("азс" in w)
    }

    @Test
    fun `cuisine is a query — «суші» must reach a restaurant called anything`() {
        val w = words(mapOf("amenity" to "restaurant", "cuisine" to "sushi"), "restaurant")
        assertTrue("суші" in w)
        assertTrue("роли" in w)
    }

    @Test
    fun `multi-value cuisine keeps every half`() {
        val w = words(mapOf("amenity" to "restaurant", "cuisine" to "pizza;italian"), "restaurant")
        assertTrue("піца" in w)
        assertTrue("паста" in w)
    }

    @Test
    fun `street and number are indexed so an address is a query`() {
        val w = words(
            mapOf("shop" to "convenience", "addr:street" to "Хрещатик", "addr:housenumber" to "22"),
            "convenience",
        )
        assertTrue("хрещатик" in w)
        assertTrue("22" in w)
    }

    @Test
    fun `attributes people ask by are words too`() {
        val w = words(
            mapOf("amenity" to "fuel", "opening_hours" to "24/7", "fuel:diesel" to "yes"),
            "fuel",
        )
        assertTrue("цілодобово" in w)
        assertTrue("дизель" in w)
    }

    @Test
    fun `tags that say nothing produce nothing`() {
        assertEquals("", PoiVocabulary.keywords(emptyMap()))
    }

    /**
     * Значення, справді повернуті живим Overpass по Остраві (49.78,18.10,
     * 49.88,18.30) 15.09 — не вигадані, зняті командою.
     */
    @Test
    fun `tagValueLabel перекладає живі значення barrier, access, surface`() {
        assertEquals("стовпчик", PoiVocabulary.tagValueLabel("barrier", "bollard"))
        assertEquals("дозволено власником (може закритись)", PoiVocabulary.tagValueLabel("access", "permissive"))
        assertEquals("бруківка (плитка)", PoiVocabulary.tagValueLabel("surface", "paving_stones"))
        assertEquals("середня", PoiVocabulary.tagValueLabel("smoothness", "intermediate"))
    }

    @Test
    fun `tagValueLabel не знає значення — повертає null, а не сире слово`() {
        assertNull(PoiVocabulary.tagValueLabel("barrier", "some_new_osm_value"))
        assertNull(PoiVocabulary.tagValueLabel("access", "yes"))
    }

    @Test
    fun `heritage-код читається як реєстр охорони, а не як голе число`() {
        assertEquals("памʼятка національного значення", PoiVocabulary.tagValueLabel("heritage", "2"))
        assertEquals("памʼятка під охороною", PoiVocabulary.tagValueLabel("heritage", "yes"))
    }

    @Test
    fun `a single letter never leaks into the index`() {
        val w = words(mapOf("amenity" to "cafe", "brand" to "A"), "cafe")
        assertFalse("a" in w)
    }

    @Test
    fun `intent resolves a kind from a prefix`() {
        assertTrue("pharmacy" in PoiVocabulary.intent("апте"))
        assertTrue("fuel" in PoiVocabulary.intent("заправ"))
        assertTrue("cafe" in PoiVocabulary.intent("кава"))
    }

    @Test
    fun `intent stays silent below three characters — everything matches at two`() {
        assertTrue(PoiVocabulary.intent("ка").isEmpty())
        assertTrue(PoiVocabulary.intent("").isEmpty())
    }

    @Test
    fun `an exact word beats somebody else's prefix`() {
        // «бар» is a whole category and also the start of «барбершоп»; a man
        // typing the finished word means the finished word.
        assertEquals(listOf("bar"), PoiVocabulary.intent("бар"))
    }

    @Test
    fun `a place name is not a category`() {
        assertTrue(PoiVocabulary.intent("хрещатик").isEmpty())
        assertTrue(PoiVocabulary.intent("сільпо").isEmpty())
    }

    @Test
    fun `the three apostrophes a Ukrainian keyboard emits are one word`() {
        assertEquals(PoiVocabulary.intent("кавʼярня"), PoiVocabulary.intent("кавʼярня"))
        assertTrue("cafe" in PoiVocabulary.intent("кавʼярня"))
    }

    @Test
    fun `every kind row is well formed and unique`() {
        val values = PoiVocabulary.intent("заправка")
        assertEquals(values.size, values.distinct().size)
    }
}
