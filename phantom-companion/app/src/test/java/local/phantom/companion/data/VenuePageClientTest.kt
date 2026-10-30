package local.phantom.companion.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import local.phantom.companion.feature.stream.ui.map.venue.RsvpResult
import local.phantom.companion.feature.stream.ui.map.venue.VenueLoad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VenuePageClientTest {

    private val venueId = "0123456789abcdef"
    private val eventId = "$venueId.ev_1"
    private val pageJson = """
        {
          "id": "$venueId",
          "name": "Kavárna",
          "blocks": [
            {"id": "h1", "type": "hours", "rows": [{"day": "mon", "open": "09:00", "close": "18:00"}]},
            {"id": "ev_1", "type": "event", "title": "Chess", "starts": "2026-05-01T18:00"}
          ]
        }
    """.trimIndent()
    private val factsJson = """
        {
          "venue_id": "$venueId",
          "verified": true,
          "osm": {"type": "node", "id": 1},
          "events": [
            {"event_id": "$eventId", "venue_id": "$venueId", "title": "Chess", "starts": "2026-05-01T18:00", "going": 4, "capacity": 16, "cancelled": false}
          ]
        }
    """.trimIndent()

    private val proof = VenueClient.Proof("pubB64", "a".repeat(32), 1700000000000L, "sigB64")

    @Test
    fun `load asks the page and the facts`() = runTest {
        val seen = mutableListOf<Pair<String, String>>()
        val client = VenuePageClient(
            baseUrl = "https://scene.test/",
            signer = { proof },
            http = { m, url, _ ->
                seen += m to url
                when (url) {
                    "https://scene.test/venues/$venueId" -> 200 to pageJson
                    "https://scene.test/venues/$venueId/facts" -> 200 to factsJson
                    else -> 404 to """{"detail":"Not Found"}"""
                }
            },
        )
        val res = client.load(venueId) as VenueLoad.Ready
        assertEquals(
            listOf(
                "GET" to "https://scene.test/venues/$venueId",
                "GET" to "https://scene.test/venues/$venueId/facts",
            ),
            seen,
        )
        assertTrue(res.card.verified)
        assertEquals(4, res.card.events.single().going)
        assertEquals(16, res.card.events.single().capacity)
    }

    @Test
    fun `load survives a 404 facts door`() = runTest {
        val client = VenuePageClient(
            baseUrl = "https://scene.test",
            signer = { proof },
            http = { _, url, _ ->
                if (url.endsWith("/facts")) 404 to """{"detail":"Not Found"}"""
                else 200 to pageJson
            },
        )
        val res = client.load(venueId) as VenueLoad.Ready
        assertFalse(res.card.verified)
        assertEquals(eventId, res.card.events.single().eventId)
        assertNull(res.card.events.single().going)
    }

    @Test
    fun `load maps off, missing and network loss`() = runTest {
        val off = VenuePageClient(baseUrl = "", signer = { proof }, http = { _, _, _ -> 200 to pageJson })
        assertEquals(VenueLoad.Off, off.load(venueId))

        val missing = VenuePageClient(
            baseUrl = "https://scene.test",
            signer = { proof },
            http = { _, _, _ -> 404 to """{"refused":"not_found"}""" },
        )
        assertEquals(VenueLoad.Missing, missing.load(venueId))

        val noRoute = VenuePageClient(
            baseUrl = "https://scene.test",
            signer = { proof },
            http = { _, _, _ -> 404 to """{"detail":"Not Found"}""" },
        )
        assertEquals(VenueLoad.Off, noRoute.load(venueId))

        val dead = VenuePageClient(
            baseUrl = "https://scene.test",
            signer = { proof },
            http = { _, _, _ -> null },
        )
        assertEquals(VenueLoad.Failed, dead.load(venueId))
    }

    @Test
    fun `bad venue or event id never reaches the network`() = runTest {
        var calls = 0
        val client = VenuePageClient(
            baseUrl = "https://scene.test",
            signer = { calls++; proof },
            http = { _, _, _ -> calls++; 200 to pageJson },
        )
        assertEquals(VenueLoad.Missing, client.load("not-hex"))
        assertEquals(VenueLoad.Missing, client.load("0123456789ABCDEF"))
        assertEquals(RsvpResult.Failed, client.rsvp("bad", true))
        assertEquals(RsvpResult.Failed, client.rsvp("$venueId.", true))
        assertEquals(RsvpResult.Failed, client.rsvp("$venueId.${"x".repeat(37)}", true))
        assertEquals(0, calls)
    }

    @Test
    fun `rsvp posts the contract body to the event path`() = runTest {
        var method: String? = null
        var url: String? = null
        var body: String? = null
        var signedClaim: DeviceClaim? = null
        val client = VenuePageClient(
            baseUrl = "https://scene.test",
            signer = { claim -> signedClaim = claim; proof },
            http = { m, u, json ->
                method = m
                url = u
                body = json
                200 to """{"event_id":"$eventId","going":true,"count":8,"capacity":20}"""
            },
        )
        val res = client.rsvp(eventId, true)
        assertEquals(RsvpResult.Done(going = true, count = 8, capacity = 20), res)
        assertEquals(EventRsvpClaim(eventId, true), signedClaim)
        assertEquals("POST", method)
        assertEquals("https://scene.test/events/$eventId/rsvp", url)
        val sent = Json.parseToJsonElement(body!!).jsonObject
        assertEquals("pubB64", sent["pub_key"]!!.jsonPrimitive.content)
        assertEquals("a".repeat(32), sent["nonce"]!!.jsonPrimitive.content)
        assertEquals(1700000000000L, sent["issued_ms"]!!.jsonPrimitive.long)
        assertEquals("sigB64", sent["signature"]!!.jsonPrimitive.content)
        assertTrue(sent["going"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `rsvp sentence equals the server sentence byte for byte`() {
        assertEquals(
            "phantom-event-rsvp|v1|pubK|nonce32|99|0123456789abcdef.b1|1",
            VenuePageClient.rsvpSentence("pubK", "nonce32", 99L, "0123456789abcdef.b1", true),
        )
        assertEquals(
            "phantom-event-rsvp|v1|pubK|nonce32|99|0123456789abcdef.b1|0",
            EventRsvpClaim("0123456789abcdef.b1", false).sentence("pubK", "nonce32", 99L),
        )
    }

    @Test
    fun `rsvp maps full, cancelled, over and no key`() = runTest {
        fun clientWith(reply: Pair<Int, String>?, hasKey: Boolean = true) = VenuePageClient(
            baseUrl = "https://scene.test",
            signer = { if (hasKey) proof else null },
            http = { _, _, _ -> reply },
        )
        assertEquals(
            RsvpResult.Full,
            clientWith(409 to """{"refused":"event_full"}""").rsvp(eventId, true),
        )
        assertEquals(
            RsvpResult.Cancelled,
            clientWith(409 to """{"refused":"event_cancelled"}""").rsvp(eventId, true),
        )
        assertEquals(
            RsvpResult.Over,
            clientWith(409 to """{"refused":"event_over"}""").rsvp(eventId, true),
        )
        assertEquals(
            RsvpResult.NoKey,
            clientWith(200 to "{}", hasKey = false).rsvp(eventId, true),
        )
        assertEquals(
            RsvpResult.Off,
            clientWith(404 to """{"refused":"no_such_event"}""").rsvp(eventId, true),
        )
    }
}
