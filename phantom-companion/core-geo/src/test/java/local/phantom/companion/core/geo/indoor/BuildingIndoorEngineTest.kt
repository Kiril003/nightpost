package local.phantom.companion.core.geo.indoor

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.map.OverpassCenter
import local.phantom.companion.core.net.map.OverpassClient
import local.phantom.companion.core.net.map.OverpassElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildingIndoorEngineTest {

    private class FakeOverpassClient(private val elements: List<OverpassElement>) : OverpassClient() {
        override suspend fun query(query: String): List<OverpassElement> = elements
    }

    @Test
    fun `parses multi-level building passages, escalators, elevators, and mall venues`() = runBlocking {
        val sampleElements = listOf(
            // Ground floor entrance
            OverpassElement(
                type = "node",
                id = 101,
                lat = 50.4501,
                lon = 30.5234,
                tags = mapOf("entrance" to "main", "name" to "Центральний вхід", "wheelchair" to "yes"),
            ),
            // Level 0 elevator node
            OverpassElement(
                type = "node",
                id = 102,
                lat = 50.4502,
                lon = 30.5235,
                tags = mapOf("highway" to "elevator", "level" to "0", "name" to "Панорамний ліфт"),
            ),
            // Level 1 escalator way
            OverpassElement(
                type = "way",
                id = 201,
                tags = mapOf("conveying" to "escalator", "level" to "1"),
                geometry = listOf(
                    OverpassCenter(50.4501, 30.5234),
                    OverpassCenter(50.4503, 30.5236),
                ),
            ),
            // Building passage way (tunnel through building)
            OverpassElement(
                type = "way",
                id = 202,
                tags = mapOf("tunnel" to "building_passage", "highway" to "footway", "level" to "0", "name" to "Критий пасаж"),
                geometry = listOf(
                    OverpassCenter(50.4500, 30.5230),
                    OverpassCenter(50.4504, 30.5230),
                ),
            ),
            // Level 2 fashion shop room
            OverpassElement(
                type = "way",
                id = 301,
                tags = mapOf("indoor" to "room", "shop" to "clothes", "name" to "Zara", "level" to "2"),
                geometry = listOf(
                    OverpassCenter(50.4505, 30.5238),
                    OverpassCenter(50.4507, 30.5238),
                    OverpassCenter(50.4507, 30.5240),
                    OverpassCenter(50.4505, 30.5240),
                    OverpassCenter(50.4505, 30.5238),
                ),
            ),
            // Level 1 food court cafe
            OverpassElement(
                type = "node",
                id = 401,
                lat = 50.4506,
                lon = 30.5237,
                tags = mapOf("amenity" to "cafe", "name" to "Aroma Kava", "level" to "1", "opening_hours" to "08:00-22:00"),
            ),
            // Level -1 supermarket
            OverpassElement(
                type = "node",
                id = 402,
                lat = 50.4508,
                lon = 30.5239,
                tags = mapOf("shop" to "supermarket", "name" to "Сільпо", "level" to "-1", "opening_hours" to "24/7"),
            ),
        )

        val engine = BuildingIndoorEngine(FakeOverpassClient(sampleElements))
        val plan = engine.fetchBuildingPlan(50.4501, 30.5234)

        assertNotNull(plan)
        assertEquals(listOf(-1, 0, 1, 2), plan!!.availableLevels)

        // Entrances
        assertEquals(1, plan.entrances.size)
        assertEquals(EntranceKind.MAIN, plan.entrances[0].kind)
        assertTrue(plan.entrances[0].isWheelchairAccessible)

        // Passages: Elevator, Escalator, Building Passage
        val l0Passages = plan.passagesForLevel(0)
        assertTrue(l0Passages.any { it.kind == PassageKind.ELEVATOR })
        assertTrue(l0Passages.any { it.kind == PassageKind.BUILDING_PASSAGE })

        val l1Passages = plan.passagesForLevel(1)
        assertEquals(1, l1Passages.size)
        assertEquals(PassageKind.ESCALATOR, l1Passages[0].kind)

        // Rooms
        val l2Rooms = plan.roomsForLevel(2)
        assertEquals(1, l2Rooms.size)
        assertEquals("Zara", l2Rooms[0].name)
        assertEquals("clothes", l2Rooms[0].category)
        assertEquals(IndoorVenueCategory.FASHION, l2Rooms[0].venueCategory)

        // Venues Directory
        assertEquals(3, plan.venues.size)
        val foodVenues = plan.venuesByCategory(IndoorVenueCategory.FOOD)
        assertEquals(1, foodVenues.size)
        assertEquals("Aroma Kava", foodVenues[0].name)
        assertEquals(1, foodVenues[0].level)

        val superVenues = plan.venuesByCategory(IndoorVenueCategory.SUPERMARKET)
        assertEquals(1, superVenues.size)
        assertEquals("Сільпо", superVenues[0].name)
        assertEquals(-1, superVenues[0].level)

        val fashionVenues = plan.venuesByCategory(IndoorVenueCategory.FASHION)
        assertEquals(1, fashionVenues.size)
        assertEquals("Zara", fashionVenues[0].name)
    }
}
