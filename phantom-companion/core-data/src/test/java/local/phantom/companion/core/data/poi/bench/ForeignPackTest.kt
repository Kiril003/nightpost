package local.phantom.companion.core.data.poi.bench

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.data.geocoder.ForgeGeocoder
import local.phantom.companion.core.data.geocoder.ForgePackFixture
import local.phantom.companion.core.data.geocoder.JdbcPackDb
import local.phantom.companion.core.data.poi.PoiRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Пакет чужої країни на телефоні: відповідає там, де людина стоїть, і мовчить про решту світу. */
class ForeignPackTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val repo by lazy { build() }

    private fun build(): PoiRepository {
        val md = ForgeGeocoder.over(JdbcPackDb(ForgePackFixture.unpack(temp.newFolder("md")).absolutePath))!!
        return PoiRepository(
            poiDao = UaCorpus.NoCache,
            gazetteer = UaCorpus.dao(),
            offlinePack = { UaCorpus.pack },
            forgeGeocoders = { listOf(md) },
        )
    }

    private fun search(q: String, city: City) = runBlocking { repo.searchOfflineVerdict(q, city.lat, city.lon) }

    @Test
    fun `у Києві київський McDonald's, а не кишинівський`() {
        val top = search("McDonalds", SearchProbes.KYIV).hits.first()
        assertTrue("${top.name} за ${top.distanceMeters} м", top.distanceMeters < 5_000)
    }

    @Test
    fun `у Празі з молдовським пакетом шукати нема де`() {
        assertEquals(PoiRepository.OfflineSearch.NowhereToLook, search("McDonalds", SearchProbes.PRAHA))
    }

    @Test
    fun `кава в Кишиневі — кавʼярня, хоч пакет зводить їх із ресторанами`() {
        for (q in listOf("кава", "coffee")) {
            assertEquals(q, "cafe", search(q, SearchProbes.CHISINAU).hits.first().subcategory)
        }
    }

    @Test
    fun `Нова пошта кирилицею знаходить Nova Poshta в Кишиневі`() {
        val top = search("Нова пошта", SearchProbes.CHISINAU).hits.first()
        assertTrue(top.name, top.name.contains("Nova Poshta", ignoreCase = true))
    }

    @Test
    fun `англійською і трансліт вид місця знаходиться в пакеті кузні`() {
        for (q in listOf("pharmacy", "apteka", "аптіка", "coffee")) {
            val hits = search(q, SearchProbes.CHISINAU).hits
            assertTrue("$q: ${hits.take(3).map { it.name + " " + it.subcategory }}", hits.isNotEmpty())
            assertTrue(q, hits.first().distanceMeters < 3_000)
            assertTrue(q, hits.first().subcategory in setOf("pharmacy", "cafe", "restaurant", "fast_food"))
        }
    }
}
