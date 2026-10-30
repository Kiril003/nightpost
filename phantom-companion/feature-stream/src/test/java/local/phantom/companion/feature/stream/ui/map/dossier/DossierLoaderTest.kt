package local.phantom.companion.feature.stream.ui.map.dossier

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.net.map.PlaceDossierClient
import local.phantom.companion.feature.stream.ui.map.MapText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0 + P1 наскрізно: збірка досьє над підробленими джерелами.
 *
 * Два питання, на які цей тест відповідає замість пристрою:
 *  • чи бачить оператор картку ДО мережі (пік за нуль запитів);
 *  • чи може стаття про проспект пролізти під заголовок «ДОВІДКА».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class DossierLoaderTest {

    @Before
    fun bindMapText() {
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    private val libraryTarget = DossierTarget(
        lat = 50.50717,
        lon = 30.41257,
        nameHint = "Бібліотека ім. Андрія Головка",
        categoryHint = "amenity",
        subcategoryHint = "library",
    )

    private val libraryTags = mapOf(
        "name" to "Бібліотека ім. Андрія Головка",
        "amenity" to "library",
        "opening_hours" to "Mo-Fr 10:00-18:00",
    )

    private val avenueArticle = DossierArticle(
        title = "Проспект Георгія Гонгадзе",
        extract = "Проспект Георгія Гонгадзе — проспект у Подільському районі міста Києва.",
        thumbnailUrl = null,
        pageUrl = "https://uk.wikipedia.org/wiki/Проспект_Георгія_Гонгадзе",
        wikidataId = null,
    )

    private val libraryArticle = DossierArticle(
        title = "Бібліотека імені Андрія Головка",
        extract = "Бібліотека імені Андрія Головка — публічна бібліотека Подільського району.",
        thumbnailUrl = null,
        pageUrl = "https://uk.wikipedia.org/wiki/Бібліотека_імені_Андрія_Головка",
        wikidataId = "Q7777",
    )

    // ── P1: пік без мережі ──────────────────────────────────────────────────

    @Test
    fun `перший знімок приходить без жодного запиту`() = runTest {
        val sources = FakeSources(details = listOf(detail(libraryTags)))
        // Саме `first()` на потоці, а не `toList().first()`: перший обриває
        // збирання одразу після початкової емісії, і лічильник запитів показує
        // стан НА МОМЕНТ появи картки. `toList()` дочекався б усього потоку, і
        // тест міряв би вже завершене завантаження — тобто не те, що перевіряє.
        val first = placeDossierFlow(libraryTarget, sources).first()

        assertEquals("Бібліотека ім. Андрія Головка", first.title)
        assertEquals(DossierCategory.LIBRARY, first.category)
        assertEquals("бібліотека", first.kindLabel)
        // Усі слоти ще в дорозі — і саме тому картка малює скелетони, не спінер.
        assertEquals(DossierSlot.values().toSet(), first.pending)
        assertTrue(first.failed.isEmpty())
        assertEquals(0, sources.calls)
    }

    @Test
    fun `безіменний тап не вигадує назви`() = runTest {
        val target = DossierTarget(lat = 50.4, lon = 30.5)
        val first = placeDossierFlow(target, FakeSources()).toList().first()
        assertEquals(FALLBACK_TITLE, first.title)
        assertEquals(DossierCategory.PLACE, first.category)
        assertNull(first.kindLabel)
    }

    @Test
    fun `слоти доливаються, а фінальний знімок нічого не чекає`() = runTest {
        val sources = FakeSources(
            details = listOf(detail(libraryTags)),
            address = "Проспект Георгія Гонгадзе, 20, Київ",
            nearest = avenueArticle,
        )
        val snapshots = placeDossierFlow(libraryTarget, sources).toList()
        assertTrue("очікували кілька знімків, а не один", snapshots.size > 1)

        val last = snapshots.last()
        assertTrue(last.isComplete)
        assertTrue(last.pending.isEmpty())
        assertEquals("Проспект Георгія Гонгадзе, 20, Київ", last.address)
        assertEquals("Mo-Fr 10:00-18:00", last.rawHours)
    }

    // ── P0: чия це довідка ──────────────────────────────────────────────────

    @Test
    fun `стаття про проспект приходить як ПОРУЧ, а не як довідка`() = runTest {
        val sources = FakeSources(details = listOf(detail(libraryTags)), nearest = avenueArticle)
        val last = placeDossierFlow(libraryTarget, sources).toList().last()

        val reference = assertNotNull(last.reference).let { last.reference!! }
        assertEquals(WikiVerdict.NEARBY, reference.verdict)
        assertFalse(reference.isAboutEntity)
        assertEquals("Проспект Георгія Гонгадзе", reference.article.title)
    }

    @Test
    fun `тег wikipedia витягує САМЕ статтю про місце`() = runTest {
        val tags = libraryTags + ("wikipedia" to "uk:Бібліотека імені Андрія Головка")
        val sources = FakeSources(
            details = listOf(detail(tags)),
            // Геопошук усе одно віддає проспект — і все одно програє.
            nearest = avenueArticle,
            byTitle = mapOf("Бібліотека імені Андрія Головка" to libraryArticle),
        )
        val last = placeDossierFlow(libraryTarget, sources).toList().last()

        assertEquals(WikiVerdict.ENTITY, last.reference?.verdict)
        assertEquals("Бібліотека імені Андрія Головка", last.reference?.article?.title)
    }

    @Test
    fun `тег wikidata веде до статті через сайтлінк`() = runTest {
        val tags = libraryTags + ("wikidata" to "Q7777")
        val sources = FakeSources(
            details = listOf(detail(tags)),
            nearest = avenueArticle,
            wikidataTitles = mapOf("Q7777" to WikiTitleRef("uk", "Бібліотека імені Андрія Головка")),
            byTitle = mapOf("Бібліотека імені Андрія Головка" to libraryArticle),
        )
        val last = placeDossierFlow(libraryTarget, sources).toList().last()

        assertEquals(WikiVerdict.ENTITY, last.reference?.verdict)
        assertEquals("Q7777", last.reference?.article?.wikidataId)
    }

    @Test
    fun `коли статті нема взагалі - секції нема`() = runTest {
        val sources = FakeSources(details = listOf(detail(libraryTags)))
        val last = placeDossierFlow(libraryTarget, sources).toList().last()
        assertNull(last.reference)
        assertFalse(last.hasFailed(DossierSlot.REFERENCE))
    }

    // ── Деградація ──────────────────────────────────────────────────────────

    @Test
    fun `мовчазний Overpass не забирає з собою решту картки`() = runTest {
        val sources = FakeSources(
            detailsDelayMs = 60_000L,
            address = "вулиця Січових Стрільців, 1",
        )
        val last = placeDossierFlow(libraryTarget, sources).toList().last()

        assertTrue(last.hasFailed(DossierSlot.DETAIL))
        assertTrue(last.isComplete)
        // Адреса приїхала, назва з тапу вціліла, «Маршрут» ніколи нічого не чекав.
        assertEquals("вулиця Січових Стрільців, 1", last.address)
        assertEquals("Бібліотека ім. Андрія Головка", last.title)
    }

    @Test
    fun `виняток у джерелі - це збій слоту, а не падіння досьє`() = runTest {
        val sources = FakeSources(details = listOf(detail(libraryTags)), addressThrows = true)
        val last = placeDossierFlow(libraryTarget, sources).toList().last()

        assertTrue(last.hasFailed(DossierSlot.ADDRESS))
        assertNull(last.address)
        assertEquals(DossierCategory.LIBRARY, last.category)
    }

    @Test
    fun `порожня відповідь - не збій, а просто відсутня секція`() = runTest {
        val sources = FakeSources(details = listOf(detail(libraryTags)), address = null)
        val last = placeDossierFlow(libraryTarget, sources).toList().last()

        assertFalse(last.hasFailed(DossierSlot.ADDRESS))
        assertNull(last.address)
    }

    // ── Кеш ─────────────────────────────────────────────────────────────────

    @Test
    fun `повторне відкриття приходить одним повним знімком`() = runTest {
        val cache = DossierCache()
        val sources = FakeSources(details = listOf(detail(libraryTags)), address = "Київ")
        placeDossierFlow(libraryTarget, sources, cache).toList()
        val callsAfterFirst = sources.calls

        val second = placeDossierFlow(libraryTarget, sources, cache).toList()
        assertEquals(1, second.size)
        assertTrue(second.single().isComplete)
        assertEquals("жодного нового запиту не мало бути", callsAfterFirst, sources.calls)
    }

    // ── Зупинки ─────────────────────────────────────────────────────────────

    @Test
    fun `зупинка отримує маршрути і сервісний ПОРУЧ`() = runTest {
        val target = DossierTarget(
            lat = 50.45,
            lon = 30.52,
            nameHint = "Контрактова площа",
            categoryHint = "railway",
            subcategoryHint = "station",
        )
        val sources = FakeSources(
            details = listOf(detail(mapOf("name" to "Контрактова площа", "railway" to "station"))),
            routes = listOf(routeLine("18")),
            nearbyPois = listOf(poi("Аптека")),
        )
        val last = placeDossierFlow(target, sources).toList().last()

        assertEquals(1, last.transitLines.size)
        assertEquals("ПОРУЧ ЗІ СТАНЦІЄЮ", last.similarTitle)
        assertEquals(1, last.similar.size)
    }

    // ── Фікстури ────────────────────────────────────────────────────────────

    private fun detail(tags: Map<String, String>) =
        PlaceDossierClient.PlaceDetail(osmId = 1L, lat = 50.50717, lon = 30.41257, tags = tags)

    private fun routeLine(ref: String) = PlaceDossierClient.RouteLineRef(
        relationId = 1L, ref = ref, name = null, mode = "bus", colour = null, from = null, to = null,
    )

    private fun poi(name: String) = PoiEntity(
        id = 2L, lat = 50.45, lon = 30.52, name = name,
        category = "amenity", subcategory = "pharmacy", tags = emptyMap(),
    )

    /**
     * Підроблені джерела. Лічильник [calls] — доказ головного твердження P1:
     * перший знімок малюється при нулі запитів.
     */
    private class FakeSources(
        private val details: List<PlaceDossierClient.PlaceDetail> = emptyList(),
        private val building: PlaceDossierClient.BuildingCard? = null,
        private val routes: List<PlaceDossierClient.RouteLineRef> = emptyList(),
        private val nearbyPois: List<PoiEntity> = emptyList(),
        private val address: String? = null,
        private val nearest: DossierArticle? = null,
        private val byTitle: Map<String, DossierArticle> = emptyMap(),
        private val wikidataTitles: Map<String, WikiTitleRef> = emptyMap(),
        private val detailsDelayMs: Long = 0L,
        private val addressThrows: Boolean = false,
    ) : DossierSources {
        @Volatile var calls: Int = 0
            private set

        override suspend fun namedAround(lat: Double, lon: Double): List<PlaceDossierClient.PlaceDetail> {
            calls++
            if (detailsDelayMs > 0) delay(detailsDelayMs)
            return details
        }

        override suspend fun buildingAt(lat: Double, lon: Double): PlaceDossierClient.BuildingCard? {
            calls++
            return building
        }

        override suspend fun stopRoutes(lat: Double, lon: Double, radiusM: Int) =
            routes.also { calls++ }

        override suspend fun nearby(lat: Double, lon: Double, radiusM: Int, selector: String) =
            nearbyPois.also { calls++ }

        override suspend fun reverseGeocode(lat: Double, lon: Double): String? {
            calls++
            if (addressThrows) error("мережа мовчить")
            return address
        }

        override suspend fun nearestArticle(lat: Double, lon: Double, nameHint: String?) =
            nearest.also { calls++ }

        override suspend fun articleByTitle(ref: WikiTitleRef) =
            byTitle[ref.title].also { calls++ }

        override suspend fun articleTitleForWikidata(wikidataId: String) =
            wikidataTitles[wikidataId].also { calls++ }
    }
}
