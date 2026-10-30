package local.phantom.companion.core.net.peer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

private const val NOW = 1_700_000_000_000L
private const val ORACLE = "https://api.phantom-os.dev"
private const val FLY = "https://phantom-license.fly.dev"

/**
 * Переїзд Fly → Oracle (vc30): старші збірки сидять лише на Fly, нові ходять в
 * обидва. Справжні клієнт, курʼєр і насос; сервери — у памʼяті, по хосту.
 */
class PeerRelayFanoutTest {

    private class Server {
        val store = ConcurrentHashMap<String, Pair<String, String>>()
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val burned = java.util.Collections.synchronizedList(mutableListOf<String>())
        @Volatile var refuses = false
        @Volatile var hangs = false
        @Volatile var legacyFlag: Boolean? = null
        private var seq = 0

        @Synchronized fun put(tag: String, blob: String): String = "b${seq++}".also { store[it] = tag to blob }
    }

    private val oracle = Server()
    private val fly = Server()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val http = HttpClient(
        MockEngine { request ->
            val server = when (request.url.host) {
                "api.phantom-os.dev" -> oracle
                "phantom-license.fly.dev" -> fly
                else -> error("чужий хост ${request.url.host}")
            }
            if (server.refuses) throw java.io.IOException("connection refused")
            if (server.hangs) delay(60_000)
            server.paths += request.url.encodedPath
            val body = (request.body as? TextContent)?.text.orEmpty()
            val tree = if (body.isBlank()) null else Json.parseToJsonElement(body).jsonObject
            when (request.url.encodedPath) {
                "/relay/v2/health" -> server.legacyFlag?.let { flag ->
                    respond("""{"ok":true,"blob_sizes":[],"live":0,"legacy_relay":$flag}""", HttpStatusCode.OK, json)
                } ?: respond("", HttpStatusCode.NotFound)
                "/relay/drop" -> {
                    server.put(tree!!["tag"]!!.jsonPrimitive.content, tree["blob"]!!.jsonPrimitive.content)
                    respond("""{"held_until_ms":${NOW + PeerRelay.TTL_MS}}""", HttpStatusCode.OK, json)
                }
                "/relay/fetch" -> {
                    val asked = tree!!["tags"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                    val blobs = buildJsonArray {
                        server.store.filter { it.value.first in asked }.forEach { (id, v) ->
                            add(buildJsonObject { put("id", id); put("tag", v.first); put("blob", v.second) })
                        }
                    }
                    respond(buildJsonObject { put("blobs", blobs) }.toString(), HttpStatusCode.OK, json)
                }
                "/relay/burn" -> {
                    tree!!["ids"]!!.jsonArray.map { it.jsonPrimitive.content }.forEach {
                        server.store.remove(it)
                        server.burned += it
                    }
                    respond("{}", HttpStatusCode.OK, json)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        },
    ) {
        install(ContentNegotiation) { json() }
        expectSuccess = false
    }

    private class Phone(val id: String) {
        val signing = Ed25519.generateKeypair()
        val dh = X25519.generateKeypair()
        val dhPubB64: String = java.util.Base64.getEncoder().withoutPadding().encodeToString(dh.publicKey)
    }

    private class World(val me: Phone, val them: Phone) : PeerRelayPump.World {
        var messages: List<PeerMessage> = emptyList()
        var writes = 0
        var arrivals = 0

        override fun selfId(): String = me.id
        override fun decoySecret(): ByteArray = PeerRelay.decoySecret(me.dh.privateKey)!!
        override fun contacts(): List<String> = listOf(them.id)
        override fun pairKey(peerId: String): ByteArray? =
            if (peerId == them.id) PeerChannel.derive(me.dh.privateKey, them.dhPubB64, me.id, them.id) else null
        override fun signingKey(peerId: String): ByteArray? = if (peerId == them.id) them.signing.publicKey else null
        override fun outbox(): List<PeerMessage> = messages
        override fun publish(transform: (List<PeerMessage>) -> List<PeerMessage>) {
            messages = transform(messages)
        }
        override fun persist(message: PeerMessage): Boolean {
            writes++
            if (messages.none { it.id == message.id }) arrivals++
            messages = PeerOutbox.enqueue(messages, message)
            return true
        }
    }

    private fun vc29(): PeerRelayTransport =
        PeerRelayGate.open(PeerRelayGate.Settings(enabled = true, baseUrl = FLY), contacts = 1, http = http)!!

    private fun vc30(): PeerRelayTransport = PeerRelayGate.open(
        PeerRelayGate.Settings(enabled = true, baseUrl = ORACLE, legacyUrl = FLY),
        contacts = 1,
        http = http,
    )!!

    private fun pump(world: World, relay: PeerRelayTransport) =
        PeerRelayPump(world, PeerRelayCourier(relay) { NOW }) { NOW }

    private fun letter(from: Phone, to: Phone, id: String): PeerMessage =
        PeerSealing.seal(PeerMessage(id = id, fromId = from.id, toId = to.id, body = "о сьомій", sentAtMs = NOW), from.signing.privateKey)

    private suspend fun until(what: () -> Boolean) = withTimeout(5_000) { while (!what()) delay(10) }

    private suspend fun pollUntil(pump: PeerRelayPump, what: () -> Boolean): Int = withTimeout(5_000) {
        var arrived = 0
        while (true) {
            arrived += pump.poll().arrived
            if (what()) break
            delay(20)
        }
        arrived
    }

    private suspend fun send(world: World, relay: PeerRelayTransport, id: String) {
        world.messages = PeerOutbox.enqueue(emptyList(), letter(world.me, world.them, id))
        assertTrue(pump(world, relay).handOff(world.messages.first()))
    }

    @Test
    fun `a letter from a Fly-only phone reaches a vc30 phone and its receipt goes back`() = runBlocking {
        val a = Phone("old"); val b = Phone("new")
        val old = World(a, b); val new = World(b, a)
        val oldRelay = vc29()
        send(old, oldRelay, "m-1")
        assertTrue(oracle.store.isEmpty())

        val arrived = pollUntil(pump(new, vc30())) { new.arrivals > 0 }

        assertEquals(1, arrived)
        assertEquals(1, new.arrivals)
        until { fly.burned.isNotEmpty() && fly.store.isNotEmpty() }
        val back = pump(old, oldRelay).poll()
        assertEquals(1, back.delivered)
        assertEquals(PeerMessage.State.DELIVERED, old.messages.single().state)
    }

    @Test
    fun `a vc30 letter is deposited to Fly too, so a Fly-only phone reads it`() = runBlocking {
        val a = Phone("new"); val b = Phone("old")
        val new = World(a, b); val old = World(b, a)
        send(new, vc30(), "m-2")
        assertEquals(1, oracle.store.size)
        until { fly.store.size == 1 }

        val got = pump(old, vc29()).poll()

        assertEquals(1, got.arrived)
        assertEquals("m-2", old.messages.single().id)
    }

    @Test
    fun `the same letter from both relays lands once and both copies burn`() = runBlocking {
        val a = Phone("new-a"); val b = Phone("new-b")
        val sender = World(a, b); val reader = World(b, a)
        send(sender, vc30(), "m-3")
        until { fly.store.size == 1 }
        val flyCopy = fly.store.keys.single()
        val oracleCopy = oracle.store.keys.single()

        val got = pump(reader, vc30()).poll()

        assertEquals(1, got.arrived)
        assertEquals(1, reader.writes)
        assertEquals(1, reader.arrivals)
        assertTrue(oracleCopy in oracle.burned)
        until { flyCopy in fly.burned }
    }

    @Test
    fun `two differently sealed copies of one letter are still written once`() = runBlocking {
        val a = Phone("old"); val b = Phone("new")
        val old = World(a, b); val new = World(b, a)
        val oldRelay = vc29()
        send(old, oldRelay, "m-4")
        assertTrue(pump(old, oldRelay).handOff(old.messages.first()))
        assertEquals(2, fly.store.size)

        val reader = pump(new, vc30())
        pollUntil(reader) { new.arrivals > 0 }
        reader.poll()

        assertEquals(1, new.arrivals)
        assertEquals(1, new.messages.count { it.id == "m-4" })
    }

    @Test
    fun `Fly hanging costs neither a send nor a poll anything`() = runBlocking {
        fly.hangs = true
        val a = Phone("new-a"); val b = Phone("new-b")
        val sender = World(a, b); val reader = World(b, a)
        val senderRelay = vc30()

        val sendMs = timed { send(sender, senderRelay, "m-5") }
        assertTrue("send waited $sendMs ms", sendMs < 1_000)
        assertEquals(PeerMessage.State.HELD, sender.messages.single().state)

        val readerRelay = vc30()
        val reader1 = pump(reader, readerRelay)
        var got = PeerRelayPump.Report()
        val firstMs = timed { got = reader1.poll() }
        assertEquals(1, got.arrived)
        assertTrue("first poll waited $firstMs ms", firstMs < 1_000)

        val secondMs = timed { reader1.poll() }
        assertTrue("second poll waited $secondMs ms", secondMs < 1_000)
    }

    @Test
    fun `Fly refusing connections is invisible to sender and reader`() = runBlocking {
        fly.refuses = true
        val a = Phone("new-a"); val b = Phone("new-b")
        val sender = World(a, b); val reader = World(b, a)
        val ms = timed {
            send(sender, vc30(), "m-6")
            assertEquals(1, pump(reader, vc30()).poll().arrived)
        }
        assertTrue("took $ms ms", ms < 1_000)
        assertEquals(PeerMessage.State.HELD, sender.messages.single().state)
    }

    @Test
    fun `the primary server can switch Fly off without an update`() = runBlocking {
        oracle.legacyFlag = false
        val a = Phone("new-a"); val b = Phone("new-b")
        val sender = World(a, b); val reader = World(b, a)
        val relay = vc30()
        relay.probe()
        assertEquals(null, relay.pollEveryMs)
        send(sender, relay, "m-7")
        val readerRelay = vc30().also { it.probe() }
        assertEquals(1, pump(reader, readerRelay).poll().arrived)
        delay(200)
        assertTrue(fly.paths.isEmpty())
    }

    @Test
    fun `while Fly is on, a live-covered phone still polls every two minutes`() = runBlocking {
        oracle.legacyFlag = true
        val relay = vc30().also { it.probe() }
        assertEquals(PeerRelayFanout.LEGACY_POLL_MS, relay.pollEveryMs)
        assertEquals(null, vc29().pollEveryMs)
    }

    @Test
    fun `a copy pushed live from the primary is not handed over again from Fly`() = runBlocking {
        val tag = "A".repeat(PeerRelay.TAG_CHARS)
        val bytes = ByteArray(PeerRelay.BLOB_SIZES.first()) { 7 }
        val primary = Fake(listOf(PeerRelayTransport.Blob("p1", tag, bytes)))
        val legacy = Fake(listOf(PeerRelayTransport.Blob("f1", tag, bytes)))
        val fanout = PeerRelayFanout(primary, legacy, scope = CoroutineScope(Dispatchers.Unconfined))
        val pushed = mutableListOf<String>()
        fanout.openLive(object : PeerRelayLive.Events {
            override fun onBlobs(blobs: List<PeerRelayTransport.Blob>, storeSinceMs: Long?) {
                blobs.forEach { pushed += it.id }
            }
        })
        primary.events!!.onBlobs(primary.blobs, null)

        val fetched = fanout.fetch(List(PeerRelay.FETCH_TAGS) { tag })!!.blobs.map { it.id }

        assertEquals(listOf("p1"), pushed)
        assertEquals(listOf("p1"), fetched)
        assertEquals(listOf("f1"), legacy.burned)
    }

    @Test
    fun `burning a Fly copy goes to Fly with its own id`() = runBlocking {
        val primary = Fake(emptyList())
        val legacy = Fake(emptyList())
        val fanout = PeerRelayFanout(primary, legacy, scope = CoroutineScope(Dispatchers.Unconfined))
        fanout.burn(listOf("o1", PeerRelayFanout.LEGACY_ID + "f9"))
        assertEquals(listOf("o1"), primary.burned)
        assertEquals(listOf("f9"), legacy.burned)
    }

    @Test
    fun `a letter sent while Fly is paused waits in a queue that outlives a restart`() = runBlocking {
        var clock = NOW
        val queue = java.io.File.createTempFile("legacy", ".q").also { it.delete(); it.deleteOnExit() }
        fun relay() = PeerRelayFanout(
            PeerRelayClient(baseUrl = ORACLE, http = http),
            PeerRelayClient(baseUrl = FLY, http = http),
            nowMs = { clock },
            queueFile = queue,
        )
        fly.refuses = true
        val new = World(Phone("new"), Phone("old"))
        val first = relay()
        send(new, first, "m-8")
        until { first.pollEveryMs == null }
        send(new, first, "m-9")

        fly.refuses = false
        clock += PeerRelayFanout.QUIET_MAX_MS
        relay().fetch(listOf("A".repeat(PeerRelay.TAG_CHARS)))

        until { fly.store.size == 2 }
    }

    @Test
    fun `a Fly burn that failed is tried again on the next poll`() = runBlocking {
        val primary = Fake(emptyList())
        val legacy = Fake(emptyList()).also { it.burnOk = false }
        val fanout = PeerRelayFanout(primary, legacy, scope = CoroutineScope(Dispatchers.Unconfined))
        fanout.burn(listOf(PeerRelayFanout.LEGACY_ID + "f9"))
        legacy.burnOk = true

        fanout.fetch(listOf("A".repeat(PeerRelay.TAG_CHARS)))

        assertEquals(listOf("f9", "f9"), legacy.burned)
    }

    private class Fake(val blobs: List<PeerRelayTransport.Blob>) : PeerRelayTransport {
        val burned = mutableListOf<String>()
        var burnOk = true
        var events: PeerRelayLive.Events? = null
        override suspend fun drop(tag: String, blob: ByteArray) =
            PeerRelayTransport.Dropped(PeerRelayTransport.Drop.HELD)
        override suspend fun fetch(tags: List<String>) = PeerRelayTransport.Pickup(blobs)
        override suspend fun burn(ids: List<String>): Boolean {
            burned += ids
            return burnOk
        }
        override fun openLive(events: PeerRelayLive.Events): PeerRelayLive? {
            this.events = events
            return null
        }
    }

    private suspend fun timed(block: suspend () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }
}
