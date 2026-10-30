package local.phantom.companion.data

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.peer.PeerBeacon
import local.phantom.companion.core.net.peer.PeerIntro
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerRelay
import local.phantom.companion.core.net.peer.PeerRelayLive
import local.phantom.companion.core.net.peer.PeerRelayTransport
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * Сховок у памʼяті з живим каналом — те саме, що вміє `relay_live.py`:
 * підписка на 64 адреси, вкидання сокетом і HTTPS, поштовх копій підписникам,
 * живі кадри іншим підписникам тієї ж адреси. Відповіді йдуть одним потоком,
 * як із одного сокета, і ніколи не під замком того, хто надсилав.
 */
internal class MemRelay {
    private class Sub(val listener: WebSocketListener, var tags: Set<String> = emptySet())

    private val subs = HashMap<WebSocket, Sub>()
    private val held = LinkedHashMap<String, Pair<String, ByteArray>>()
    private val wire = Executors.newSingleThreadExecutor()
    private var ids = 0

    /** Кожен живий кадр, що пройшов через сервер: (адреса, байти). */
    val liveFrames = java.util.concurrent.CopyOnWriteArrayList<Pair<String, ByteArray>>()

    fun stored(): Map<String, Pair<String, ByteArray>> = synchronized(this) { LinkedHashMap(held) }

    fun subscribedTo(tag: String): Int = synchronized(this) { subs.values.count { tag in it.tags } }

    fun sockets(): Int = synchronized(this) { subs.size }

    fun shutdown() = wire.shutdownNow()

    inner class Ws : WebSocket {
        override fun request(): Request = Request.Builder().url("https://relay.invalid").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = true.also { onText(this, text) }
        override fun send(bytes: ByteString): Boolean = true.also { onBinary(this, bytes.toByteArray()) }
        override fun close(code: Int, reason: String?): Boolean = true.also { synchronized(this@MemRelay) { subs.remove(this) } }
        override fun cancel() {
            synchronized(this@MemRelay) { subs.remove(this) }
        }
    }

    fun dial(listener: WebSocketListener): WebSocket {
        val ws = Ws()
        synchronized(this) { subs[ws] = Sub(listener) }
        wire.execute { listener.onOpen(ws, upgraded()) }
        return ws
    }

    private fun reply(ws: WebSocket, text: String) {
        val sub = synchronized(this) { subs[ws] } ?: return
        wire.execute { sub.listener.onMessage(ws, text) }
    }

