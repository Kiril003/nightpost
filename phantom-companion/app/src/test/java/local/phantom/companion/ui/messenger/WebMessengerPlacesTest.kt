package local.phantom.companion.ui.messenger

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.feature.stream.ui.map.around.AroundPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K5: the plan draft searches real places through `GET /messenger/places`.
 * Every source is a fake: no server, no POI database, no Photon.
 */
class WebMessengerPlacesTest {
    private val me = 49.8350 to 18.2830
    private val venue = AroundPlace("Cafe X", 49.8360, 18.2830, kind = "cafe", venueId = "0123456789abcdef")
    private val twin = AroundPlace("Cafe X", 49.83622, 18.2830, kind = "cafe")
    private fun osm(name: String, dLat: Double) = AroundPlace(name, me.first + dLat, me.second, kind = "cafe")

    private var venuesAskedAt: Pair<Double, Double>? = null
    private var offlineAskedAt: Pair<Double, Double>? = null
    private var onlineAsked: Pair<String, Pair<Double, Double>>? = null

    private fun door(
        here: Pair<Double, Double>? = me,
        venues: List<AroundPlace> = listOf(venue),
        offline: List<AroundPlace> = emptyList(),
        online: List<AroundPlace> = emptyList(),
    ) = WebMessengerPlaces(
        here = { here },
        venuesNear = { lat, lon -> venuesAskedAt = lat to lon; venues },
        offline = { _, lat, lon, _ -> offlineAskedAt = lat to lon; offline },
        online = { q, near -> onlineAsked = q to near; online },
    )

    private fun places(reply: String): List<JsonObject> {
        val o = Json.parseToJsonElement(reply).jsonObject
        assertEquals("door must answer 200: $reply", 200, o["status"]!!.jsonPrimitive.int)
        return o["data"]!!.jsonObject["places"]!!.jsonArray.map { it.jsonObject }
    }

    private fun get(d: WebMessengerPlaces, query: String) = runBlocking {
        assertTrue(d.claims("GET", listOf("messenger", "places")))
        d.handle("GET", listOf("messenger", "places"), query)
    }

    @Test
    fun `query lists the venue first, then places from the map, all with coordinates`() {
        val near = listOf(twin, osm("Bakery", 0.002), osm("Corner", 0.003), osm("Kavárna", 0.004), osm("Bistro", 0.005))
        val rows = places(get(door(offline = near), "q=cafe&lat=49.835&lon=18.283"))
        assertEquals(5, rows.size)
        assertEquals("0123456789abcdef", rows.first()["id"]!!.jsonPrimitive.content)
        assertTrue("the 25 m twin of the venue must be dropped", rows.drop(1).none { it["name"]!!.jsonPrimitive.content == "Cafe X" })
        assertTrue(rows.drop(1).all { it["id"] is JsonNull })
        rows.forEach {
            assertTrue(it["lat"]!!.jsonPrimitive.double in 49.0..50.0)
            assertTrue(it["lon"]!!.jsonPrimitive.double in 18.0..19.0)
            assertTrue(it["distance_m"]!!.jsonPrimitive.int >= 0)
        }
        assertNull("offline hits answered, Photon is not asked", onlineAsked)
    }

    @Test
    fun `blank query near me is the nearest ten by distance`() {
        val near = (12 downTo 1).map { osm("P$it", it * 0.001) }
        val rows = places(get(door(venues = emptyList(), offline = near), "q="))
        assertEquals(WebMessengerPlaces.LIMIT, rows.size)
        val d = rows.map { it["distance_m"]!!.jsonPrimitive.int }
        assertEquals(d.sorted(), d)
        assertEquals("P1", rows.first()["name"]!!.jsonPrimitive.content)
        assertNull(onlineAsked)
    }

    @Test
    fun `nothing on the phone falls back to Photon around me`() {
        val pizza = AroundPlace("Pizzeria Uno", 49.8371, 18.2902, kind = "restaurant")
        val rows = places(get(door(venues = emptyList(), online = listOf(pizza)), "q=pizza"))
        assertEquals("pizza", onlineAsked?.first)
        assertEquals(me, onlineAsked?.second)
        assertEquals("Pizzeria Uno", rows.single()["name"]!!.jsonPrimitive.content)
        assertEquals(49.8371, rows.single()["lat"]!!.jsonPrimitive.double, 1e-6)
        assertEquals(18.2902, rows.single()["lon"]!!.jsonPrimitive.double, 1e-6)
    }

    @Test
    fun `venues are asked at my own fix, never at a point the page sent`() {
        get(door(offline = listOf(osm("Bakery", 0.002))), "q=cafe&lat=50.45&lon=30.52")
        assertEquals(me, venuesAskedAt)
    }

    @Test
    fun `no fix of my own uses the page point for the map only, no fix at all is 409`() {
        val rows = places(get(door(here = null, offline = listOf(osm("Bakery", 0.002))), "q=cafe&lat=49.835&lon=18.283"))
        assertNull("venues must not be asked without my own fix", venuesAskedAt)
        assertEquals(49.835 to 18.283, offlineAskedAt)
        assertEquals(1, rows.size)
        val refused = Json.parseToJsonElement(get(door(here = null), "q=cafe")).jsonObject
        assertEquals(409, refused["status"]!!.jsonPrimitive.int)
        assertEquals("no_fix", refused["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a venue is found the way search folds names - any apostrophe, any case`() {
        val lira = AroundPlace("Кавʼярня «Ліра»", 49.8361, 18.2830, kind = "cafe", venueId = "fedcba9876543210")
        val q = java.net.URLEncoder.encode("КАВ'ЯРНЯ", "UTF-8")
        val rows = places(get(door(venues = listOf(lira)), "q=$q"))
        assertEquals("fedcba9876543210", rows.single()["id"]!!.jsonPrimitive.content)
        assertNull("the venue answered, Photon is not asked", onlineAsked)
    }

    @Test
    fun `only GET on the places path is claimed`() {
        val d = door()
        assertFalse(d.claims("POST", listOf("messenger", "places")))
        assertFalse(d.claims("GET", listOf("messenger", "venues")))
    }
}
