package local.phantom.companion.data

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.peer.PeerChannel
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.net.peer.PeerRelay
import local.phantom.companion.core.net.peer.PeerRelayTransport
import local.phantom.companion.core.net.peer.PeerSealing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_700_000_000_000L

/**
 * Слово до вимкненого телефона.
 *
 * Обидві половини сховка були написані й перевірені, і жодна не була ввімкнена:
 * єдиний шлях, яким рухається палець людини, — `PeerMesh.flush()` — при
 * відсутній адресі робив `continue`. Тут перевіряється не сховок сам по собі
 * (це робить core-net), а та сама послідовність, яку тепер робить `flush()`:
 * вибрати шлях, віддати листа, забрати його на тому кінці, повернути квитанцію.
 *
 * Сервер тут місцевий: справжній `POST /relay/drop` на день цієї перевірки
 * віддавав 404, тож наскрізь через Fly це НЕ доведено, і тут цього не сказано.
 */
class PeerMeshRelayTest {

    /** Уміє рівно те, що вміє справжній, і памʼятає, що йому показували. */
    private class FakeRelay : PeerRelayTransport {
        val store = LinkedHashMap<String, Pair<String, ByteArray>>()
        val fetchWidths = mutableListOf<Int>()
        var reachable = true
        private var seq = 0

        override suspend fun drop(tag: String, blob: ByteArray): PeerRelayTransport.Dropped {
            if (!reachable) return PeerRelayTransport.Dropped(PeerRelayTransport.Drop.NO_CONTACT)
            if (!PeerRelay.isTag(tag)) return PeerRelayTransport.Dropped(PeerRelayTransport.Drop.REFUSED)
            if (blob.size !in PeerRelay.BLOB_SIZES) {
                return PeerRelayTransport.Dropped(PeerRelayTransport.Drop.REFUSED)
            }
            store["blob-${seq++}"] = tag to blob
            return PeerRelayTransport.Dropped(PeerRelayTransport.Drop.HELD)
        }

        override suspend fun fetch(tags: List<String>): PeerRelayTransport.Pickup? {
            if (!reachable) return null
            fetchWidths += tags.toSet().size
            if (tags.size != PeerRelay.FETCH_TAGS) return null
            val asked = tags.toSet()
            return PeerRelayTransport.Pickup(
                store.filter { it.value.first in asked }
                    .map { PeerRelayTransport.Blob(it.key, it.value.first, it.value.second) },
            )
        }

        override suspend fun burn(ids: List<String>): Boolean {
            ids.forEach { store.remove(it) }
            return true
        }
    }

    private class Phone(val id: String) {
        val signing = Ed25519.generateKeypair()
        val dh = X25519.generateKeypair()
        val dhPubB64: String =
            java.util.Base64.getEncoder().withoutPadding().encodeToString(dh.publicKey)
    }

    /** Той самий шов, що на телефоні: реєстр, ключі та журнал за лямбдами. */
    private class Node(me: Phone, them: Phone, relay: FakeRelay) {
        var log: List<PeerMessage> = emptyList()

        val leg = PeerMeshRelay(
            whoAmI = { me.id },
            decoySecretOf = { ByteArray(32) { 9 } },
            roster = { listOf(them.id) },
            pairKeyOf = { id ->
                if (id == them.id) {
                    PeerChannel.derive(me.dh.privateKey, them.dhPubB64, me.id, them.id)
                } else {
                    null
                }
            },
            signingKeyOf = { id -> if (id == them.id) them.signing.publicKey else null },
            readLog = { log },
            writeLog = { transform -> log = transform(log) },
            store = { message ->
                log = PeerOutbox.enqueue(log, message)
                true
            },
            open = { contacts -> if (contacts > 0) relay else null },
            nowMs = { NOW },
        )
    }

    private class Pair2 {
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        val relay = FakeRelay()
        val nodeA = Node(a, b, relay)
        val nodeB = Node(b, a, relay)
    }

    private fun letter(from: Phone, to: Phone, id: String, body: String): PeerMessage =
        PeerSealing.seal(
            PeerMessage(id = id, fromId = from.id, toId = to.id, body = body, sentAtMs = NOW),
            from.signing.privateKey,
        )

