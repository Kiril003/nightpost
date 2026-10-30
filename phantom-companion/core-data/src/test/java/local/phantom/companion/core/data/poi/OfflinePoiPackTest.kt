package local.phantom.companion.core.data.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline icon floor's promises: the pack parses, filters to the viewport,
 * orders nearest-the-centre-first, caps the count, maps tags onto [PoiEntity]
 * for the dossier — and degrades a broken asset to empty, never a crash (a
 * malformed bundle is a build bug, not a runtime the operator should hit).
 */
class OfflinePoiPackTest {

    private val json = """
    {
      "generated": 1, "bbox": [50.2, 30.2, 50.6, 30.9], "count": 3,
      "pois": [
        {"id": 1, "lat": 50.5000, "lon": 30.4000, "name": "Центр", "cat": "amenity", "sub": "atm",
         "tags": {"amenity": "atm", "operator": "ПриватБанк"}},
        {"id": 2, "lat": 50.5010, "lon": 30.4010, "name": "Поруч", "cat": "shop", "sub": "supermarket"},
        {"id": 3, "lat": 50.9000, "lon": 30.9000, "name": "Далеко", "cat": "amenity", "sub": "cafe"}
      ]
    }
    """.trimIndent()

    @Test
    fun `filters to box and drops what is outside`() {
        val pack = OfflinePoiPack.parse(json)
        assertEquals(3, pack.size)
        val hits = pack.inBox(minLat = 50.49, minLon = 30.39, maxLat = 50.51, maxLon = 30.41)
        assertEquals(2, hits.size)
        assertTrue(hits.none { it.name == "Далеко" })
    }

    @Test
    fun `orders nearest the box centre first`() {
        val pack = OfflinePoiPack.parse(json)
        // Box centred exactly on "Центр" (50.500, 30.400); "Поруч" is inside
        // but strictly farther, so the ordering is unambiguous.
        val hits = pack.inBox(minLat = 50.498, minLon = 30.398, maxLat = 50.502, maxLon = 30.402)
        assertEquals("Центр", hits.first().name)
    }

    @Test
    fun `caps at the requested limit`() {
        val pack = OfflinePoiPack.parse(json)
        val hits = pack.inBox(50.2, 30.2, 50.6, 30.9, limit = 1)
        assertEquals(1, hits.size)
    }

    @Test
    fun `carries tags onto the entity for the dossier`() {
        val pack = OfflinePoiPack.parse(json)
        val atm = pack.inBox(50.2, 30.2, 50.6, 30.9).first { it.id == 1L }
        assertEquals("amenity", atm.category)
        assertEquals("atm", atm.subcategory)
        assertEquals("ПриватБанк", atm.tags["operator"])
    }

    @Test
    fun `a malformed asset is an empty pack, not a crash`() {
        val pack = OfflinePoiPack.parse("{ this is not json")
        assertEquals(0, pack.size)
        assertTrue(pack.inBox(50.2, 30.2, 50.6, 30.9).isEmpty())
    }
}
