package local.phantom.companion.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.peer.PeerChannel
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.net.peer.PeerRelay
import local.phantom.companion.core.net.peer.PeerRelayLive
import local.phantom.companion.core.net.peer.PeerRelayTransport
import local.phantom.companion.core.net.peer.PeerSealing
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_700_000_000_000L

/** Мережа між телефоном і сховком: 40 мс в один бік, як 4G у місті. */
private const val ONE_WAY = 40L

/**
 * Від «надіслати» до «доставлено» сховком — на підставленому годиннику.
 *
 * Сценарій той, на який скаржились: живий канал відправника щойно впав (зміна
 * мережі, перезапуск сервера), і в цю ж мить людина пише. Квитанція може
 * прийти лише живим каналом, тож усе вирішує, чи він підніметься сам, чи
 * чекатиме такту насоса.
 */
class PeerMeshRelayLatencyTest {

    private class Server(private val scope: CoroutineScope) {
        private class Sub(val listener: WebSocketListener, var tags: Set<String> = emptySet())

        private val subs = HashMap<WebSocket, Sub>()
        val held = LinkedHashMap<String, Pair<String, ByteArray>>()
        private var ids = 0
        var socketDrops = 0
        var httpDrops = 0

        inner class Ws : WebSocket {
            override fun request(): Request = Request.Builder().url("https://relay.invalid").build()
            override fun queueSize(): Long = 0
            override fun send(text: String): Boolean {
                scope.launch { delay(ONE_WAY); onText(this@Ws, text) }
                return true
            }
            override fun send(bytes: ByteString): Boolean {
                val raw = bytes.toByteArray()
                scope.launch { delay(ONE_WAY); onBinary(this@Ws, raw) }
                return true
            }
            override fun close(code: Int, reason: String?): Boolean = true.also { subs.remove(this) }
            override fun cancel() { subs.remove(this) }
        }

        fun dial(@Suppress("UNUSED_PARAMETER") url: String, listener: WebSocketListener): WebSocket {
            val ws = Ws()
            subs[ws] = Sub(listener)
            // Рукостискання TCP+TLS+WS — два обертання.
            scope.launch { delay(4 * ONE_WAY); listener.onOpen(ws, response()) }
            return ws
        }

        fun cut(ws: WebSocket) {
            val sub = subs.remove(ws) ?: return
            sub.listener.onFailure(ws, java.io.IOException("мережа змінилась"), null)
        }

        private fun reply(ws: WebSocket, text: String) {
            val sub = subs[ws] ?: return
            scope.launch { delay(ONE_WAY); sub.listener.onMessage(ws, text) }
        }

        private fun onText(ws: WebSocket, text: String) {
            val o = Json.parseToJsonElement(text).jsonObject
            if (o["op"]?.jsonPrimitive?.content != "sub") return
            val sub = subs[ws] ?: return
            sub.tags = o["tags"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
            reply(ws, """{"t":"subbed","store_since_ms":1}""")
            val waiting = held.filter { it.value.first in sub.tags }
            if (waiting.isNotEmpty()) reply(ws, blobsFrame(waiting))
        }

        private fun onBinary(ws: WebSocket, frame: ByteArray) {
            val seq = java.nio.ByteBuffer.wrap(frame, 1, 4).int
            val tag = String(frame, 5, PeerRelay.TAG_CHARS, Charsets.US_ASCII)
            val blob = frame.copyOfRange(5 + PeerRelay.TAG_CHARS, frame.size)
            socketDrops++
            val id = store(tag, blob)
            reply(ws, """{"t":"dropped","seq":$seq,"status":"held","id":"$id"}""")
        }

        fun store(tag: String, blob: ByteArray): String {
            val id = "b-${ids++}"
            held[id] = tag to blob
            subs.filter { tag in it.value.tags }.keys.forEach { reply(it, blobsFrame(mapOf(id to (tag to blob)))) }
            return id
        }

        private fun blobsFrame(blobs: Map<String, Pair<String, ByteArray>>): String {
            val arr = JsonArray(
                blobs.map { (id, v) ->
                    buildJsonObject {
                        put("id", id)
                        put("tag", v.first)
                        put("blob", java.util.Base64.getEncoder().encodeToString(v.second))
                    }
                },
            )
            return buildJsonObject {
                put("t", "blobs")
                put("blobs", arr)
            }.toString()
        }

        private fun response() = Response.Builder()
            .request(Request.Builder().url("https://relay.invalid").build())
            .protocol(Protocol.HTTP_1_1).code(101).message("").build()
    }

