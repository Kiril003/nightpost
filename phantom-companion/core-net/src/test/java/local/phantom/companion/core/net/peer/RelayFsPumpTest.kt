package local.phantom.companion.core.net.peer

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

private const val T0 = 1_790_000_000_000L

/**
 * Конверт v5 через справжній насос сховка: лист сплячому їде з одноразовим
 * ключем, сервер тримає байти, отримувач відчиняє, стирає ключ — і порядок
 * «записано, потім спалено» не міняється.
 */
class RelayFsPumpTest {

    private class Store : PeerRelayTransport {
        val boxes = LinkedHashMap<String, Pair<String, ByteArray>>()
        private var seq = 0
        override suspend fun drop(tag: String, blob: ByteArray) = PeerRelayTransport.Dropped(
            PeerRelayTransport.Drop.HELD, T0 + PeerRelay.TTL_MS,
        ).also { boxes["b${seq++}"] = tag to blob }
        override suspend fun fetch(tags: List<String>): PeerRelayTransport.Pickup {
            val asked = tags.toSet()
            return PeerRelayTransport.Pickup(boxes.filter { it.value.first in asked }.map { PeerRelayTransport.Blob(it.key, it.value.first, it.value.second) }, T0 - 1)
        }
        override suspend fun burn(ids: List<String>): Boolean { ids.forEach { boxes.remove(it) }; return true }
    }

    private class Mem : RelayFsKeys.Slot {
        var v: String? = null
        override fun read() = v
        override fun write(value: String) { v = value }
    }

    private class Phone(val id: String) {
        val ed = Ed25519.generateKeypair()
        val dh = X25519.generateKeypair()
        val me = RelayFs.Identity(ed.publicKey, dh.publicKey)
        val keys = RelayFsKeys(Mem(), { T0 })
    }

    private class World(val me: Phone, val them: Phone) : PeerRelayPump.World {
        var messages: List<PeerMessage> = emptyList()
        override fun selfId() = me.id
        override fun contacts() = listOf(them.id)
        override fun pairKey(peerId: String) =
            PeerChannel.derive(me.dh.privateKey, Base64.getEncoder().encodeToString(them.dh.publicKey), me.id, them.id)
        override fun signingKey(peerId: String) = them.ed.publicKey
        override fun outbox() = messages
        override fun publish(transform: (List<PeerMessage>) -> List<PeerMessage>) { messages = transform(messages) }
        override fun persist(message: PeerMessage): Boolean { messages = PeerOutbox.enqueue(messages, message); return true }
        override fun decoySecret(): ByteArray = PeerRelay.decoySecret(me.dh.privateKey)!!
        override fun relayFsSeal(message: PeerMessage) = RelayFsMail.seal(message, me.me, me.dh.privateKey, them.me, me.keys)
        override fun relayFsOpen(raw: String, peerId: String) = RelayFsMail.open(raw, me.me, me.dh.privateKey, peerId, them.me, me.keys)
        override fun relayFsLanded(raw: String, peerId: String) = RelayFsMail.landed(raw, peerId, me.keys)
    }

    @Before fun on() { RelayFs.Flag.enabled = true }

    @After fun off() { RelayFs.Flag.enabled = false }

    @Test fun `лист сплячому їде v5, відчиняється, ключ стирається, копія палиться після запису`() = runBlocking {
        val store = Store()
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        a.keys.absorb(b.id, b.keys.bundleFor(a.id, b.ed.privateKey)!!)
        val worldA = World(a, b)
        val worldB = World(b, a)
        val pumpA = PeerRelayPump(worldA, PeerRelayCourier(store) { T0 }) { T0 }
        val pumpB = PeerRelayPump(worldB, PeerRelayCourier(store) { T0 }) { T0 }

        val letter = PeerSealing.seal(PeerMessage(id = "m-1", fromId = a.id, toId = b.id, body = "без інтернету", sentAtMs = T0), a.ed.privateKey)
        worldA.messages = PeerOutbox.enqueue(emptyList(), letter)
        assertTrue(pumpA.handOff(letter))
        assertEquals(PeerMessage.Sealing.ONE_TIME_KEY, worldA.messages.single().sealing)
        val opksBefore = b.keys.snapshot().opks.size

        val report = pumpB.poll()
        assertEquals(1, report.arrived)
        assertEquals("без інтернету", worldB.messages.single { it.id == "m-1" }.body)
        assertEquals(opksBefore - 1, b.keys.snapshot().opks.size)
        assertEquals("копію листа спалено після запису", 1, report.burned)
    }

