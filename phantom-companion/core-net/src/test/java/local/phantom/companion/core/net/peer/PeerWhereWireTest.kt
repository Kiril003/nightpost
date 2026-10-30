package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.randomBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

private const val T0 = 1_790_000_000_000L

class PeerWhereWireTest {

    private val friend = whereFriend("bbbb2222")

    private fun districtBodyFor(lat: Double, lon: Double, p: PeerWhere.Precision = PeerWhere.Precision.DISTRICT): String {
        val granted = PeerWhereBeacon.grant(PeerWhereBeacon.State(), friend, p, T0)!!.state
        val step = PeerWhereBeacon.onFix(granted, PeerWhereBeacon.Fix(lat, lon, 7, T0), T0)
        return step.out.single().body
    }

    @Test
    fun `тіло району однакове для двох різних точок однієї клітинки`() {
        val cell = PeerWhereGrid.cellOf(50.4501, 30.5234, PeerWhereGrid.Level.DISTRICT)
        val (cLat, cLon) = PeerWhereGrid.centreOf(cell)
        val a = districtBodyFor(cLat + 0.002, cLon - 0.003)
        val b = districtBodyFor(cLat - 0.004, cLon + 0.005)
        assertEquals(cell, PeerWhereGrid.cellOf(cLat + 0.002, cLon - 0.003, cell.level))
        assertEquals(cell, PeerWhereGrid.cellOf(cLat - 0.004, cLon + 0.005, cell.level))
        assertEquals("у тіло району потрапило щось від точної точки", a, b)
        assertEquals(cell, (PeerWhere.decode(a) as PeerWhere.Area).cell)
    }

    @Test
    fun `у байтах району немає ні градусів, ні числа точки`() {
        val lat = 50.4501234
        val lon = 30.5234567
        val body = districtBodyFor(lat, lon)
        val raw = java.util.Base64.getUrlDecoder().decode(body.removePrefix(PeerWhere.TAG))
        for (v in listOf(Math.round(lat * 1e7).toInt(), Math.round(lon * 1e7).toInt())) {
            val needle = ByteBuffer.allocate(4).putInt(v).array()
            assertFalse("E7 точки лежить у кадрі району", raw.toList().windowed(4).any { it == needle.toList() })
        }
        assertFalse(body.contains("50.45") || body.contains("30.52"))
        val q = districtBodyFor(lat, lon, PeerWhere.Precision.QUARTER)
        assertTrue(PeerWhere.decode(q) is PeerWhere.Area)
    }

    private fun allKinds(): Map<String, String> {
        val cell = PeerWhereGrid.cellOf(50.45, 30.52, PeerWhereGrid.Level.DISTRICT)
        val qcell = PeerWhereGrid.cellOf(50.45, 30.52, PeerWhereGrid.Level.QUARTER)
        return mapOf(
            "район" to PeerWhere.encode(PeerWhere.Area(cell, T0, T0 + 1, 1))!!,
            "квартал" to PeerWhere.encode(PeerWhere.Area(qcell, T0, T0 + 1_800_000, 9_999))!!,
            "точно" to PeerWhere.encode(PeerWhere.Point(-33.8688197, 151.2092955, 65_000, T0, T0 + 900_000, 123_456))!!,
            "точно без похибки" to PeerWhere.encode(PeerWhere.Point(0.0, 0.0, null, T0, T0 + 1, 0))!!,
            "стерти" to PeerWhere.encode(PeerWhere.Erase(T0))!!,
            "де ти" to PeerWhere.encode(PeerWhere.Ask(T0))!!,
        )
    }

    @Test
    fun `кожен рід має одну довжину тіла`() {
        val lengths = allKinds().mapValues { it.value.length }
        assertEquals(lengths.toString(), setOf(PeerWhere.BODY_CHARS), lengths.values.toSet())
    }

