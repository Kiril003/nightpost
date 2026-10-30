package local.phantom.companion.core.data.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «Unknown» was not coming from the map style — it was invented here, once per
 * nameless OSM node, and then drawn on the map hundreds of times over.
 */
class PoiDisplayNameTest {

    @Test
    fun `a nameless node has no name and does not belong on the map`() {
        assertNull(poiDisplayName(emptyMap()))
        assertNull(poiDisplayName(mapOf("amenity" to "bench")))
        assertNull(poiDisplayName(mapOf("name" to "   ")))
    }

    @Test
    fun `never fabricates the word Unknown`() {
        assertNull(poiDisplayName(mapOf("name" to "Unknown")))
        assertNull(poiDisplayName(mapOf("name" to "unknown")))
    }

    @Test
    fun `Ukrainian wins over every other spelling`() {
        assertEquals(
            "Ощадбанк",
            poiDisplayName(
                mapOf("name" to "Oschadbank", "name:uk" to "Ощадбанк", "name:en" to "Oschadbank"),
            ),
        )
    }

    @Test
    fun `falls back through the tag ladder`() {
        assertEquals("АТБ", poiDisplayName(mapOf("name" to "АТБ")))
        assertEquals("WOG", poiDisplayName(mapOf("brand" to "WOG")))
        assertEquals("Укрпошта", poiDisplayName(mapOf("operator" to "Укрпошта")))
    }

    @Test
    fun `trims stray whitespace`() {
        assertEquals("КFC", poiDisplayName(mapOf("name" to "  КFC  ")))
    }
}