    private fun onText(ws: WebSocket, text: String) {
        val o = Json.parseToJsonElement(text).jsonObject
        when (o["op"]?.jsonPrimitive?.content) {
            "sub" -> {
                val tags = o["tags"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                val waiting = synchronized(this) {
                    subs[ws]?.tags = tags
                    held.filter { it.value.first in tags }
                }
                reply(ws, """{"t":"subbed","store_since_ms":1}""")
                if (waiting.isNotEmpty()) reply(ws, blobsFrame(waiting))
            }
            "live" -> {
                val tag = o["tag"]!!.jsonPrimitive.content
                val data = o["data"]!!.jsonPrimitive.content
                if (data.length > 16 * 1024) return
                liveFrames += tag to java.util.Base64.getDecoder().decode(data)
                val others = synchronized(this) { subs.filter { it.key !== ws && tag in it.value.tags }.keys.toList() }
                val frame = buildJsonObject {
                    put("t", "live")
                    put("tag", tag)
                    put("data", data)
                }.toString()
                others.forEach { reply(it, frame) }
            }
            "ping" -> reply(ws, """{"t":"pong"}""")
        }
    }

    private fun onBinary(ws: WebSocket, frame: ByteArray) {
        val seq = java.nio.ByteBuffer.wrap(frame, 1, 4).int
        val tag = String(frame, 5, PeerRelay.TAG_CHARS, Charsets.US_ASCII)
        val blob = frame.copyOfRange(5 + PeerRelay.TAG_CHARS, frame.size)
        val id = store(tag, blob)
        val status = if (id == null) "refused" else "held"
        reply(ws, """{"t":"dropped","seq":$seq,"status":"$status","id":"${id.orEmpty()}"}""")
    }

    fun store(tag: String, blob: ByteArray): String? {
        if (!PeerRelay.isTag(tag) || blob.size !in PeerRelay.BLOB_SIZES) return null
        val (id, listeners) = synchronized(this) {
            val id = "b-${ids++}"
            held[id] = tag to blob
            id to subs.filter { tag in it.value.tags }.keys.toList()
        }
        listeners.forEach { reply(it, blobsFrame(mapOf(id to (tag to blob)))) }
        return id
    }

    fun fetch(tags: List<String>): List<PeerRelayTransport.Blob> = synchronized(this) {
        val asked = tags.toSet()
        held.filter { it.value.first in asked }.map { PeerRelayTransport.Blob(it.key, it.value.first, it.value.second) }
    }

    fun burn(ids: List<String>) = synchronized(this) { ids.forEach { held.remove(it) } }

    /**
     * Живий кадр від когось третього: свій сокет, жодної підписки. Так у двері
     * шле кожен, хто бачив код, — і так само шле підслухане вдруге.
     */
    fun sendLiveAs(tag: String, bytes: ByteArray) {
        val ws = dial(object : WebSocketListener() {})
        ws.send(
            buildJsonObject {
                put("op", "live")
                put("tag", tag)
                put("data", java.util.Base64.getEncoder().encodeToString(bytes))
            }.toString(),
        )
    }

    private fun blobsFrame(blobs: Map<String, Pair<String, ByteArray>>): String = buildJsonObject {
        put("t", "blobs")
        put(
            "blobs",
            JsonArray(
                blobs.map { (id, v) ->
                    buildJsonObject {
                        put("id", id)
                        put("tag", v.first)
                        put("blob", java.util.Base64.getEncoder().encodeToString(v.second))
                    }
                },
            ),
        )
    }.toString()

    private fun upgraded() = Response.Builder()
        .request(Request.Builder().url("https://relay.invalid").build())
        .protocol(Protocol.HTTP_1_1).code(101).message("").build()

    /** Транспорт одного телефона до цього сховка. [live] = false — сокета немає (старий сервер). */
    inner class Transport(private val live: Boolean = true) : PeerRelayTransport {
        override val liveDrops: Boolean get() = live

        override suspend fun drop(tag: String, blob: ByteArray): PeerRelayTransport.Dropped =
            if (store(tag, blob) != null) {
                PeerRelayTransport.Dropped(PeerRelayTransport.Drop.HELD, System.currentTimeMillis() + PeerRelay.TTL_MS)
            } else {
                PeerRelayTransport.Dropped(PeerRelayTransport.Drop.REFUSED)
            }

        override suspend fun fetch(tags: List<String>): PeerRelayTransport.Pickup? {
            if (tags.size != PeerRelay.FETCH_TAGS) return null
            return PeerRelayTransport.Pickup(this@MemRelay.fetch(tags), 1L)
        }

        override suspend fun burn(ids: List<String>): Boolean = true.also { this@MemRelay.burn(ids) }

        override fun openLive(events: PeerRelayLive.Events): PeerRelayLive? =
            if (live) PeerRelayLive("wss://relay.invalid/relay/v2/live", events) { _, l -> dial(l) } else null
    }
}

/** Один телефон: ключі, реєстр, журнал і справжня [PeerMesh] над спільним сховком. */
internal class MemPhone(
    val name: String,
    relay: PeerRelayTransport?,
) {
    private class MemSlot : PeerLogSlot {
        val cells = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    val signing = Ed25519.generateKeypair()
    val dh = X25519.generateKeypair()
    val pubB64: String = java.util.Base64.getEncoder().withoutPadding().encodeToString(signing.publicKey)
    val dhPubB64: String = java.util.Base64.getEncoder().withoutPadding().encodeToString(dh.publicKey)
    val id: String = PeerIntro.fingerprint(pubB64)

    /** Код, як його несе QR: без адреси в мережі — телефони в різних мережах. */
    val invite = PeerInvite.Invite(peerId = id, name = name, pubKey = pubB64, dhPubKey = dhPubB64, host = "", port = 0)

    val roster = PeerRosterStore(MemSlot())
    val arrived = java.util.concurrent.CopyOnWriteArrayList<PeerMessage>()
    val calls = java.util.concurrent.CopyOnWriteArrayList<PeerMessage>()
    val typing = java.util.concurrent.CopyOnWriteArrayList<PeerMessage>()

    val mesh = PeerMesh(
        roster = roster,
        selfId = { id },
        privateKey = { signing.privateKey },
        publicKey = { pubB64 },
        dhPrivateKey = { dh.privateKey },
        dhPublicKey = { dhPubB64 },
        myName = { name },
        beacon = PeerBeacon(object : ContextWrapper(null) {}),
        // Ті самі ворота, що й на телефоні: нуль скриньок — нуль запитів.
        relayOpen = { watched -> relay.takeIf { PeerRelay.pollingWanted(watched) } },
        online = { true },
        onArrived = { arrived += it },
        onCall = { calls += it },
        onTyping = { typing += it },
        // У памʼяті квитанція «почув» вертається за мілісекунди; тест, де її
        // немає, не мусить чекати вісім секунд телефона.
        liveAckWaitMs = 2_000L,
    )

    /** Двері цього телефона сьогодні. */
    fun door(): String = PeerRelay.doorTag(dhPubB64, id, PeerRelay.epochOf(System.currentTimeMillis()))!!

    fun row(of: MemPhone) = roster.peers.value.firstOrNull { it.id == of.id }

    suspend fun startOn(scope: CoroutineScope) {
        mesh.start(scope)
        waitFor("$name знає себе") { mesh.selfIdFlow.value.isNotEmpty() }
    }
}

internal suspend fun waitFor(what: String, timeoutMs: Long = 15_000, ok: () -> Boolean) {
    val until = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < until) {
        if (ok()) return
        delay(20)
    }
    throw AssertionError("не дочекались: $what")
}

/**
 * Один бік сканує, другий не робить нічого — і обидва однаково на звʼязку.
 *
 * Жодної спільної мережі: код без адреси, mDNS мовчить, у Б порожній реєстр.
 * Єдиний шлях між ними — сховок.
 */
class PeerMeshRemoteIntroTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MemRelay()
    private val a = MemPhone("Аня", server.Transport())
    private val b = MemPhone("Богдан", server.Transport())

