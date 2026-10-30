package local.phantom.companion.core.net.peer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Скринька повна» і «забагато запитів» — це «зачекай», а не провина листа.
 * Досі 409/429/507 ставали Refused → markFailed, і вісім таких відповідей
 * сплячому другові робили слово FAILED назавжди.
 */
class PeerRelayHonestWaitTest {

    private val now = 1_700_000_000_000L
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Phone(val id: String) {
        val signing = Ed25519.generateKeypair()
        val dh = X25519.generateKeypair()
        val dhPubB64: String =
            java.util.Base64.getEncoder().withoutPadding().encodeToString(dh.publicKey)
    }

    private class World(val me: Phone, val them: List<Phone>) : PeerRelayPump.World {
        var messages: List<PeerMessage> = emptyList()
        override fun selfId(): String = me.id
        override fun decoySecret(): ByteArray = PeerRelay.decoySecret(me.dh.privateKey)!!
        override fun contacts(): List<String> = them.map { it.id }
        override fun pairKey(peerId: String): ByteArray? =
            them.firstOrNull { it.id == peerId }?.let {
                PeerChannel.derive(me.dh.privateKey, it.dhPubB64, me.id, it.id)
            }
        override fun signingKey(peerId: String): ByteArray? =
            them.firstOrNull { it.id == peerId }?.signing?.publicKey
        override fun outbox(): List<PeerMessage> = messages
        override fun publish(transform: (List<PeerMessage>) -> List<PeerMessage>) {
            messages = transform(messages)
        }
        override fun persist(message: PeerMessage): Boolean = true
    }

    private fun letter(from: Phone, to: Phone): PeerMessage =
        PeerSealing.seal(
            PeerMessage(id = "m-1", fromId = from.id, toId = to.id, body = "слово", sentAtMs = now),
            from.signing.privateKey,
        )

    private fun client(engine: MockEngine) = PeerRelayClient(
        baseUrl = "https://example.invalid",
        http = HttpClient(engine) {
            install(ContentNegotiation) { json() }
            expectSuccess = false
        },
    )

    @Test
    fun `409 429 and 507 never spend an attempt of the letter`() = runBlocking {
        for (code in listOf(
            HttpStatusCode.Conflict,
            HttpStatusCode.TooManyRequests,
            HttpStatusCode.InsufficientStorage,
        )) {
            val a = Phone("phone-a")
            val b = Phone("phone-b")
            val world = World(a, listOf(b))
            world.messages = PeerOutbox.enqueue(emptyList(), letter(a, b))
            val engine = MockEngine {
                respond("{}", code, headersOf(HttpHeaders.RetryAfter, "30"))
            }
            var clock = now
            val pump = PeerRelayPump(world, PeerRelayCourier(client(engine)) { clock }) { clock }
            // Двадцять тактів поспіль — удвічі більше за драбину з восьми спроб.
            repeat(20) {
                assertFalse(pump.handOff(world.messages.single()))
                clock += 31_000L
            }
            val left = world.messages.single()
            assertEquals("$code", 0, left.attempts)
            assertEquals("$code", PeerMessage.State.QUEUED, left.state)
        }
    }

    @Test
    fun `after being told to wait the phone does not knock again before retry-after`() = runBlocking {
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        val world = World(a, listOf(b))
        world.messages = PeerOutbox.enqueue(emptyList(), letter(a, b))
        val engine = MockEngine {
            respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30"))
        }
        var clock = now
        val pump = PeerRelayPump(world, PeerRelayCourier(client(engine)) { clock }) { clock }
        pump.handOff(world.messages.single())
        val asked = engine.requestHistory.size
        clock += 10_000L
        pump.handOff(world.messages.single())
        assertEquals(asked, engine.requestHistory.size)
        clock += 25_000L
        pump.handOff(world.messages.single())
        assertTrue(engine.requestHistory.size > asked)
    }

    /** Сервер, що лише записує, які адреси питали. */
    private class Recorder : PeerRelayTransport {
        val asked = mutableListOf<List<String>>()
        override suspend fun drop(tag: String, blob: ByteArray) =
            PeerRelayTransport.Dropped(PeerRelayTransport.Drop.HELD, 0L)
        override suspend fun fetch(tags: List<String>): PeerRelayTransport.Pickup {
            asked += tags
            return PeerRelayTransport.Pickup(emptyList(), 0L)
        }
        override suspend fun burn(ids: List<String>) = true
    }

    /**
     * Справжні адреси стоять цілу добу. Якщо вигадані міняються на кожен забір,
     * сервер відрізняє справжні за повтором між двома заборами — і «64 імена
     * ховають кількість друзів» не тримається.
     */
    @Test
    fun `decoy mailboxes are the same all day, so the real ones do not stand out`() = runBlocking {
        val a = Phone("phone-a")
        val world = World(a, listOf(Phone("phone-b"), Phone("phone-c")))
        val relay = Recorder()
        var clock = now
        val pump = PeerRelayPump(world, PeerRelayCourier(relay) { clock }) { clock }
        pump.poll(0)
        clock += 3_600_000L
        pump.poll(1)
        assertEquals(PeerRelay.FETCH_TAGS, relay.asked[0].toSet().size)
        assertEquals(relay.asked[0].toSet(), relay.asked[1].toSet())
        clock += PeerRelay.EPOCH_MS
        pump.poll(2)
        assertNotEquals(relay.asked[0].toSet(), relay.asked[2].toSet())
    }

    @Test
    fun `two phones never share decoys`() = runBlocking {
        val b = Phone("phone-b")
        val relayA = Recorder()
        val relayC = Recorder()
        PeerRelayPump(World(Phone("phone-a"), listOf(b)), PeerRelayCourier(relayA) { now }) { now }.poll(0)
        PeerRelayPump(World(Phone("phone-c"), listOf(b)), PeerRelayCourier(relayC) { now }) { now }.poll(0)
        assertTrue(relayA.asked[0].toSet().intersect(relayC.asked[0].toSet()).isEmpty())
    }
}
