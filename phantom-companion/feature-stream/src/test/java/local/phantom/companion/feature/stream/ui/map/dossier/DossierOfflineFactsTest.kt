package local.phantom.companion.feature.stream.ui.map.dossier

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.PoiHit
import local.phantom.companion.core.data.poi.TilePlaces
import local.phantom.companion.core.net.map.PlaceDossierClient
import local.phantom.companion.feature.stream.ui.map.MapText
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class DossierOfflineFactsTest {

    @Before
    fun bindMapText() {
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    private val target = DossierTarget(
        lat = 50.08130, lon = 14.41330,
        nameHint = "Kavárna Slavia", categoryHint = "amenity", subcategoryHint = "cafe",
    )

    private val tile = DossierLocalHit(
        source = DossierSourceKind.VIEWPORT,
        name = "Kavárna Slavia",
        lat = 50.08130, lon = 14.41330, distanceM = 2.0,
        osmId = 77L, categoryHint = "amenity", subcategoryHint = "cafe",
        tags = mapOf("name" to "Kavárna Slavia", "amenity" to "cafe", TilePlaces.SOURCE_TAG to "cafe"),
    )

    private val packed = PoiHit(
        id = 4242L, lat = 50.08135, lon = 14.41338, name = "Kavárna Slavia",
        category = "amenity", subcategory = "cafe", distanceMeters = 8.0,
        tags = mapOf(
            "name" to "Kavárna Slavia",
            "amenity" to "cafe",
            "opening_hours" to "Mo-Su 08:00-22:00",
            "phone" to "+420 224 218 493",
            "addr:street" to "Smetanovo nábřeží",
            "addr:housenumber" to "1012/2",
            "addr:city" to "Praha",
        ),
    )

    @Test
    fun `a tagged cafe shows hours, address and phone with the network off`() = runTest {
        val last = placeDossierFlow(target, OfflineSources(listOf(tile, gazetteerHit(packed)))).toList().last()

        assertEquals("Mo-Su 08:00-22:00", last.rawHours)
        assertEquals(
            listOf(
                CoreFact(CoreField.HOURS, "Mo-Su 08:00-22:00"),
                CoreFact(CoreField.ADDRESS, "Smetanovo nábřeží, 1012/2, Praha"),
                CoreFact(CoreField.PHONE, "+420 224 218 493"),
            ),
            coreFacts(last),
        )
    }

    @Test
    fun `a cafe nobody tagged prints no empty fields`() = runTest {
        val last = placeDossierFlow(target, OfflineSources(listOf(tile))).toList().last()

        assertEquals(emptyList<CoreFact>(), coreFacts(last))
    }

    private class OfflineSources(private val hits: List<DossierLocalHit>) : DossierSources {
        override suspend fun localHits(lat: Double, lon: Double, nameHint: String?) = hits
        override suspend fun namedAround(lat: Double, lon: Double): List<PlaceDossierClient.PlaceDetail> = offline()
        override suspend fun buildingAt(lat: Double, lon: Double): PlaceDossierClient.BuildingCard? = offline()
        override suspend fun stopRoutes(lat: Double, lon: Double, radiusM: Int): List<PlaceDossierClient.RouteLineRef> = offline()
        override suspend fun nearby(lat: Double, lon: Double, radiusM: Int, selector: String): List<PoiEntity> = offline()
        override suspend fun reverseGeocode(lat: Double, lon: Double): String? = offline()
        override suspend fun nearestArticle(lat: Double, lon: Double, nameHint: String?): DossierArticle? = offline()
        override suspend fun articleByTitle(ref: WikiTitleRef): DossierArticle? = offline()
        override suspend fun articleTitleForWikidata(wikidataId: String): WikiTitleRef? = offline()

        private fun offline(): Nothing = throw java.io.IOException("offline")
    }
}
