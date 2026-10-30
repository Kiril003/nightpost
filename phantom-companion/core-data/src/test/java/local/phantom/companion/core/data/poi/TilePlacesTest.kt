package local.phantom.companion.core.data.poi

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import local.phantom.companion.core.data.poi.bench.JdbcGazetteerDao
import local.phantom.companion.core.data.poi.bench.UaCorpus
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.valueOrNull
import local.phantom.companion.core.net.map.OverpassClient
import local.phantom.companion.core.net.map.OverpassElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TilePlacesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Hanging : OverpassClient() {
        override suspend fun fetchAroundResult(lat: Double, lon: Double, radiusMeters: Int, selector: String): GeoResult<List<OverpassElement>> =
            awaitCancellation()
        override suspend fun fetchByNameResult(query: String, lat: Double, lon: Double, radiusMeters: Int): GeoResult<List<OverpassElement>> =
            awaitCancellation()
    }

    private val here = 49.8350 to 18.2920

    private fun tile(name: String, cls: String, sub: String, dLat: Double = 0.0005, dLon: Double = 0.0) =
        TilePlaces.entity(here.first + dLat, here.second + dLon, mapOf("name" to name, "class" to cls, "subclass" to sub))!!

    private fun repo(dao: JdbcGazetteerDao) =
        PoiRepository(poiDao = UaCorpus.NoCache, overpassClient = Hanging(), gazetteer = dao)

    @Test
    fun `a tile feature becomes a place the chips understand`() {
        val shop = tile("Coop Tuty", "grocery", "supermarket")
        assertEquals("shop", shop.category)
        assertEquals("supermarket", shop.subcategory)
        assertEquals("amenity", tile("Kafe", "cafe", "cafe").category)
        val stop = tile("Náměstí", "bus", "bus_stop")
        assertEquals("public_transport" to "platform", stop.category to stop.subcategory)
        assertEquals("Аптека", TilePlaces.entity(0.0, 0.0, mapOf("name" to "Lékárna", "name:uk" to "Аптека", "class" to "pharmacy", "subclass" to "pharmacy"))!!.name)
        assertNull(TilePlaces.entity(0.0, 0.0, mapOf("class" to "cafe", "subclass" to "cafe")))
        assertTrue(shop.id < 0)
    }

    @Test
    fun `nearby answers from what the device knows without waiting for overpass`() = runBlocking {
        val dao = JdbcGazetteerDao(temp.newFile("g.sqlite"))
        dao.upsert(
            listOf(
                PoiGazetteerEntity(7, here.first + 0.001, here.second, "Lékárna U Anděla", "amenity", "pharmacy"),
            ),
        )
        val found = withTimeout(3_000) { repo(dao).getNearbyResult(here.first, here.second, 3000, "amenity=pharmacy") }
        assertEquals(listOf("Lékárna U Anděla"), found.valueOrNull().orEmpty().map { it.name })
    }

    @Test
    fun `places drawn on the map answer chips, nearby by name and search`() = runBlocking {
        val dao = JdbcGazetteerDao(temp.newFile("g.sqlite"))
        val repo = repo(dao)
        val written = repo.rememberTilePlaces(
            listOf(tile("Coop Tuty", "grocery", "supermarket"), tile("Kavárna Mlýnek", "cafe", "cafe", dLon = 0.001)),
        )
        assertEquals(2, written)
        val shops = withTimeout(3_000) { repo.getNearbyResult(here.first, here.second, 3000, "shop=supermarket") }
        assertEquals(listOf("Coop Tuty"), shops.valueOrNull().orEmpty().map { it.name })
        val byName = withTimeout(3_000) { repo.getNearbyByNameResult("coop", here.first, here.second) }
        assertEquals(listOf("Coop Tuty"), byName.valueOrNull().orEmpty().map { it.name })
        assertTrue(repo.searchOffline("coop", here.first, here.second).any { it.name == "Coop Tuty" })
    }

    @Test
    fun `a tile twin of a known place is not written again`() = runBlocking {
        val dao = JdbcGazetteerDao(temp.newFile("g.sqlite"))
        dao.upsert(
            listOf(
                PoiGazetteerEntity(
                    9, here.first + 0.0005, here.second, "Coop Tuty", "shop", "supermarket",
                    tags = mapOf("shop" to "supermarket", "opening_hours" to "Mo-Su 07:00-21:00"),
                ),
            ),
        )
        val repo = repo(dao)
        assertEquals(0, repo.rememberTilePlaces(listOf(tile("coop tuty", "grocery", "supermarket", dLat = 0.0006))))
        val shops = withTimeout(3_000) { repo.getNearbyResult(here.first, here.second, 3000, "shop=supermarket") }
        assertEquals(1, shops.valueOrNull().orEmpty().size)
    }
}
