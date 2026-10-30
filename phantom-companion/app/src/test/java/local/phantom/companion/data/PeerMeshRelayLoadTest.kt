package local.phantom.companion.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY_MS = 86_400_000L
private const val TICK_MS = 2_000L

/**
 * Скільки запитів робить ТИХИЙ телефон за добу — симуляцією справжнього
 * [PeerMeshRelay] на такті сітки (2 с, `PeerMesh.PUMP_INTERVAL_MS`), а не
 * арифметикою в коментарі. Ціль із MESSENGER_V2 §3: ≤ 300 + 1 WS.
 */
class PeerMeshRelayLoadTest {

    private class Phone(val id: String) {
        val signing = Ed25519.generateKeypair()
        val dh = X25519.generateKeypair()
        val dhPubB64: String =
            java.util.Base64.getEncoder().withoutPadding().encodeToString(dh.publicKey)
    }

    /** Сервер, що лічить запити й уміє (або ні) живий канал. */
    private class CountingRelay(val speaksLive: Boolean) : PeerRelayTransport {
        var requests = 0
        val store = LinkedHashMap<String, Pair<String, ByteArray>>()
        /** Кожен підписаний сокет зі своїм слухачем: відправник тепер теж слухає квитанцію. */
        val subs = LinkedHashMap<WebSocket, Pair<WebSocketListener, List<String>>>()
        private var seq = 0

        override suspend fun drop(tag: String, blob: ByteArray): PeerRelayTransport.Dropped {
            requests++
            val id = "blob-${seq++}"
            store[id] = tag to blob
            // Поштовх — як на сервері: копія лягла, підписник її отримує.
            val b64 = java.util.Base64.getEncoder().encodeToString(blob)
            subs.filter { tag in it.value.second }.forEach { (ws, sub) ->
                sub.first.onMessage(ws, """{"t":"blobs","blobs":[{"id":"$id","tag":"$tag","blob":"$b64"}]}""")
            }
            return PeerRelayTransport.Dropped(PeerRelayTransport.Drop.HELD)
        }

        override suspend fun fetch(tags: List<String>): PeerRelayTransport.Pickup {
            requests++
            val asked = tags.toSet()
            return PeerRelayTransport.Pickup(
                store.filter { it.value.first in asked }
                    .map { PeerRelayTransport.Blob(it.key, it.value.first, it.value.second) },
                0L,
            )
        }

        override suspend fun burn(ids: List<String>): Boolean {
            requests++
            ids.forEach { store.remove(it) }
            return true
        }

        override fun openLive(events: PeerRelayLive.Events): PeerRelayLive? {
            if (!speaksLive) return null
            return PeerRelayLive("wss://x/relay/v2/live", events) { _, l ->
                val ws = Socket(this)
                subs[ws] = l to emptyList()
                l.onOpen(ws, Response.Builder()
                    .request(Request.Builder().url("https://x").build())
                    .protocol(Protocol.HTTP_1_1).code(101).message("").build())
                ws
            }
        }

        class Socket(val relay: CountingRelay) : WebSocket {
            override fun request(): Request = Request.Builder().url("https://x").build()
            override fun queueSize(): Long = 0
            override fun send(text: String): Boolean {
                // Як сервер: на пінг — понг. Інакше тихий сокет сочився б мертвим.
                if (text.contains("\"op\":\"ping\"")) relay.subs[this]?.first?.onMessage(this, """{"t":"pong"}""")
                if (text.contains("\"op\":\"sub\"")) {
                    val l = relay.subs.getValue(this).first
                    relay.subs[this] = l to Regex("[A-Za-z0-9_-]{43}").findAll(text).map { it.value }.toList()
                    l.onMessage(this, """{"t":"subbed","store_since_ms":0}""")
                }
                return true
            }
            override fun send(bytes: ByteString): Boolean = false
            override fun close(code: Int, reason: String?): Boolean = true
            override fun cancel() {}
        }
    }

    private class Node(me: Phone, them: Phone, relay: CountingRelay, clock: () -> Long) {
        var log: List<PeerMessage> = emptyList()
        val leg = PeerMeshRelay(
            whoAmI = { me.id },
            decoySecretOf = { ByteArray(32) { 9 } },
            roster = { listOf(them.id) },
            pairKeyOf = { id ->
                if (id == them.id) PeerChannel.derive(me.dh.privateKey, them.dhPubB64, me.id, them.id) else null
            },
            signingKeyOf = { id -> if (id == them.id) them.signing.publicKey else null },
            readLog = { log },
            writeLog = { transform -> log = transform(log) },
            store = { message ->
                log = PeerOutbox.enqueue(log, message)
                true
            },
            open = { contacts -> if (contacts > 0) relay else null },
            nowMs = clock,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    /** Доба тиші: такт кожні 2 с, жодного листа. */
    private fun quietDay(relay: CountingRelay, intervalMs: Long?): Pair<Int, Int> = runBlocking {
        var now = 1_700_000_000_000L
        val node = Node(Phone("phone-a"), Phone("phone-b"), relay) { now }
        val end = now + DAY_MS
        while (now < end) {
            node.leg.collectIfDue(intervalMs)
            now += TICK_MS
        }
        relay.requests to node.leg.liveDials()
    }

    @Test
    fun `a quiet phone makes at most 300 requests a day plus one live connection`() {
        val (before, _) = quietDay(CountingRelay(speaksLive = false), intervalMs = 2_000L)
        val (noLive, _) = quietDay(CountingRelay(speaksLive = false), intervalMs = null)
        val (withLive, dials) = quietDay(CountingRelay(speaksLive = true), intervalMs = null)
        println("ЗАМІР: тихий телефон, запитів на добу — до v2 $before; v2 без живого каналу $noLive; v2 з живим каналом $withLive + $dials WS")
        assertEquals(43_200, before)
        assertTrue("без каналу $noLive", noLive <= 86_400 / 30)
        assertTrue("з каналом $withLive", withLive <= 300)
        assertEquals(1, dials)
    }

    @Test
    fun `a letter reaches the subscribed phone at the moment it is dropped, without a poll`() = runBlocking {
        var now = 1_700_000_000_000L
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        val relay = CountingRelay(speaksLive = true)
        val nodeA = Node(a, b, relay) { now }
        val nodeB = Node(b, a, relay) { now }
        nodeB.leg.collectIfDue() // підписка + перший захід
        assertTrue(nodeB.leg.liveCoversAll())
        val before = relay.requests
        val note = PeerSealing.seal(
            PeerMessage(id = "m-1", fromId = a.id, toId = b.id, body = "тут", sentAtMs = now),
            a.signing.privateKey,
        )
        nodeA.log = PeerOutbox.enqueue(emptyList(), note)
        assertTrue(nodeA.leg.handOff(note))
        // Жодного такту, жодного забору: лист уже в журналі B.
        assertEquals(PeerMessage.State.DELIVERED, nodeB.log.single().state)
        // Вкидання + квитанція + спалення, і НЕ забір.
        assertEquals(before + 3, relay.requests)
        now += 1_000L
    }
}