    @After
    fun tearDown() {
        a.mesh.stop()
        b.mesh.stop()
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun `one-sided add over the relay fills both rosters and both directions deliver`() = runBlocking {
        a.mesh.accept(b.invite)
        assertTrue("у Б реєстр порожній", b.roster.peers.value.isEmpty())
        a.startOn(scope)
        b.startOn(scope)

        val hi = a.mesh.send(b.id, "привіт, це Аня")!!

        waitFor("Б дізнався про А зі сховка") { (b.row(a)?.introducedAtMs ?: 0L) > 0L }
        val seen = b.row(a)!!
        assertEquals(a.pubB64, seen.pubKey)
        assertEquals(a.dhPubB64, seen.dhPubKey)
        assertFalse("сховок — не «поруч»", seen.isNearby(System.currentTimeMillis()))
        assertTrue("адреси в мережі сховок не приносить", seen.transport.isNullOrBlank())

        waitFor("перше слово А в Б") { b.mesh.outbox.value.any { it.id == hi.id } }
        assertEquals("привіт, це Аня", b.mesh.outbox.value.single { it.id == hi.id }.body)
        waitFor("А бачить «доставлено»") {
            a.mesh.outbox.value.single { it.id == hi.id }.state == PeerMessage.State.DELIVERED
        }
        waitFor("відповідь-картка Б дійшла до А") { (a.row(b)?.introducedAtMs ?: 0L) > 0L }

        val back = b.mesh.send(a.id, "о, привіт")!!
        waitFor("відповідь Б в А") { a.mesh.outbox.value.any { it.id == back.id } }
        assertEquals("о, привіт", a.mesh.outbox.value.single { it.id == back.id }.body)
        waitFor("відповідь Б дійшла до слухача А") { a.arrived.any { it.id == back.id } }
    }

    @Test
    fun `a phone with no contacts answers a knock from the periodic poll alone`() = runBlocking {
        // Порожній реєстр — служби сітки немає (PeerMeshGate), тож Б не
        // запущено: двері забирає лише періодична робота, як на телефоні.
        a.mesh.accept(b.invite)
        a.startOn(scope)
        waitFor("стук лежить у дверях Б") { server.stored().values.any { it.first == b.door() } }

        b.mesh.collectRelay()

        assertTrue("Б вписав А з одного забору", b.row(a) != null)
        waitFor("відповідь Б дійшла до А") { (a.row(b)?.introducedAtMs ?: 0L) > 0L }
    }

    @Test
    fun `an answer that lands after the knocker restarted still introduces`() = runBlocking {
        a.mesh.accept(b.invite)
        a.startOn(scope)
        waitFor("стук лежить у дверях Б") { server.stored().values.any { it.first == b.door() } }
        a.mesh.stop()

        b.startOn(scope)
        waitFor("відповідь Б лежить у дверях А") { server.stored().values.any { it.first == a.door() } }
        b.mesh.stop()

        a.startOn(scope)
        waitFor("А впізнав відповідь на стук, зроблений до перезапуску") { (a.row(b)?.introducedAtMs ?: 0L) > 0L }
    }

    @Test
    fun `junk at the door is burned on the first fetch, not carried every round`() = runBlocking {
        server.store(b.door(), ByteArray(PeerRelay.BLOB_SIZES.first()) { it.toByte() })

        b.mesh.collectRelay()

        assertTrue("сміття в дверях спалено", server.stored().isEmpty())
    }

    @Test
    fun `a blocked stranger knocking at the door is not let in`() = runBlocking {
        val blocks = PeerBlockStore(
            object : PeerLogSlot {
                val cells = HashMap<String, String>()
                override fun read(key: String): String? = cells[key]
                override fun write(key: String, value: String?) {
                    if (value == null) cells.remove(key) else cells[key] = value
                }
            },
        )
        blocks.block(a.id)
        val guarded = PeerMesh(
            roster = b.roster,
            selfId = { b.id },
            privateKey = { b.signing.privateKey },
            publicKey = { b.pubB64 },
            dhPrivateKey = { b.dh.privateKey },
            dhPublicKey = { b.dhPubB64 },
            beacon = PeerBeacon(object : ContextWrapper(null) {}),
            relayOpen = { watched -> server.Transport().takeIf { PeerRelay.pollingWanted(watched) } },
            online = { true },
            blocked = { blocks.blocked.value },
        )
        try {
            a.mesh.accept(b.invite)
            a.startOn(scope)
            // Помпа охоронця спалює стук одразу після старту — тоді очікування
            // нижче вже нічого не побачило б. Спершу стук, потім охоронець.
            waitFor("стук у двері лежить у сховку") { server.stored().size >= 1 }
            guarded.start(scope)
            repeat(3) { guarded.collectRelay() }
            delay(500)
            assertTrue("заблокований не вписаний", b.roster.peers.value.none { it.id == a.id })
            assertTrue("стук заблокованого спалено, а не питається щозаходу", server.stored().isEmpty())
        } finally {
            guarded.stop()
        }
    }
}
