package local.phantom.companion.feature.stream.ui.map.peers

import kotlinx.coroutines.flow.MutableStateFlow
import local.phantom.companion.core.data.peers.MyWorldSummary
import local.phantom.companion.core.data.peers.PEER_LIVE_FRESH_WINDOW_MS
import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.PeerPrecision
import local.phantom.companion.core.data.peers.PeerSelfShow
import local.phantom.companion.core.data.peers.PeerShare
import local.phantom.companion.core.data.peers.ShareMode
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.around.AroundPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleMeetFairPlaceTest {

    private val now = 1_724_500_000_000L

    @Test
    fun `pickFairMeetVenues chooses venue with most even walk times for Iva, Daniel and Ana`() {
        val participants = listOf(
            "Iva" to (50.4531 to 30.5200),
            "Daniel" to (50.4473 to 30.5225),
            "Ana" to (50.4473 to 30.5175),
        )
        val venues = listOf(
            AroundPlace(
                name = "Skewed Espresso",
                lat = 50.4540,
                lon = 30.5200,
                venueId = "v-skewed",
            ),
            AroundPlace(
                name = "Pho Corner",
                lat = 50.4500,
                lon = 30.5200,
                venueId = "v-pho",
            ),
        )

        val ranked = pickFairMeetVenues(participants, venues)

        assertEquals(2, ranked.size)
        val best = ranked.first()
        assertEquals("Pho Corner", best.name)
        assertEquals("v-pho", best.venueId)
        assertTrue("Expected spread <= 2, got ${best.maxSpreadMinutes}", best.maxSpreadMinutes <= 2)
        assertEquals(setOf("Iva", "Daniel", "Ana"), best.etasMinutes.keys)
        assertTrue(best.etasMinutes.values.all { it > 0 })
    }

    @Test
    fun `pickFairMeetVenues returns empty list when venues is empty`() {
        val participants = listOf(
            "Iva" to (50.4531 to 30.5200),
            "Daniel" to (50.4473 to 30.5225),
        )
        assertTrue(pickFairMeetVenues(participants, emptyList()).isEmpty())
    }

    @Test
    fun `pickFairMeetVenues breaks spread ties by minimum total walk time`() {
        val participants = listOf(
            "Iva" to (50.4510 to 30.5200),
            "Daniel" to (50.4490 to 30.5200),
        )
        val venues = listOf(
            AroundPlace(name = "Far Midpoint", lat = 50.4500, lon = 30.5350, venueId = "v-far"),
            AroundPlace(name = "Close Midpoint", lat = 50.4500, lon = 30.5200, venueId = "v-close"),
        )

        val ranked = pickFairMeetVenues(participants, venues)

        assertEquals("Close Midpoint", ranked.first().name)
    }

    @Test
    fun `timed live share stops at expiry and sends nothing after expiry`() {
        val port = RecordingPeoplePort()
        val controller = LiveShareController(port, shareWords = "Share my location", arrivedWords = "Arrived")
        val recipients = listOf(
            ShareRecipient(peerId = "t-iva", name = "Iva", eligible = true, inClose = true),
        )

        controller.start(
            recipients = recipients,
            audienceId = null,
            duration = LiveShareDuration.MIN_15,
            me = 50.4500 to 30.5200,
            nowMs = now,
        )
        assertEquals(1, port.sentPlaces.size)
        assertTrue(controller.isSharingAt(now + 5 * 60_000L, recipients, port.hidden.value))
        assertTrue(controller.onLocationOrTick(50.4510 to 30.5200, now + 5 * 60_000L))
        assertEquals(2, port.sentPlaces.size)

        val expiryMs = now + 15 * 60_000L
        assertFalse(controller.onLocationOrTick(50.4520 to 30.5200, expiryMs))
        assertEquals(2, port.sentPlaces.size)
        assertFalse(controller.isSharingAt(expiryMs, recipients, port.hidden.value))
        assertEquals(Long.MAX_VALUE, port.hidden.value["t-iva"])

        assertFalse(controller.onLocationOrTick(50.4530 to 30.5200, expiryMs + 60_000L))
        assertEquals(2, port.sentPlaces.size)
    }

    @Test
    fun `stop holds indefinitely and does not resume after 8 hours until started again`() {
        val port = RecordingPeoplePort()
        val controller = LiveShareController(port, shareWords = "Share my location", arrivedWords = "Arrived")
        val recipients = listOf(
            ShareRecipient(peerId = "t-iva", name = "Iva", eligible = true, inClose = true),
        )

        assertTrue(controller.isSharingAt(now, recipients, port.hidden.value))
        controller.stop(recipients)

        val afterEightHours = now + 8 * 3_600_000L + 1_000L
        val afterSevenDays = now + 7 * 24 * 3_600_000L
        assertFalse(controller.isSharingAt(afterEightHours, recipients, port.hidden.value))
        assertFalse(controller.isSharingAt(afterSevenDays, recipients, port.hidden.value))
        assertFalse(controller.onLocationOrTick(50.4510 to 30.5200, afterEightHours))
        assertTrue(port.sentPlaces.isEmpty())

        controller.start(
            recipients = recipients,
            audienceId = null,
            duration = LiveShareDuration.HOUR_1,
            me = 50.4510 to 30.5200,
            nowMs = afterEightHours + 60_000L,
        )
        assertTrue(controller.isSharingAt(afterEightHours + 120_000L, recipients, port.hidden.value))
        assertEquals(1, port.sentPlaces.size)
    }

    @Test
    fun `friend shared at approximate precision is not Close and gets no live share`() {
        val rows = listOf(
            local.phantom.companion.core.data.peers.PeerSeeRow("t-iva", "Iva", PeerPrecision.EXACT, eligible = true, inClose = true),
            local.phantom.companion.core.data.peers.PeerSeeRow("t-dan", "Daniel", PeerPrecision.QUARTER, eligible = true),
        )

        val targets = resolveShareTargets(shareRecipients(rows, emptyMap()), audienceId = null)

        assertEquals(listOf("t-iva"), targets.map { it.peerId })
    }

    @Test
    fun `null audience shares only with Close circle and gives non-Close eligible peer nothing`() {
        val port = RecordingPeoplePort()
        val controller = LiveShareController(port, shareWords = "Share my location", arrivedWords = "Arrived")
        val recipients = listOf(
            ShareRecipient(peerId = "t-iva", name = "Iva", eligible = true, inClose = true),
            ShareRecipient(peerId = "t-oleg", name = "Oleg", eligible = true, inClose = false),
            ShareRecipient(peerId = "t-ghost", name = "Ghost", eligible = false, inClose = true),
        )

        controller.start(
            recipients = recipients,
            audienceId = null,
            duration = LiveShareDuration.MIN_15,
            me = 50.4500 to 30.5200,
            nowMs = now,
        )

        assertEquals(listOf("t-iva"), port.sentPlaces.map { it.peerId })
    }

    @Test
    fun `dispatchFairMeet sends only to participants in the chosen candidate etas`() {
        val port = RecordingPeoplePort()
        val candidate = FairMeetCandidate(
            name = "Pho Corner",
            lat = 50.4500,
            lon = 30.5200,
            venueId = "v-pho",
            etasMinutes = linkedMapOf("You" to 5, "Iva" to 4, "Daniel" to 6),
            maxSpreadMinutes = 2,
        )
        val eligiblePeers = listOf(
            "t-iva" to "Iva",
            "t-daniel" to "Daniel",
            "t-oleg" to "Oleg",
        )

        val meeting = dispatchFairMeet(
            port = port,
            candidate = candidate,
            timeLabel = "18:45",
            meetAtLabel = "Meet at Pho Corner",
            arrivedWords = "Arrived",
            eligiblePeers = eligiblePeers,
            nowMs = now,
        )

        assertNotNull(meeting)
        assertEquals(listOf("t-iva", "t-daniel"), port.sentPlaces.map { it.peerId })
        assertEquals(listOf("t-iva" to "Iva", "t-daniel" to "Daniel"), meeting!!.members)
    }

    @Test
    fun `dispatchFairMeet includes the selected time chip in the sent message`() {
        val port = RecordingPeoplePort()
        val candidate = FairMeetCandidate(
            name = "Pho Corner",
            lat = 50.4500,
            lon = 30.5200,
            venueId = "v-pho",
            etasMinutes = linkedMapOf("You" to 5, "Iva" to 4),
            maxSpreadMinutes = 1,
        )

        dispatchFairMeet(
            port = port,
            candidate = candidate,
            timeLabel = "18:45",
            meetAtLabel = "Meet at Pho Corner",
            arrivedWords = "Arrived",
            eligiblePeers = listOf("t-iva" to "Iva"),
            nowMs = now,
        )

        assertEquals(1, port.sentPlaces.size)
        assertEquals("Meet at Pho Corner · 18:45", port.sentPlaces.single().words)
    }

    @Test
    fun `labels use map_people_you for self and distinct plurals for minutes away vs minutes ago`() {
        assertEquals(R.string.map_people_you, fairMeetSelfLabelRes())
        assertNotEquals(R.string.map_people_title, fairMeetSelfLabelRes())

        val origin = 50.4500 to 30.5200
        val livePeer = PeerPoint(
            nodeId = "iva",
            threadId = "t-iva",
            name = "Iva",
            lat = 50.4529,
            lon = 30.5200,
            atMs = now,
            verified = true,
            stale = false,
            share = PeerShare(ShareMode.LIVE),
            precision = PeerPrecision.EXACT,
        )
        val stalePeer = PeerPoint(
            nodeId = "daniel",
            threadId = "t-daniel",
            name = "Daniel",
            lat = 50.4600,
            lon = 30.5200,
            atMs = now - 14 * 60_000L,
            verified = true,
            stale = false,
            share = PeerShare(ShareMode.LIVE),
        )
        assertTrue(14 * 60_000L > PEER_LIVE_FRESH_WINDOW_MS)

        val participants = buildFairMeetParticipants("You", origin, listOf(livePeer), now)
        assertEquals(listOf("You", "Iva"), participants.map { it.first })

        val figures = peerFigures(listOf(livePeer, stalePeer), now, origin)
        assertEquals(R.plurals.map_minutes_away, figures[0].tagPluralsRes)
        assertEquals(4, figures[0].tagMinutes)
        assertEquals("4 min away", figures[0].tag)

        assertEquals(R.plurals.map_minutes_ago, figures[1].tagPluralsRes)
        assertEquals(14, figures[1].tagMinutes)
        assertEquals("14 min ago", figures[1].tag)

        val walkTag = peerWalkAwayTag(origin, livePeer.lat, livePeer.lon)
        assertNotNull(walkTag)
        assertEquals(R.plurals.map_minutes_away, walkTag!!.pluralsRes)
        assertEquals(4, walkTag.minutes)

        val candidate = FairMeetCandidate(
            name = "Pho Corner",
            lat = 50.4500,
            lon = 30.5200,
            venueId = "v-pho",
            etasMinutes = linkedMapOf("You" to 5, "Iva" to 4),
            maxSpreadMinutes = 1,
        )
        val etaTags = fairMeetEtaTags(candidate)
        assertTrue(etaTags.all { it.second.pluralsRes == R.plurals.map_minutes_away })

        val json = peerFiguresGeoJson(figures)
        assertTrue(json.contains("\"tag\":\"4 min away\""))
        assertTrue(json.contains("\"tag\":\"14 min ago\""))
    }
}

private class RecordingPeoplePort : MapPeoplePort {
    data class SentPlace(val peerId: String, val lat: Double, val lon: Double, val words: String)

    override val self = MutableStateFlow(PeerSelfShow())
    override val hidden = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val world = MutableStateFlow(MyWorldSummary())

    val sentPlaces = mutableListOf<SentPlace>()
    val arrivalRegistrations = mutableListOf<List<String>>()

    override fun hide(peerId: String?, untilMs: Long) {
        val next = hidden.value.toMutableMap()
        if (peerId != null) {
            if (untilMs <= 0L) next.remove(peerId) else next[peerId] = untilMs
        } else {
            next.keys.toList().forEach { k -> next[k] = untilMs }
            next[""] = untilMs
        }
        hidden.value = next
    }

    override fun shareLowBattery(on: Boolean) = Unit

    override fun say(peerId: String, words: String): Boolean = true

    override fun sendPlace(peerId: String, lat: Double, lon: Double, words: String): Boolean {
        sentPlaces += SentPlace(peerId, lat, lon, words)
        return true
    }

    override fun tellOnArrival(peerIds: List<String>, lat: Double, lon: Double, words: String) {
        arrivalRegistrations += peerIds
    }

    override fun forgetWorld() = Unit
}
