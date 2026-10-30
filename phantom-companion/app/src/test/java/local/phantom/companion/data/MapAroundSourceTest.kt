package local.phantom.companion.data

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.feature.stream.ui.map.around.AroundPlaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapAroundSourceTest {
    private class Api(val answer: VenueClient.Answer) : VenueApi {
        var areas: List<String> = emptyList()
        override suspend fun near(areas: List<String>): VenueClient.Answer = answer.also { this.areas = areas }
        override suspend fun venue(id: String) = answer
        override suspend fun rate(id: String, stars: Int) = answer
    }

    private val venueJson = """{"venues":[{"id":"0123456789abcdef","name":"Cafe X","address":"1 St","lat":50.001,"lon":14.0,"rating":{"mean":4.5,"count":2,"sum":9}}]}"""
    private val poi = PoiEntity(1, 50.002, 14.0, "Corner Bakery", "amenity", "cafe", emptyMap())
    private var localAsked = 0
    private fun local(r: GeoResult<List<PoiEntity>>): suspend (Double, Double) -> GeoResult<List<PoiEntity>> =
        { _, _ -> localAsked++; r }

    @Test
    fun venuesAndMapPlacesShowTogetherVenueFirst() = runBlocking {
        val api = Api(VenueClient.Answer.Ok(venueJson))
        val near = listOf(
            PoiEntity(2, 50.00135, 14.0, "Cafe X twin", "amenity", "cafe", emptyMap()),
            PoiEntity(3, 50.00137 + 0.0002, 14.0, "Kept 61 m", "amenity", "cafe", emptyMap()),
            PoiEntity(4, 50.003, 14.0, "Bistro", "amenity", "restaurant", emptyMap()),
            PoiEntity(5, 50.004, 14.0, "Pub", "amenity", "pub", emptyMap()),
            PoiEntity(6, 50.005, 14.0, "Bar", "amenity", "bar", emptyMap()),
        )
        val got = MapAroundSource(api, local(GeoResult.Ok(near))).places(50.0, 14.0) as AroundPlaces.Shown
        assertTrue(got.venues)
        assertEquals(5, got.places.size)
        assertEquals("0123456789abcdef", got.places.first().venueId)
        assertTrue("an OSM place within 40 m of the venue is the venue itself", got.places.none { it.name == "Cafe X twin" })
        assertTrue(got.places.any { it.name == "Kept 61 m" })
        assertEquals(1, localAsked)
        assertTrue("сервер мав отримати області r6, а не точку", api.areas.isNotEmpty() && api.areas.all { it.length == 15 })
    }

    @Test
    fun venuesStayWhenPlacesOnThePhoneFail() = runBlocking {
        val dead = GeoResult.Unavailable(SourceUnavailable.Transport("x"))
        val got = MapAroundSource(Api(VenueClient.Answer.Ok(venueJson)), local(dead)).places(50.0, 14.0) as AroundPlaces.Shown
        assertTrue(got.venues)
        assertEquals("0123456789abcdef", got.places.single().venueId)
    }

    @Test
    fun serverOffFallsBackToPlacesOnThePhone() = runBlocking {
        val got = MapAroundSource(Api(VenueClient.Answer.NotEnabled), local(GeoResult.Ok(listOf(poi)))).places(50.0, 14.0)
        got as AroundPlaces.Shown
        assertEquals(false, got.venues)
        assertEquals("Corner Bakery", got.places.single().name)
        assertEquals("cafe", got.places.single().kind)
    }

    @Test
    fun emptyIsNothingAndDeadSourceIsUnknown() = runBlocking {
        val off = Api(VenueClient.Answer.Unreachable)
        assertEquals(AroundPlaces.Nothing, MapAroundSource(off, local(GeoResult.Ok(emptyList()))).places(50.0, 14.0))
        val dead = GeoResult.Unavailable(SourceUnavailable.Transport("x"))
        assertEquals(AroundPlaces.Unknown, MapAroundSource(off, local(dead)).places(50.0, 14.0))
    }
}