    @Test fun `прапорець вимкнено — той самий насос шле v3, як досі`() = runBlocking {
        RelayFs.Flag.enabled = false
        val store = Store()
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        a.keys.absorb(b.id, b.keys.bundleFor(a.id, b.ed.privateKey)!!)
        val worldA = World(a, b)
        val pumpA = PeerRelayPump(worldA, PeerRelayCourier(store) { T0 }) { T0 }
        val letter = PeerSealing.seal(PeerMessage(id = "m-2", fromId = a.id, toId = b.id, body = "v3", sentAtMs = T0), a.ed.privateKey)
        worldA.messages = PeerOutbox.enqueue(emptyList(), letter)
        assertTrue(pumpA.handOff(letter))
        assertEquals(PeerMessage.Sealing.PAIR_KEY, worldA.messages.single().sealing)
        val worldB = World(b, a)
        assertEquals(1, PeerRelayPump(worldB, PeerRelayCourier(store) { T0 }) { T0 }.poll().arrived)
        assertNotNull(worldB.messages.singleOrNull { it.id == "m-2" })
        assertEquals(RelayFsKeys.OPK_BATCH, a.keys.opksLeftFor(b.id))
    }

    @Test fun `копію v5, віддану вдруге, підтверджено й спалено, але не прийнято вдруге`() = runBlocking {
        val store = Store()
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        a.keys.absorb(b.id, b.keys.bundleFor(a.id, b.ed.privateKey)!!)
        val worldA = World(a, b)
        val worldB = World(b, a)
        val pumpA = PeerRelayPump(worldA, PeerRelayCourier(store) { T0 }) { T0 }
        val pumpB = PeerRelayPump(worldB, PeerRelayCourier(store) { T0 }) { T0 }
        val letter = PeerSealing.seal(PeerMessage(id = "m-3", fromId = a.id, toId = b.id, body = "раз", sentAtMs = T0), a.ed.privateKey)
        worldA.messages = PeerOutbox.enqueue(emptyList(), letter)
        assertTrue(pumpA.handOff(letter))
        val copy = store.boxes.values.single()

        val first = pumpB.poll()
        assertEquals(1, first.arrived)
        assertEquals("лист від них — під OPK, і так записано", PeerMessage.Sealing.ONE_TIME_KEY, worldB.messages.single { it.id == "m-3" }.sealing)
        val receiptsAfterFirst = store.boxes.size

        // Спалення «не дійшло»: сховок віддає ту саму копію ще раз.
        store.boxes["again"] = copy
        val again = pumpB.poll()
        assertEquals("вдруге не приймається", 0, again.arrived)
        assertEquals("копія горить", 1, again.burned)
        assertEquals("квитанція пішла ще раз", receiptsAfterFirst + 1, store.boxes.size)
        assertEquals(1, worldB.messages.count { it.id == "m-3" })
        // І автор цю квитанцію впізнає.
        assertEquals(1, pumpA.poll().delivered)
        assertEquals(PeerMessage.State.DELIVERED, worldA.messages.single().state)
    }

    @Test fun `OPK скінчились — лист їде лише під SPK і так і названий`() = runBlocking {
        val store = Store()
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        a.keys.absorb(b.id, b.keys.bundleFor(a.id, b.ed.privateKey)!!)
        repeat(RelayFsKeys.OPK_BATCH) { a.keys.nextForSend(b.id) }
        val worldA = World(a, b)
        val worldB = World(b, a)
        val pumpA = PeerRelayPump(worldA, PeerRelayCourier(store) { T0 }) { T0 }
        val letter = PeerSealing.seal(PeerMessage(id = "m-4", fromId = a.id, toId = b.id, body = "SPK", sentAtMs = T0), a.ed.privateKey)
        worldA.messages = PeerOutbox.enqueue(emptyList(), letter)
        assertTrue(pumpA.handOff(letter))
        assertEquals(PeerMessage.Sealing.SIGNED_PREKEY, worldA.messages.single().sealing)
        assertEquals(PeerMessage.Sealing.SIGNED_PREKEY, a.keys.lastOut(b.id)?.sealing)
        assertEquals(1, PeerRelayPump(worldB, PeerRelayCourier(store) { T0 }) { T0 }.poll().arrived)
        assertEquals(PeerMessage.Sealing.SIGNED_PREKEY, worldB.messages.single { it.id == "m-4" }.sealing)
    }
}
