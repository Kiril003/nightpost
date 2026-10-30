package local.phantom.companion.data

import local.phantom.companion.core.ai.context.FixGrade
import local.phantom.companion.core.ai.context.FixRegister
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.peers.PeerPointsRepository
import local.phantom.companion.core.data.peers.PeerPrecision
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.net.peer.PeerWhere
import local.phantom.companion.core.net.peer.PeerWhereGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val T0 = 1_790_000_000_000L

/**
 * Положення людей не лягає на флеш: ні вхідне, ні вихідне, ні на мить.
 * Диск тут — справжній [PeerOutboxStore] над слотом, що памʼятає КОЖЕН запис,
 * бо стертий пізніше рядок на флеші однаково лишає шифротекст.
 */
class PeerWhereNoTraceTest {

    private class RecordingSlot : PeerLogSlot {
        val cells = HashMap<String, String>()
        val everWritten = mutableListOf<String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
            value?.let { everWritten += it }
        }
    }

    private class MemoryGrants : PeerWhereGrants {
        var saved: Map<String, PeerWhere.Precision> = emptyMap()
        override fun load() = saved
        override fun save(grants: Map<String, PeerWhere.Precision>) {
            saved = grants
        }
    }

    private fun friend(id: String) = PeerIdentity(
        id = id,
        name = "Оксана",
        kind = PeerIdentity.Kind.PERSON,
        pubKey = "pub-$id",
        dhPubKey = "dh-$id",
        verifiedAtMs = 1L,
        keySource = PeerIdentity.KeySource.CAMERA,
        introducedAtMs = 1L,
    )

    private val lat = 50.4501234
    private val lon = 30.5234567

    private fun traces(text: String): List<String> {
        val raw = listOf(Math.round(lat * 1e7).toString(), Math.round(lon * 1e7).toString(), "50.450", "30.523")
        return raw.filter { it in text } + listOfNotNull(PeerWhere.TAG.takeIf { it in text })
    }

    @Test
    fun `ні вхідне, ні вихідне положення не торкається диска, а після строку немає й у памʼяті`() {
        var now = T0
        val slot = RecordingSlot()
        val store = PeerOutboxStore(slot)
        val log = PeerOutboxHolder(save = store::save)
        val points = PeerPointsRepository { now }
        val oksana = friend("oksana")
        val hub = PeerWhereHub(
            points = points,
            roster = { listOf(oksana) },
            fix = { LocationFix(lat, lon, accuracyM = 8f, grade = FixGrade.NAVIGATION, register = FixRegister.MEASURED) },
            send = { to, body ->
                log.publish { PeerOutbox.enqueue(it, PeerMessage("out-$now-${it.size}", "me", to, body, now)) }
            },
            fixAllowed = { true },
            grantStore = MemoryGrants(),
            outbox = { log.messages.value },
            nowMs = { now },
        )
        log.publish { PeerOutbox.enqueue(it, PeerMessage("hello", "oksana", "me", "привіт", now)) }

        assertTrue(hub.setPrecision("oksana", PeerWhere.Precision.EXACT))
        assertTrue("кадр не пішов", log.messages.value.any { PeerWhere.isWhere(it.body) })

        val cell = PeerWhereGrid.cellOf(lat, lon, PeerWhereGrid.Level.QUARTER)
        val theirs = PeerWhere.encode(PeerWhere.Area(cell, now, now + 20 * 60_000L, 1))!!
        hub.absorb(PeerMessage("in-1", "oksana", "me", theirs, now))
        val point = PeerWhere.encode(PeerWhere.Point(lat, lon, 8, now + 1, now + 10 * 60_000L, 2))!!
        hub.absorb(PeerMessage("in-2", "oksana", "me", point, now + 1))
        assertNotNull(points.points.value.singleOrNull { it.precision == PeerPrecision.EXACT })

        // Навіть рядок, що пройшов би старими дверима журналу, диск не бере.
        log.publish { PeerOutbox.enqueue(it, PeerMessage("in-3", "oksana", "me", theirs, now)) }

        assertTrue(log.flush())
        assertTrue("жодного запису не було — перевірка сліпа", slot.everWritten.isNotEmpty())
        slot.everWritten.forEach { written ->
            assertEquals("слід положення на диску", emptyList<String>(), traces(written))
        }
        assertTrue(slot.everWritten.last().contains("привіт"))

        now += 31 * 60_000L
        hub.sweep()
        assertTrue("чуже положення пережило строк", points.points.value.isEmpty())
        slot.everWritten.forEach { assertEquals(emptyList<String>(), traces(it)) }
        assertFalse(PeerOutboxStore(RecordingSlot().also { it.cells.putAll(slot.cells) }).load().any { PeerWhere.isWhere(it.body) })
    }
}
