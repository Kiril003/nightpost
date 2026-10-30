package local.phantom.companion.core.data.evac

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import local.phantom.companion.core.data.poi.OfflinePoiPack
import local.phantom.companion.core.data.poi.PoiDao
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.PoiRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторож стоїть на ПАКЕТІ, а не на коді: код лишається правильним, а вміст
 * їде з кожним перепіканням.
 *
 * `amenity=shelter` в OSM означає НАВІС. У випіканні від 06.09.2026 таких
 * обʼєктів 32, і серед них дослівно «Альтанки для пікніка», «місце для
 * куріння», «Бунгало», «Бельведер».
 *
 * Перевірка на самому пакеті сьогодні зелена ПОРОЖНЕЧЕЮ — сертифікованих у
 * ньому нуль. Тому поруч стоїть [синтетичний пакет][syntheticPack]: у ньому
 * альтанка вже несе власний `shelter_type=gazebo`, тобто рівно той випадок,
 * який настане після перепікання й на якому матчер зобовʼязаний почервоніти.
 */
class ShelterPackTruthTest {

    /** Слова, якими в OSM підписані навіси. Жодне з них не може стояти на сховищі. */
    private val canopyWords = listOf(
        "альтанк", "альтанок", "пікнік", "курін", "бунгало", "бельведер", "беседк",
        "навіс", "платформ", "gazebo", "picnic", "smoking", "bus stop",
    )

    private val packNames = listOf("poi.json", "official_facilities.json", "invincibility_points.json")

    private fun shippedPacks(): List<OfflinePoiPack> = packNames.mapNotNull { name ->
        File("src/main/assets/country/ua/$name").takeIf { it.exists() }?.let { OfflinePoiPack.parse(it.readText()) }
    }

    /** Усе, що пакет узагалі знає. Свідомо широка рамка — це перепис, а не запит. */
    private fun everything(pack: OfflinePoiPack): List<PoiEntity> =
        pack.inBox(-90.0, -180.0, 90.0, 180.0, limit = Int.MAX_VALUE)

    private val tier = ShelterTruth.tier(ShelterAssetType.BOMB_SHELTER)

    private fun sheltersIn(packs: List<OfflinePoiPack>): List<PoiEntity> =
        packs.flatMap { everything(it) }.filter { tier.matches(it.tags, it.category, it.subcategory) }

    /**
     * Той випадок, якого у спеченому пакеті ще немає: обидва обʼєкти несуть
     * `shelter_type`, і тільки перший є сховищем. Це єдина форма, у якій цей
     * тест може почервоніти на альтанці.
     */
    private fun syntheticPack(certified: Int?): OfflinePoiPack = OfflinePoiPack.parse(
        """
        {"generated":1,"count":2${certified?.let { ",\"certified_shelters\":$it" } ?: ""},"pois":[
          {"id":1,"lat":50.45,"lon":30.52,"name":"Сховище №17","cat":"amenity","sub":"shelter",
           "tags":{"amenity":"shelter","shelter_type":"bomb_shelter"}},
          {"id":2,"lat":50.4501,"lon":30.5201,"name":"Альтанка","cat":"amenity","sub":"shelter",
           "tags":{"amenity":"shelter","shelter_type":"gazebo"}}
        ]}
        """.trimIndent(),
    )

    @Test
    fun `a canopy that carries its own shelter_type is still not a shelter`() {
        val found = sheltersIn(listOf(syntheticPack(certified = 1)))
        assertEquals("знайдено не рівно одне: " + found.joinToString { "«${it.name}»" }, 1, found.size)
        assertEquals("Сховище №17", found.single().name)
    }

    @Test
    fun `the shipped pack offers no canopy as a shelter`() {
        val liars = sheltersIn(shippedPacks()).filter { poi -> canopyWords.any { it in poi.name.lowercase() } }
        assertTrue(
            "пакет віддає під виглядом сховища: " + liars.joinToString { "«${it.name}»" },
            liars.isEmpty(),
        )
    }

    @Test
    fun `the trap is present in the shipped pack and is filtered out`() {
        val canopies = shippedPacks().flatMap { everything(it) }.filter { ShelterTruth.isCanopyOnly(it.tags) }
        // Порожній вхід зробив би перевірку вище зеленою ні за що.
        assertTrue("у пакеті немає жодного `amenity=shelter` — перевірка нічого не доводить", canopies.isNotEmpty())
        val admitted = canopies.filter { ShelterTruth.isBombShelter(it.tags) }
        assertEquals("навіс визнано сховищем: " + admitted.joinToString { "«${it.name}»" }, 0, admitted.size)
    }

    @Test
    fun `every admitted shelter carries a protective tag`() {
        val without = sheltersIn(shippedPacks()).filter { poi -> !ShelterTruth.isProtective(poi.tags) }
        assertTrue(
            "сховище без заяви про захист: " + without.joinToString { "«${it.name}»" },
            without.isEmpty(),
        )
    }

    /**
     * Вердикт іде з ЛІЧИЛЬНИКА в заголовку, а не з наявності тега: після
     * перепікання альтанки понесуть `shelter_type=gazebo`, і висновок «ключ є
     * — отже довідник бачить сховища» зробив би застосунок упевнено неправим
     * у другий бік.
     */
    @Test
    fun `the verdict follows the baked count, not the presence of a tag`() {
        fun verdictOf(pack: OfflinePoiPack) =
            PoiRepository(poiDao = SilentDao(), offlinePack = { pack }).offlineShelterRegistry()

        assertEquals(ShelterRegistryVerdict.PRESENT, verdictOf(syntheticPack(certified = 1)))
        assertEquals(ShelterRegistryVerdict.SILENT, verdictOf(syntheticPack(certified = 0)))
        // Пакет, що ніколи не рахував, мовчить — навіть маючи теги на альтанках.
        assertEquals(ShelterRegistryVerdict.SILENT, verdictOf(syntheticPack(certified = null)))
        assertEquals(ShelterRegistryVerdict.ABSENT, verdictOf(OfflinePoiPack.parse("{}")))
    }

    @Test
    fun `the shipped pack does not claim to know about shelters`() {
        val merged = OfflinePoiPack.merge(shippedPacks())
        if (merged.size == 0) return
        val verdict = PoiRepository(poiDao = SilentDao(), offlinePack = { merged }).offlineShelterRegistry()
        if (verdict == ShelterRegistryVerdict.PRESENT) {
            assertTrue(
                "пакет заявляє про сховища, але жодного не віддає",
                sheltersIn(shippedPacks()).isNotEmpty(),
            )
        }
    }

    private class SilentDao : PoiDao {
        override fun getPoisInBoundingBox(
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
        ): Flow<List<PoiEntity>> = flowOf(emptyList())

        override suspend fun insertPois(pois: List<PoiEntity>) = Unit

        override suspend fun clearExpired(expiryTime: Long) = Unit
    }
}
