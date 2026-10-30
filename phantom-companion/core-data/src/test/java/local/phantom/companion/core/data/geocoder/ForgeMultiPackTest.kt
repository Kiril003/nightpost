package local.phantom.companion.core.data.geocoder

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.poi.PoiDao
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.PoiRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Поставлених пакетів буває кілька, і всі вони мусять відповідати.
 *
 * Власник попросив «не лише Україну, а всю Європу», і кузня спекла 26 країн.
 * До 12.09 читач брав РІВНО ОДИН пакет, а імпортер стирав попередній: файл звався
 * `geocoder.sqlite` на всіх. Людина в Кошицях, у якої стоїть український пакет,
 * мовчки діставала українські відповіді за сотні кілометрів — це гірше за
 * порожній екран, бо порожній екран чесний.
 */
class ForgeMultiPackTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var one: ForgeGeocoder
    private lateinit var two: ForgeGeocoder

    // Кишинів — якір векторів кузні.
    private val lat = 47.0105
    private val lon = 28.8638

    @Before
    fun open() {
        one = ForgeGeocoder.over(JdbcPackDb(ForgePackFixture.unpack(temp.newFolder("a")).absolutePath))
            ?: error("пакет кузні не відкрився")
        two = ForgeGeocoder.over(JdbcPackDb(ForgePackFixture.unpack(temp.newFolder("b")).absolutePath))
            ?: error("другий пакет кузні не відкрився")
    }

    @After
    fun close() {
        one.close()
        two.close()
    }

    private fun repo(
        packs: List<ForgeGeocoder>,
        home: ((Double, Double) -> String?)? = null,
    ) = PoiRepository(poiDao = NoPoiDao(), forgeGeocoders = { packs }, homeCountry = home)

    @Test
    fun `імʼя файла походить від країни, а не одне на всіх`() {
        assertEquals("geocoder-sk.sqlite", ForgeGeocoderStore.fileNameFor("SK"))
        assertEquals("geocoder-ua.sqlite", ForgeGeocoderStore.fileNameFor("ua"))
        // Порожня країна не має ставати порожнім іменем — інакше два безіменні
        // пакети знову зʼїли б один одного.
        assertEquals("geocoder-unknown.sqlite", ForgeGeocoderStore.fileNameFor(""))
        assertEquals("geocoder-unknown.sqlite", ForgeGeocoderStore.fileNameFor("../.."))
    }

    @Test
    fun `питають обидва пакети, а не перший-ліпший`() = runTest {
        val verdict = repo(listOf(one, two)).searchOfflineVerdict("аптека", lat, lon, limit = 5)
        assertTrue(verdict is PoiRepository.OfflineSearch.Hits)
        assertEquals("Apoteka", verdict.hits.first().name)
        // Два однакові пакети не мусять подвоїти видачу.
        assertEquals(verdict.hits.map { it.id }.distinct(), verdict.hits.map { it.id })
    }

    @Test
    fun `рядок несе країну свого пакета`() = runTest {
        // Без цього список із двох «Нових» — у Словаччині й в Україні —
        // неможливо читати.
        val verdict = repo(listOf(one)).searchOfflineVerdict("аптека", lat, lon, limit = 3)
        assertEquals("MD", verdict.hits.first().tags[PoiRepository.COUNTRY_TAG])
    }

    @Test
    fun `нечіткий прохід біжить лише в пакеті країни, де людина стоїть`() = runTest {
        // Виміряно 12.09 на трьох пакетах із Кошиць: нечіткий прохід у ЧУЖОМУ
        // пакеті коштував 306–308 мс і не міг дати відповіді. «Кишенів» —
        // описка з векторів кузні: вдома знаходиться, з-за кордону ні.
        val home = repo(listOf(one, two), home = { _, _ -> "MD" })
            .searchOfflineVerdict("Кишенів", lat, lon, limit = 3)
        assertTrue("вдома описку мусить рятувати нечіткий прохід", home is PoiRepository.OfflineSearch.Hits)
        assertEquals("Chișinău", home.hits.first().name)

        val abroad = repo(listOf(one, two), home = { _, _ -> "SK" })
            .searchOfflineVerdict("Кишенів", lat, lon, limit = 3)
        assertEquals(emptyList<String>(), abroad.hits.map { it.name })
    }

    @Test
    fun `один пакет лишається таким, як був`() = runTest {
        // Коли пакет один, питання «чий він» не стоїть, і нечіткий прохід
        // мусить працювати навіть із чужим кодом країни.
        val verdict = repo(listOf(one), home = { _, _ -> "SK" })
            .searchOfflineVerdict("Кишенів", lat, lon, limit = 3)
        assertTrue(verdict is PoiRepository.OfflineSearch.Hits)
        assertEquals("Chișinău", verdict.hits.first().name)
    }

    private class NoPoiDao : PoiDao {
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