    private class Transport(private val server: Server) : PeerRelayTransport {
        val dialed = mutableListOf<WebSocket>()

        override val liveDrops = true

        override suspend fun drop(tag: String, blob: ByteArray): PeerRelayTransport.Dropped {
            // HTTPS-запит на вже відкритому зʼєднанні пулу — одне обертання.
            delay(2 * ONE_WAY)
            server.httpDrops++
            server.store(tag, blob)
            return PeerRelayTransport.Dropped(PeerRelayTransport.Drop.HELD)
        }

        override suspend fun fetch(tags: List<String>): PeerRelayTransport.Pickup? {
            delay(2 * ONE_WAY)
            val asked = tags.toSet()
            return PeerRelayTransport.Pickup(
                server.held.filter { it.value.first in asked }
                    .map { PeerRelayTransport.Blob(it.key, it.value.first, it.value.second) },
            )
        }

        override suspend fun burn(ids: List<String>): Boolean {
            ids.forEach { server.held.remove(it) }
            return true
        }

        override fun openLive(events: PeerRelayLive.Events): PeerRelayLive =
            PeerRelayLive("wss://relay.invalid/relay/v2/live", events) { url, l -> server.dial(url, l).also { dialed += it } }
    }

    private class Phone(val id: String) {
        val signing = Ed25519.generateKeypair()
        val dh = X25519.generateKeypair()
        val dhPubB64: String = java.util.Base64.getEncoder().withoutPadding().encodeToString(dh.publicKey)
    }

    private class Node(me: Phone, them: Phone, server: Server, scope: TestScope) {
        @Volatile var log: List<PeerMessage> = emptyList()
        val transport = Transport(server)

        val leg = PeerMeshRelay(
            whoAmI = { me.id },
            decoySecretOf = { ByteArray(32) { 9 } },
            roster = { listOf(them.id) },
            pairKeyOf = { id -> if (id == them.id) PeerChannel.derive(me.dh.privateKey, them.dhPubB64, me.id, them.id) else null },
            signingKeyOf = { id -> if (id == them.id) them.signing.publicKey else null },
            readLog = { log },
            writeLog = { transform -> synchronized(this) { log = transform(log) } },
            store = { message -> synchronized(this) { log = PeerOutbox.enqueue(log, message) }; true },
            open = { transport },
            nowMs = { NOW + scope.testScheduler.currentTime },
            scope = scope.backgroundScope,
        )
    }

    @Test
    fun `a letter written the moment the channel dropped is delivered in under 300 ms`() = runTest {
        val server = Server(backgroundScope)
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        val nodeA = Node(a, b, server, this)
        val nodeB = Node(b, a, server, this)

        nodeA.leg.collectIfDue(intervalMs = Long.MAX_VALUE)
        nodeB.leg.collectIfDue(intervalMs = Long.MAX_VALUE)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(nodeA.leg.liveCoversAll() && nodeB.leg.liveCoversAll())
        val socketA = nodeA.transport.dialed.single()

        server.cut(socketA)
        val note = PeerSealing.seal(
            PeerMessage(id = "m-1", fromId = a.id, toId = b.id, body = "де ти?", sentAtMs = NOW),
            a.signing.privateKey,
        )
        nodeA.log = PeerOutbox.enqueue(emptyList(), note)
        val sentAt = currentTime
        launch { nodeA.leg.handOff(note) }

        var deliveredAt = -1L
        while (currentTime - sentAt < 5_000) {
            advanceTimeBy(5)
            runCurrent()
            if (nodeA.log.single().state == PeerMessage.State.DELIVERED) {
                deliveredAt = currentTime
                break
            }
        }
        assertTrue("не доставлено за 5 с", deliveredAt >= 0)
        val took = deliveredAt - sentAt
        println("relay send→delivered (simulated, $ONE_WAY ms one-way): $took ms")
        assertTrue("send→delivered $took мс", took < 300)
        assertEquals("де ти?", nodeB.log.single { it.id == "m-1" }.body)
        assertTrue("квитанція йшла сокетом, не HTTPS", server.socketDrops >= 1)
    }
}
