package local.phantom.companion.core.geo.indoor

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.map.OverpassCenter
import local.phantom.companion.core.net.map.OverpassClient
import local.phantom.companion.core.net.map.OverpassElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Внутрішній план існує лише з внутрішніх елементів.
 *
 * Кадр власника 01.09 02:18, він удома: уся дорожня мережа двору синіми
 * лініями поверх мапи, «parking» ×6 бейджами, панель «ПОВЕРХ +2 / +1 / −1».
 * Три симптоми — один корінь: запит бере все в радіусі 350 м, і парсер робив
 * з кожної стежки прохід, з кожної стоянки кімнату, з кожного `layer` поверх.
 * Червоне на старому коді: двір давав план із 7 проходів і 2 кімнат.
 */
class IndoorPlanScopeTest {

    private class FakeOverpass(private val elements: List<OverpassElement>) : OverpassClient() {
        override suspend fun query(query: String): List<OverpassElement> = elements
    }

    private fun way(id: Long, tags: Map<String, String>, closed: Boolean = false): OverpassElement {
        val pts = if (closed) {
            listOf(
                OverpassCenter(50.2547, 28.6587), OverpassCenter(50.2549, 28.6587),
                OverpassCenter(50.2549, 28.6590), OverpassCenter(50.2547, 28.6590),
                OverpassCenter(50.2547, 28.6587),
            )
        } else {
            listOf(OverpassCenter(50.2547, 28.6587), OverpassCenter(50.2551, 28.6595))
        }
        return OverpassElement(type = "way", id = id, tags = tags, geometry = pts)
    }

    private fun node(id: Long, tags: Map<String, String>) =
        OverpassElement(type = "node", id = id, lat = 50.2548, lon = 28.6588, tags = tags)

    /** Житловий двір: те, що Overpass справді віддає в 350 м від дому. */
    private val yard = listOf(
        way(1, mapOf("highway" to "footway")),
        way(2, mapOf("highway" to "footway", "lit" to "yes")),
        way(3, mapOf("highway" to "service", "service" to "driveway")),
        way(4, mapOf("highway" to "living_street", "name" to "вулиця Олександра Олеся")),
        way(5, mapOf("highway" to "steps")),
        way(6, mapOf("highway" to "footway", "bridge" to "yes", "layer" to "1")),
        way(7, mapOf("highway" to "service", "tunnel" to "yes", "layer" to "-1")),
        way(8, mapOf("amenity" to "parking", "parking" to "surface"), closed = true),
        way(9, mapOf("amenity" to "parking"), closed = true),
        node(10, mapOf("entrance" to "yes")),
        node(11, mapOf("entrance" to "yes", "addr:unit" to "2")),
        node(12, mapOf("entrance" to "main", "wheelchair" to "yes")),
        node(13, mapOf("amenity" to "cafe", "name" to "Кавʼярня на розі")),
        node(14, mapOf("shop" to "convenience", "name" to "Продукти")),
    )

    @Test
    fun `a residential yard is not a building plan`() = runBlocking {
        val plan = BuildingIndoorEngine(FakeOverpass(yard)).fetchBuildingPlan(50.2548, 28.6588)
        assertNull("двір став внутрішнім планом — сині вени й «parking» повернулись", plan)
    }

    @Test
    fun `street layer hints never become floors`() = runBlocking {
        // Одна справжня кімната, щоб план існував — і поруч міст layer=1 та труба layer=-1.
        val els = yard + way(20, mapOf("indoor" to "room", "shop" to "clothes", "name" to "Zara"), closed = true)
        val plan = BuildingIndoorEngine(FakeOverpass(els)).fetchBuildingPlan(50.2548, 28.6588)
        assertNotNull(plan)
        assertEquals(listOf(0), plan!!.availableLevels)
        assertEquals(1, plan.rooms.size)
        assertTrue("стежки двору стали проходами", plan.passages.isEmpty())
        assertTrue("вулична кавʼярня стала закладом будинку", plan.venues.none { it.name == "Кавʼярня на розі" })
    }

    @Test
    fun `an underground car park with a level is a room, a surface lot is not`() = runBlocking {
        val els = listOf(
            way(30, mapOf("amenity" to "parking", "parking" to "underground", "level" to "-1"), closed = true),
            way(31, mapOf("amenity" to "parking", "parking" to "surface"), closed = true),
            way(32, mapOf("indoor" to "corridor", "level" to "1")),
            way(33, mapOf("indoor" to "room", "shop" to "electronics", "name" to "Фокстрот", "level" to "2"), closed = true),
        )
        val plan = BuildingIndoorEngine(FakeOverpass(els)).fetchBuildingPlan(50.2548, 28.6588)
        assertNotNull(plan)
        assertEquals(listOf(-1, 0, 1, 2), plan!!.availableLevels)
        assertEquals(2, plan.rooms.size)
        assertEquals(1, plan.passages.size)
        assertEquals(PassageKind.CORRIDOR, plan.passages.single().kind)
    }

    @Test
    fun `an underground car park under the house is indoor even without a level`() = runBlocking {
        // Доповнення штабу 03.09: parking=underground — законна індор-частина; наземна — двір.
        val els = yard + way(21, mapOf("amenity" to "parking", "parking" to "underground"), closed = true)
        val plan = BuildingIndoorEngine(FakeOverpass(els)).fetchBuildingPlan(50.2548, 28.6588)
        assertNotNull("підземна стоянка загубилась", plan)
        assertEquals(1, plan!!.rooms.size)
        assertEquals("parking", plan.rooms.single().category)
        assertTrue("наземні стоянки двору стали кімнатами", plan.rooms.none { it.id == "room_8" || it.id == "room_9" })
    }

    @Test
    fun `entrances alone do not make a plan, but ride along with one`() = runBlocking {
        val doorsOnly = listOf(node(40, mapOf("entrance" to "main")), node(41, mapOf("entrance" to "yes")))
        assertNull(BuildingIndoorEngine(FakeOverpass(doorsOnly)).fetchBuildingPlan(50.2548, 28.6588))
        val withRoom = doorsOnly + way(42, mapOf("indoor" to "room", "level" to "0"), closed = true)
        val plan = BuildingIndoorEngine(FakeOverpass(withRoom)).fetchBuildingPlan(50.2548, 28.6588)
        assertEquals(2, plan!!.entrances.size)
    }

    @Test
    fun `the indoor sieve itself`() {
        assertTrue(isIndoorish(mapOf("indoor" to "yes")))
        assertTrue(isIndoorish(mapOf("level" to "1", "highway" to "footway")))
        assertTrue(isIndoorish(mapOf("tunnel" to "building_passage")))
        assertTrue(isIndoorish(mapOf("conveying" to "escalator")))
        assertFalse(isIndoorish(mapOf("highway" to "footway")))
        assertFalse(isIndoorish(mapOf("highway" to "steps")))
        assertFalse(isIndoorish(mapOf("amenity" to "parking")))
        assertFalse(isIndoorish(mapOf("amenity" to "parking", "parking" to "surface")))
        assertTrue(isIndoorish(mapOf("amenity" to "parking", "parking" to "underground")))
        assertTrue(isIndoorish(mapOf("amenity" to "parking", "parking" to "multi-storey")))
        assertFalse("layer — не ознака приміщення", isIndoorish(mapOf("highway" to "footway", "layer" to "1")))
    }
}
