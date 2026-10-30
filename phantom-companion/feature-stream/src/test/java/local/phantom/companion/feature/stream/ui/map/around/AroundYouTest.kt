package local.phantom.companion.feature.stream.ui.map.around

import local.phantom.companion.core.data.peers.PeerBox
import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.PeerPrecision
import local.phantom.companion.core.data.peers.PeerShare
import local.phantom.companion.core.data.peers.ShareMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AroundYouTest {
    private val now = 1_000_000_000L
    private val me = 50.0 to 14.0

    private fun peer(
        id: String,
        lat: Double,
        lon: Double,
        atMs: Long = now - 60_000,
        mode: ShareMode = ShareMode.WHERE,
        precision: PeerPrecision? = PeerPrecision.EXACT,
        expires: Long? = null,
        box: PeerBox? = null,
    ) = PeerPoint(
        nodeId = id, threadId = "t-$id", name = id, lat = lat, lon = lon, atMs = atMs,
        share = PeerShare(mode, expires), precision = precision, box = box,
    )

    @Test
    fun nearestFriendFirstAndOneRowPerPerson() {
        val rows = aroundFriends(
            listOf(
                peer("far", 50.02, 14.0),
                peer("near", 50.001, 14.0, atMs = now - 600_000),
                peer("near", 50.002, 14.0, atMs = now - 30_000),
            ),
            me, now,
        )
        assertEquals(listOf("near", "far"), rows.map { it.point.nodeId })
        assertEquals(now - 30_000, rows[0].point.atMs)
        assertTrue(rows[0].meters!! in 200.0..250.0)
    }

    @Test
    fun sentPlaceIsNotAPersonAndExpiredShareIsGone() {
        val rows = aroundFriends(
            listOf(
                peer("sent", 50.001, 14.0, mode = ShareMode.ONE_SHOT, precision = null),
                peer("ended", 50.001, 14.0, expires = now - 1),
                peer("live", 50.003, 14.0, mode = ShareMode.LIVE, precision = null),
            ),
            me, now,
        )
        assertEquals(listOf("live"), rows.map { it.point.nodeId })
    }

    @Test
    fun districtIsApproximateAndNoFixMeansNoDistance() {
        val district = peer("d", 50.01, 14.0, precision = PeerPrecision.DISTRICT, box = PeerBox(49.99, 13.98, 50.03, 14.02))
        assertTrue(aroundFriends(listOf(district), me, now).single().approx)
        val blind = aroundFriends(listOf(district), null, now).single()
        assertNull(blind.meters)
    }

    @Test
    fun staleShareStaysListedButNotFresh() {
        val old = aroundFriends(listOf(peer("o", 50.0, 14.0, atMs = now - 60 * 60_000)), me, now).single()
        assertFalse(old.fresh)
    }

    @Test
    fun venuesParseWithRatingAndSkipRowsWithoutPoint() {
        val json = """{"venues":[
            {"id":"aaaaaaaaaaaaaaaa","name":"Mill Lane Coffee","address":"4 Mill Lane","lat":50.001,"lon":14.0,
             "cell":"x","token":"USDC","menu_version":1,"rating":{"mean":4.67,"count":6,"sum":28}},
            {"id":"bbbbbbbbbbbbbbbb","name":"No point","address":null,"lat":null,"lon":null,"rating":null},
            {"id":"cccccccccccccccc","name":"Fresh","address":"1 St","lat":50.002,"lon":14.0,"rating":null}
        ]}"""
        val places = parseVenuesNear(json)!!
        assertEquals(listOf("Mill Lane Coffee", "Fresh"), places.map { it.name })
        assertEquals(AroundRating(4.67, 6), places[0].rating)
        assertEquals("aaaaaaaaaaaaaaaa", places[0].venueId)
        assertNull(places[1].rating)
        assertNull(parseVenuesNear("not json"))
    }

    @Test
    fun placesSortedByDistanceAndCapped() {
        val many = (1..12).map { AroundPlace("p$it", 50.0 + it * 0.001, 14.0) }.reversed()
        val got = nearestPlaces(many, 50.0, 14.0)
        assertEquals(PLACES_LIMIT, got.size)
        assertEquals("p1", got.first().name)
    }

    @Test
    fun distanceWords() {
        assertEquals("340 m", aroundDistance(343.0, false, "m", "km", Locale.US))
        assertEquals("10 m", aroundDistance(2.0, false, "m", "km", Locale.US))
        assertEquals("1.4 km", aroundDistance(1_420.0, false, "m", "km", Locale.US))
        assertEquals("1,4 км", aroundDistance(1_420.0, false, "м", "км", Locale("uk")))
        assertEquals("≈ 2 km", aroundDistance(1_700.0, true, "m", "km", Locale.US))
        assertEquals("≈ 1 km", aroundDistance(200.0, true, "m", "km", Locale.US))
    }
}