    @Test
    fun `a letter with nobody nearby goes to the relay and the sender reads HELD`() = runBlocking {
        val world = Pair2()
        val note = letter(world.a, world.b, "m-1", "виходь на звʼязок")
        world.nodeA.log = PeerOutbox.enqueue(emptyList(), note)

        // Рівно той вибір, що робить flush(): адреси немає, мережа є.
        assertEquals(PeerRelay.Way.RELAY, PeerRelay.first(hasLanAddress = false, online = true))
        assertTrue("сховок мусив узяти", world.nodeA.leg.handOff(note))

        val held = world.nodeA.log.single()
        assertEquals(PeerMessage.State.HELD, held.state)
        assertTrue("квитанцію нема до чого привʼязати", held.heldNonce.isNotBlank())
        assertEquals(PeerMessage.Sealing.PAIR_KEY, held.sealing)
        assertTrue(
            "лист у сховку більше не палить радіо",
            PeerOutbox.due(world.nodeA.log, NOW + PeerRelay.TTL_MS).isEmpty(),
        )
    }

    @Test
    fun `the phone that was switched off collects it, and the sender learns it landed`() =
        runBlocking {
            val world = Pair2()
            val note = letter(world.a, world.b, "m-1", "виходь на звʼязок")
            world.nodeA.log = PeerOutbox.enqueue(emptyList(), note)
            assertTrue(world.nodeA.leg.handOff(note))

            val arrived = world.nodeB.leg.collect()
            assertEquals(1, arrived.arrived)
            assertEquals("виходь на звʼязок", world.nodeB.log.single { it.id == "m-1" }.body)

            // Квитанція повертається тим самим сховком — іншого шляху немає.
            val back = world.nodeA.leg.collect()
            assertEquals(1, back.delivered)
            assertEquals(PeerMessage.State.DELIVERED, world.nodeA.log.single().state)
        }

    /**
     * Виміряна драбина смерті: 96 хвилин і FAILED назавжди, за те що другий
     * телефон вимкнений. Сховка немає — і лист однаково не гине.
     */
    @Test
    fun `with no relay in reach the letter waits instead of dying at ninety-six minutes`() =
        runBlocking {
            val world = Pair2()
            world.relay.reachable = false
            val note = letter(world.a, world.b, "m-1", "виходь на звʼязок")
            world.nodeA.log = PeerOutbox.enqueue(emptyList(), note)

            assertFalse("сховка немає на звʼязку", world.nodeA.leg.handOff(note))
            // Далі flush() робить саме це, і саме тому спроби лишаються цілі.
            world.nodeA.log = PeerOutbox.markUnreached(world.nodeA.log, "m-1", NOW)

            val waiting = world.nodeA.log.single()
            assertEquals(PeerMessage.State.QUEUED, waiting.state)
            assertEquals("відсутність не витрачає спроб", 0, waiting.attempts)
        }

    @Test
    fun `once the address is back the letter is due again and routed straight there`() {
        val world = Pair2()
        val note = letter(world.a, world.b, "m-1", "виходь на звʼязок")
        world.nodeA.log = PeerOutbox.markUnreached(
            PeerOutbox.enqueue(emptyList(), note),
            "m-1",
            NOW,
        )
        val at = world.nodeA.log.single().nextAttemptMs

        assertEquals(PeerRelay.Way.DIRECT, PeerRelay.first(hasLanAddress = true, online = true))
        assertEquals(1, PeerOutbox.due(world.nodeA.log, at).size)
    }

    /**
     * Сліпота — не обіцянка, а властивість того, що бачить сервер. Якщо цей
     * тест доведеться послабити, значить її зламали.
     */
    @Test
    fun `the relay is handed a name that names neither person and bytes it cannot read`() =
        runBlocking {
            val world = Pair2()
            val note = letter(world.a, world.b, "m-1", "виходь на звʼязок")
            world.nodeA.log = PeerOutbox.enqueue(emptyList(), note)
            assertTrue(world.nodeA.leg.handOff(note))

            val dropped = world.relay.store.keys.single()
            val (tag, blob) = world.relay.store.values.single()
            assertTrue(PeerRelay.isTag(tag))
            assertFalse("імʼя скриньки не сміє нести автора", tag.contains("phone-a"))
            assertFalse("ані адресата", tag.contains("phone-b"))
            assertTrue("довжина мусить бути однією з трьох", blob.size in PeerRelay.BLOB_SIZES)
            assertFalse(
                "слово не сміє лежати на сервері читаним",
                String(blob, Charsets.ISO_8859_1).contains("виходь"),
            )

            world.nodeB.leg.collect()
            assertEquals(
                "забір мусить питати рівно 64 різні імені",
                listOf(64),
                world.relay.fetchWidths,
            )
            assertFalse(
                "копію мусить бути спалено після видачі",
                world.relay.store.containsKey(dropped),
            )
        }
}
