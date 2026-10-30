package local.phantom.companion.feature.stream.ui.map

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapLangATest {

    @Test
    @Config(qualifiers = "en")
    fun `english locale returns english labels and keeps ukrainian search`() {
        MapText.bind(ApplicationProvider.getApplicationContext())

        assertEquals("Power", DiscoveryGroup.ENERGY.title)
        assertEquals("Pharmacy", MapDiscovery.all.first { it.selector == "amenity=pharmacy" }.label)
        assertEquals("Supermarket", MapDiscovery.all.first { it.selector == "shop~\"supermarket|convenience\"" }.label)
        assertEquals("Cafe", MapDiscovery.all.first { it.selector == "amenity=cafe" }.label)

        assertNotNull(MapDiscovery.resolve("pharmacy"))
        assertNotNull(MapDiscovery.resolve("аптека"))
    }

    @Test
    @Config(qualifiers = "uk")
    fun `ukrainian locale returns original ukrainian labels`() {
        MapText.bind(ApplicationProvider.getApplicationContext())

        assertEquals("Енергія та світло", DiscoveryGroup.ENERGY.title)
        assertEquals("Аптека", MapDiscovery.all.first { it.selector == "amenity=pharmacy" }.label)
        assertEquals("Супермаркет", MapDiscovery.all.first { it.selector == "shop~\"supermarket|convenience\"" }.label)
        assertEquals("Кавʼярня", MapDiscovery.all.first { it.selector == "amenity=cafe" }.label)
    }

    @Test
    @Config(qualifiers = "en")
    fun `shelf starts with everyday needs food shops pharmacy transport then remaining in relative order`() {
        MapText.bind(ApplicationProvider.getApplicationContext())

        assertEquals(
            listOf(
                DiscoveryGroup.FOOD,
                DiscoveryGroup.SHOPS,
                DiscoveryGroup.HEALTH,
                DiscoveryGroup.AUTO,
            ),
            MapDiscovery.shortlist.take(4).map { it.group },
        )
        assertEquals(
            listOf(
                "amenity=cafe",
                "shop~\"supermarket|convenience\"",
                "amenity=pharmacy",
                "amenity=fuel",
                local.phantom.companion.core.data.evac.ShelterTruth.BOMB_SHELTER_SELECTOR,
                "amenity=drinking_water",
                "amenity~\"hospital|clinic\"",
                "amenity=atm",
            ),
            MapDiscovery.shortlist.map { it.selector },
        )
    }
}
