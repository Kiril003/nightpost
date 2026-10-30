package local.phantom.companion.core.data.geocoder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дзеркало контракту: те, що телефон каже базі, мусить бути дослівно тим, що
 * казала кузня, коли будувала індекси. Файл векторів — не документація, він
 * опублікований кузнею разом із пакетом.
 */
class ForgeGeocoderContractTest {

    private val sql = Json
        .parseToJsonElement(ForgePackFixture.resource("forge/geocoder-md.json"))
        .jsonObject["sql"]!!.jsonObject

    private fun forge(name: String) = sql[name]!!.jsonPrimitive.content

    @Test
    fun `every query is the forge's own query`() {
        assertEquals(forge("match"), ForgeGeocoderSql.MATCH)
        assertEquals(forge("match_near"), ForgeGeocoderSql.MATCH_NEAR)
        assertEquals(forge("category"), ForgeGeocoderSql.CATEGORY)
        assertEquals(forge("address"), ForgeGeocoderSql.ADDRESS)
        assertEquals(forge("reverse_addr"), ForgeGeocoderSql.REVERSE_ADDR)
        assertEquals(forge("reverse_place"), ForgeGeocoderSql.REVERSE_PLACE)
        assertEquals(forge("df"), ForgeGeocoderSql.DF)
    }

    @Test
    fun `the list of columns matches the order the reader unpacks`() {
        assertTrue(ForgeGeocoderSql.MATCH.startsWith("SELECT ${ForgeGeocoderSql.COLS} FROM places"))
        assertEquals(16, ForgeGeocoderSql.COLS.split(",").size)
        assertEquals("id", ForgeGeocoderSql.COLS.split(",").first())
        assertEquals("names", ForgeGeocoderSql.COLS.split(",").last())
    }

    @Test
    fun `holes are filled with as many places as there are values`() {
        assertEquals(
            "SELECT tri, n FROM tri_df WHERE tri IN (?,?,?)",
            ForgeGeocoderSql.expand(ForgeGeocoderSql.DF, 3),
        )
    }

    @Test
    fun `folding follows the rule the pack was built with`() {
        assertEquals("chisinau", ForgeGeocoderText.fold("Chișinău"))
        assertEquals("strada stefan cel mare si sfant", ForgeGeocoderText.fold("Strada Ștefan cel Mare și Sfânt"))
        assertEquals("кавярня", ForgeGeocoderText.fold("Кавʼярня"))
        assertEquals("кавярня", ForgeGeocoderText.fold("Кавʼярня"))
        assertEquals("елка", ForgeGeocoderText.fold("Ёлка"))
        assertEquals("вул і провулок 12а", ForgeGeocoderText.fold("вул. і провулок, 12а"))
        assertEquals("", ForgeGeocoderText.fold("   ,,,   "))
    }

    @Test
    fun `trigrams wrap the word in boundaries`() {
        assertEquals(setOf("0ки", "киш", "ише", "шен", "ені", "нів", "ів0"), ForgeGeocoderText.wordTrigrams("кишенів"))
        assertTrue("0ки" in ForgeGeocoderText.trigrams("Кишенів"))
        assertEquals(emptySet<String>(), ForgeGeocoderText.wordTrigrams(""))
    }

    @Test
    fun `similarity is measured against the query, not against every language of the name`() {
        val query = ForgeGeocoderText.words("Кишенів").map { ForgeGeocoderText.wordTrigrams(it) }
        val wide = "Chișinău Кишинёв Кишинів Chisinau chisinau кишинев кишинiв"
        assertTrue("описка мусить утриматись над порогом", ForgeGeocoderText.similarity(query, wide) >= 0.5)
        assertTrue(ForgeGeocoderText.similarity(query, "Одеса Odesa") < 0.5)
        assertEquals(0.0, ForgeGeocoderText.similarity(query, ""), 0.0)
    }

    @Test
    fun `a name expression asks the names column and nothing else`() {
        assertEquals("names:strada* names:haltei*", ForgeGeocoderText.namesExpression("Strada Haltei"))
        assertNull(ForgeGeocoderText.namesExpression("   "))
    }

    @Test
    fun `fts syntax typed by a human is stripped, not executed`() {
        assertEquals("АЗС  ОККО", ForgeGeocoderText.sanitize("АЗС \"ОККО"))
        assertEquals("вул  Шевченка", ForgeGeocoderText.sanitize("вул* Шевченка"))
    }

    @Test
    fun `an address splits into a street and a number`() {
        val haltei = ForgeGeocoderText.ADDRESS.find("strada haltei 6/1")
        assertNotNull(haltei)
        assertEquals("strada haltei", haltei!!.groupValues[1])
        assertEquals("6/1", haltei.groupValues[2])

        val cyrillic = ForgeGeocoderText.ADDRESS.find("штефан чел маре 1")
        assertEquals("штефан чел маре", cyrillic!!.groupValues[1])
        assertEquals("1", cyrillic.groupValues[2])

        assertNull("сама назва адресою не є", ForgeGeocoderText.ADDRESS.find("аптека"))
    }

    @Test
    fun `distance matches the metres the forge published`() {
        // Кишинів, 47.0105/28.8638 → «Bomb shelter» і «Бомбоубежище» з векторів.
        assertEquals(3678.0, ForgeGeocoder.haversine(47.0105, 28.8638, 47.02772, 28.82238), 1.0)
        assertEquals(49113.0, ForgeGeocoder.haversine(47.0105, 28.8638, 46.82902, 29.45336), 1.0)
        assertEquals(65.0, ForgeGeocoder.haversine(47.0105, 28.8638, 47.0104, 28.86464), 1.0)
        assertEquals(384.0, ForgeGeocoder.haversine(47.0105, 28.8638, 47.01116, 28.85883), 1.0)
    }

    @Test
    fun `packed tags survive a pipe inside a value`() {
        assertEquals(
            mapOf("amenity" to "pharmacy", "name" to "А|Б"),
            ForgeGeocoder.decodeTags("amenity=pharmacy|name=А%7CБ"),
        )
        assertEquals(emptyMap<String, String>(), ForgeGeocoder.decodeTags(""))
    }
}
