package local.phantom.companion.core.geo.indoor

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.map.OverpassClient
import local.phantom.companion.core.net.map.OverpassElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndoorRouteConnectorTest {

    private class FakeOverpass(private val elements: List<OverpassElement>) : OverpassClient() {
        override suspend fun query(query: String): List<OverpassElement> = elements
    }

    @Test
    fun connectDestination_whenTooClose_returnsNull() = runBlocking {
        val connector = IndoorRouteConnector(BuildingIndoorEngine(FakeOverpass(emptyList())))
        val conn = connector.connectDestination(50.4500 to 30.5200, 50.45005 to 30.52005)
        assertNull(conn)
    }

    @Test
    fun connectDestination_findsEntranceAndVerticalPassage() = runBlocking {
        val sampleElements = listOf(
            OverpassElement(
                type = "node",
                id = 101L,
                lat = 50.4510,
                lon = 30.5210,
                tags = mapOf("entrance" to "main", "level" to "0", "name" to "Центральний вхід"),
            ),
            OverpassElement(
                type = "way",
                id = 201L,
                tags = mapOf("conveying" to "escalator", "level" to "0;1"),
                geometry = listOf(
                    local.phantom.companion.core.net.map.OverpassCenter(50.4512, 30.5212),
                    local.phantom.companion.core.net.map.OverpassCenter(50.4514, 30.5214),
                ),
            ),
            OverpassElement(
                type = "node",
                id = 301L,
                lat = 50.4515,
                lon = 30.5215,
                tags = mapOf("shop" to "clothes", "name" to "Zara", "level" to "1"),
            ),
        )

        val engine = BuildingIndoorEngine(FakeOverpass(sampleElements))
        val connector = IndoorRouteConnector(engine)

        val conn = connector.connectDestination(
            approachPoint = 50.4500 to 30.5200,
            destPoint = 50.4515 to 30.5215,
        )

        assertNotNull(conn)
        assertEquals("Центральний вхід", conn!!.entrance?.name)
        assertEquals(1, conn.level)
        assertTrue(conn.steps.any { it.type == "entrance" })
        assertTrue(conn.steps.any { it.type == "indoor_level" })
    }
}
