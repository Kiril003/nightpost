package local.phantom.companion.core.net.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates [decodeFeedMessage] — the hand-rolled protobuf wire-format
 * decoder (see [ProtoReader]'s doc comment for why it isn't the generated
 * `gtfs-realtime-bindings` library) — against a real MBTA `VehiclePositions`
 * snapshot fixture. The expected counts/values here were captured by
 * parsing the SAME fixture bytes with the actual `org.mobilitydata:
 * gtfs-realtime-bindings` library before it was removed from the build
 * (duplicate-class conflict with MediaPipe's protobuf-javalite) — this
 * pins the hand-rolled decoder to that known-good ground truth rather than
 * just trusting it compiles.
 */
class GtfsRealtimeDecodeTest {
    private fun fixtureBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("mbta_vehicle_positions_fixture.pb")!!.use { it.readBytes() }

    @Test
    fun `decodes every vehicle entity in the real feed`() {
        val vehicles = decodeFeedMessage(fixtureBytes())
        assertEquals(518, vehicles.size)
    }

    @Test
    fun `field presence counts match the reference parse`() {
        val vehicles = decodeFeedMessage(fixtureBytes())
        assertEquals(436, vehicles.count { it.bearingDegrees != null })
        assertEquals(375, vehicles.count { it.occupancy != VehicleOccupancy.UNKNOWN })
        assertEquals(518, vehicles.count { it.tripId != null })
    }

    @Test
    fun `specific known vehicles decode to the exact reference values`() {
        val vehicles = decodeFeedMessage(fixtureBytes()).associateBy { it.vehicleId }

        val mattapan = vehicles.getValue("G-10121")
        assertEquals(42.27, mattapan.lat, 1e-6)
        assertEquals(-71.07331, mattapan.lon, 1e-6)
        assertEquals(90.0f, mattapan.bearingDegrees)
        assertEquals("Mattapan", mattapan.routeId)

        val greenE = vehicles.getValue("G-10119")
        assertEquals(42.33791, greenE.lat, 1e-6)
        assertEquals(-71.09507, greenE.lon, 1e-6)
        assertEquals(225.0f, greenE.bearingDegrees)
        assertEquals("Green-E", greenE.routeId)

        val bus33 = vehicles.getValue("y1704")
        assertEquals(42.24934, bus33.lat, 1e-6)
        assertEquals(-71.13932, bus33.lon, 1e-6)
        assertEquals(340.0f, bus33.bearingDegrees)
        assertEquals(VehicleOccupancy.MANY_SEATS_AVAILABLE, bus33.occupancy)
        assertEquals("33", bus33.routeId)
    }

    @Test
    fun `never crashes on unknown or malformed bytes`() {
        assertTrue(decodeFeedMessage(ByteArray(0)).isEmpty())
        // Truncated/garbage input must degrade, never throw, per this
        // codebase's honesty rule — GtfsRealtimeClient.fetchVehiclePositions
        // wraps this in runCatching too, but the decoder itself should be
        // robust on its own.
        runCatching { decodeFeedMessage(byteArrayOf(0x08, 0x02, 0x12)) }
            .onFailure { throw AssertionError("decodeFeedMessage should not throw on truncated input", it) }
    }

    // ── Lviv production testbed (track.ua-gis.com) ────────────────────────
    // The deployed feed the Obsidian Prisms actually render over. No
    // reference-library ground truth exists for this one (bindings were
    // already gone), so these assert structural sanity: real vehicles, all
    // physically inside the Lviv area, with the fields the renderer needs.

    private fun lvivBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("lviv_vehicle_positions_fixture.pb")!!.use { it.readBytes() }

    @Test
    fun `Lviv feed decodes a plausible fleet`() {
        val vehicles = decodeFeedMessage(lvivBytes())
        // The captured snapshot held this many active vehicles; a decoder
        // regression that silently dropped fields would collapse this.
        assertTrue("expected a real fleet, got ${vehicles.size}", vehicles.size > 100)
        assertTrue("every vehicle needs a stable id to pool a prism slot", vehicles.all { it.vehicleId.isNotBlank() })
    }

    @Test
    fun `every Lviv vehicle sits inside the Lviv bounding box`() {
        val vehicles = decodeFeedMessage(lvivBytes())
        // Lviv metro area, generous margin. A byte-misalignment in the
        // hand-rolled float decode would scatter coords far outside this.
        val outside = vehicles.filterNot { it.lat in 49.5..50.1 && it.lon in 23.7..24.4 }
        assertTrue("all coords should be in the Lviv bbox; strays: ${outside.take(5)}", outside.isEmpty())
    }

    @Test
    fun `Lviv feed populates the fields the prism overlay renders`() {
        val vehicles = decodeFeedMessage(lvivBytes())
        // Bearing drives prism rotation; a feed with zero bearings would
        // mean every prism points north — worth knowing the feed carries it.
        assertTrue("feed should carry bearings for rotation", vehicles.any { it.bearingDegrees != null })
        vehicles.mapNotNull { it.bearingDegrees }.forEach {
            assertTrue("bearing $it out of [0,360)", it in 0f..360f)
        }
    }
}
