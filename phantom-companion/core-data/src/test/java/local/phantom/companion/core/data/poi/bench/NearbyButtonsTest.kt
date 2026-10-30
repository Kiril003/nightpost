package local.phantom.companion.core.data.poi.bench

import java.io.File
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.data.evac.ShelterTruth
import local.phantom.companion.core.data.geocoder.ForgeGeocoder
import local.phantom.companion.core.data.geocoder.ForgePackFixture
import local.phantom.companion.core.data.geocoder.JdbcPackDb
import local.phantom.companion.core.data.poi.PoiRepository
import local.phantom.companion.core.net.geosrc.valueOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Дванадцять кнопок «поруч» без мережі — у Києві, Львові, Харкові, Кишиневі
 * (пакет кузні) і Празі (пакета на машині немає). Кожна кнопка або показує
 * місця, або пристрій чесно знає, що місць тут не знає.
 */
class NearbyButtonsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val chisinau = City("Кишинів", 47.0245, 28.8323, hasPack = true)

    private fun repo(): PoiRepository {
        val md = ForgeGeocoder.over(JdbcPackDb(ForgePackFixture.unpack(temp.newFolder("md")).absolutePath))!!
        return PoiRepository(
            poiDao = UaCorpus.NoCache,
            gazetteer = UaCorpus.dao(),
            offlinePack = { UaCorpus.pack },
            forgeGeocoders = { listOf(md) },
        )
    }

    @Test
    fun `every nearby button answers offline or honestly knows nothing here`() {
        val repo = repo()
        val out = StringBuilder("# «Поруч» без мережі, радіус 3 км\n\n| кнопка | ")
        val cities = SearchProbes.CITIES + chisinau
        out.appendLine(cities.joinToString(" | ") { it.name } + " |")
        out.appendLine("|---|" + cities.joinToString("") { "---|" })
        val silent = ArrayList<String>()
        for ((label, selector) in NEARBY_BUTTONS) {
            val cells = cities.map { city ->
                val found = runBlocking { repo.getNearbyResult(city.lat, city.lon, 3000, selector) }
                    .valueOrNull().orEmpty()
                val knows = runBlocking { repo.knowsPlacesNear(city.lat, city.lon) }
                val carries = runBlocking { repo.offlineCarries(city.lat, city.lon, selector) }
                if (found.isEmpty() && knows && label in MUST_ANSWER_EVERYWHERE && city.hasPack) {
                    silent += "$label у ${city.name}"
                }
                when {
                    found.isNotEmpty() -> "${found.size}"
                    !knows -> "0 — «шукати нема де»"
                    !carries -> "0 — «без мережі не знаю, де тут»"
                    else -> "0 — «поруч не знайшов»"
                }
            }
            out.appendLine("| $label | ${cells.joinToString(" | ")} |")
        }
        File("build/reports").mkdirs()
        File("build/reports/nearby-bench.md").writeText(out.toString())
        println(out)
        assertTrue("кнопки мовчать там, де місця є: $silent", silent.isEmpty())
        assertTrue("Прага без пакета мусить казати «шукати нема де»", !runBlocking { repo.knowsPlacesNear(50.0875, 14.4213) })
    }

    @Test
    fun `lviv answers pharmacies from the national corpus, not from kyiv`() {
        val lviv = SearchProbes.LVIV
        val found = runBlocking { repo().getNearbyResult(lviv.lat, lviv.lon, 3000, "amenity=pharmacy") }
            .valueOrNull().orEmpty()
        assertTrue("у Львові поруч аптек лише ${found.size}", found.size >= 20)
        assertTrue(found.all { it.subcategory == "pharmacy" && it.lat in 49.7..50.0 })
    }

    @Test
    fun `lviv without cafes in the corpus says it does not know, not that there are none`() {
        val lviv = SearchProbes.LVIV
        val repo = repo()
        assertTrue(!runBlocking { repo.offlineCarries(lviv.lat, lviv.lon, "amenity=cafe") })
        assertTrue(runBlocking { repo.offlineCarries(lviv.lat, lviv.lon, "amenity=pharmacy") })
        assertTrue(runBlocking { repo.offlineCarries(chisinau.lat, chisinau.lon, "amenity=cafe") })
        assertTrue(runBlocking { repo.offlineCarries(SearchProbes.KYIV.lat, SearchProbes.KYIV.lon, "amenity=cafe") })
    }

    @Test
    fun `chisinau cafes come from the forge pack`() {
        val found = runBlocking { repo().getNearbyResult(chisinau.lat, chisinau.lon, 3000, "amenity=cafe") }
            .valueOrNull().orEmpty()
        assertTrue("у Кишиневі кавʼярень лише ${found.size}", found.size >= 10)
        assertEquals(setOf("cafe"), found.map { it.subcategory }.toSet())
    }

    companion object {
        /** Дзеркало NEARBY_CATS_ALL у feature-stream; NearbyCatsTest звіряє обидва. */
        val NEARBY_BUTTONS: List<Pair<String, String>> = listOf(
            "Укриття" to ShelterTruth.BOMB_SHELTER_SELECTOR,
            "Аптека" to "amenity=pharmacy",
            "АЗС" to "amenity=fuel",
            "Банкомат / Готівка" to "amenity=atm",
            "Супермаркет" to "shop~\"supermarket|convenience\"",
            "Кавʼярня" to "amenity=cafe",
            "Лікарня / Травмпункт" to "amenity~\"hospital|clinic\"",
            "Питна вода / Бювети" to "amenity=drinking_water",
            "Метро та вокзали" to "railway~\"subway_entrance|station|halt\"|amenity=bus_station",
            "Нова Пошта / Укрпошта" to "amenity=post_office",
            "Поліція" to "amenity=police",
            "Підземний паркінг" to "parking=underground",
        )

        private val MUST_ANSWER_EVERYWHERE = setOf(
            "Аптека", "АЗС", "Банкомат / Готівка", "Супермаркет", "Лікарня / Травмпункт", "Поліція",
        )
    }
}