    @Test
    fun `запечатаний конверт і блоб сховку однакові для всіх родів`() {
        val key = randomBytes(32)
        val frames = allKinds().mapValues { (_, body) ->
            val m = PeerMessage(
                id = "6e3c1a70-0f51-4a3b-9d2e-71b0c4e5a9f2",
                fromId = "aaaa1111",
                toId = "bbbb2222",
                body = body,
                sentAtMs = T0,
                signature = "s".repeat(86),
            )
            PeerEnvelope.seal(m, key)!!.frame
        }
        assertEquals(frames.mapValues { it.value.length }.toString(), 1, frames.values.map { it.length }.toSet().size)
        val tag = PeerRelay.decoyTags(1, ByteArray(32) { 9 }, 0L).single()
        val blobs = frames.values.map { PeerRelay.wrap(it, key, tag)!!.blob.size }.toSet()
        assertEquals(1, blobs.size)
    }

    @Test
    fun `кожен рід повертається тим самим`() {
        allKinds().forEach { (name, body) ->
            val back = PeerWhere.decode(body)
            assertNotNull(name, back)
            assertEquals(name, body, PeerWhere.encode(back!!))
        }
    }

    private fun mutate(body: String, index: Int, value: Byte): String {
        val raw = java.util.Base64.getUrlDecoder().decode(body.removePrefix(PeerWhere.TAG))
        raw[index] = value
        return PeerWhere.TAG + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    @Test
    fun `набивка не носить нічого`() {
        val body = allKinds().getValue("район")
        assertNull(PeerWhere.decode(mutate(body, PeerWhere.PAYLOAD_BYTES - 1, 1)))
        assertNull("похибка в кадрі району", PeerWhere.decode(mutate(body, 33, 5)))
        assertNull("чужа версія", PeerWhere.decode(mutate(body, 0, 2)))
        assertNull(PeerWhere.decode(body.dropLast(1)))
        assertNull(PeerWhere.decode(body + "A"))
        val erase = allKinds().getValue("стерти")
        assertNull("стерти з координатою", PeerWhere.decode(mutate(erase, 27, 1)))
    }

    @Test
    fun `стан їде в тій самій довжині і дім не проїжджає з районом`() {
        val home = PeerWhere.State(PeerWhere.Motion.FOOT, PeerWhere.Spot.HOME, lowBattery = true)
        val cell = PeerWhereGrid.cellOf(50.45, 30.52, PeerWhereGrid.Level.DISTRICT)
        val quiet = PeerWhere.encode(PeerWhere.Area(cell, T0, T0 + 60_000, 1))!!
        val area = PeerWhere.encode(PeerWhere.Area(cell, T0, T0 + 60_000, 1, home))!!
        val point = PeerWhere.encode(PeerWhere.Point(50.45, 30.52, 9, T0, T0 + 60_000, 1, home))!!
        assertEquals(quiet.length, area.length)
        assertEquals(quiet.length, point.length)
        assertEquals(home.copy(spot = PeerWhere.Spot.NONE), (PeerWhere.decode(area) as PeerWhere.Area).state)
        assertEquals(home, (PeerWhere.decode(point) as PeerWhere.Point).state)
        assertNull("дім у кадрі району", PeerWhere.decode(mutate(quiet, 34, 0x04)))
        assertNull("невідомий біт стану", PeerWhere.decode(mutate(quiet, 34, 0x40)))
        assertNull("стан у «стерти»", PeerWhere.decode(mutate(allKinds().getValue("стерти"), 34, 0x01)))
    }

    @Test
    fun `клітинка поза сіткою і час без строку не складаються`() {
        assertNull(PeerWhere.encode(PeerWhere.Area(PeerWhereGrid.Cell(PeerWhereGrid.Level.DISTRICT, -1, 0), T0, T0 + 1, 1)))
        assertNull(PeerWhere.encode(PeerWhere.Point(10.0, 10.0, null, T0, T0, 1)))
        assertNull(PeerWhere.encode(PeerWhere.Point(91.0, 10.0, null, T0, T0 + 1, 1)))
    }
}

internal fun whereFriend(id: String, proven: Boolean = true, introduced: Boolean = true) = PeerIdentity(
    id = id,
    name = id,
    kind = PeerIdentity.Kind.PERSON,
    pubKey = "pub-$id",
    dhPubKey = "dh-$id",
    verifiedAtMs = if (proven) 1L else 0L,
    keySource = if (proven) PeerIdentity.KeySource.CAMERA else PeerIdentity.KeySource.SHARED,
    introducedAtMs = if (introduced) 1L else 0L,
)
