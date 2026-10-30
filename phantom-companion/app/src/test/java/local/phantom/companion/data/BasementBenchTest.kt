package local.phantom.companion.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.peer.PeerChannel
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerLane
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.net.peer.PeerRelayGate
import local.phantom.companion.core.net.peer.PeerRelayTransport
import local.phantom.companion.core.net.peer.PeerSealing
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Стенд «підвал»: два телефони, справжній сховок і справжній клієнт сховка за
 * `scripts/basement/throttle.py`. Без `PHANTOM_BENCH_RELAY` пропускається —
 * запускає його `scripts/basement/bench.sh`, не ворота.
 *
 * Водій повторює такт `PeerMesh` (2 с, `collectIfDue` + `flush`, пробудження
 * черги на перепідписку), бо `PeerMesh` тягне Android; сам шлях сховка —
 * той самий `PeerMeshRelay`, що й на телефоні.
 */
class BasementBenchTest {

    private class Phone(val id: String) {
        val signing = Ed25519.generateKeypair()
        val dh = X25519.generateKeypair()
        val dhPubB64: String = java.util.Base64.getEncoder().withoutPadding().encodeToString(dh.publicKey)
    }

    private class Driver(me: Phone, them: Phone, base: String, private val scope: CoroutineScope) {
        @Volatile var log: List<PeerMessage> = emptyList()
        val transport: PeerRelayTransport = PeerRelayGate.open(PeerRelayGate.Settings(true, base), 1)!!
        val stored = ConcurrentHashMap<String, AtomicInteger>()
        val arrivedAt = ConcurrentHashMap<String, Long>()
        val handOffs = ConcurrentHashMap<String, AtomicInteger>()
        private val wakeup = Channel<Unit>(Channel.CONFLATED)
        private val inFlight = ConcurrentHashMap.newKeySet<String>()

        fun publish(transform: (List<PeerMessage>) -> List<PeerMessage>) = synchronized(this) { log = transform(log) }

        val leg = PeerMeshRelay(
            whoAmI = { me.id },
            decoySecretOf = { ByteArray(32) { 9 } },
            roster = { listOf(them.id) },
            pairKeyOf = { id -> if (id == them.id) PeerChannel.derive(me.dh.privateKey, them.dhPubB64, me.id, them.id) else null },
            signingKeyOf = { id -> if (id == them.id) them.signing.publicKey else null },
            readLog = { log },
            writeLog = ::publish,
            store = { m ->
                stored.getOrPut(m.id) { AtomicInteger() }.incrementAndGet()
                arrivedAt.putIfAbsent(m.id, System.currentTimeMillis())
                publish { PeerOutbox.enqueue(it, m) }
                true
            },
            open = { transport },
            nowMs = System::currentTimeMillis,
            scope = scope,
            onReconnect = {
                val now = System.currentTimeMillis()
                publish { PeerOutbox.wake(it, now) }
                wakeup.trySend(Unit)
            },
        )

        fun send(m: PeerMessage) {
            publish { PeerOutbox.enqueue(it, m) }
            wakeup.trySend(Unit)
        }

        fun start() = scope.launch {
            while (isActive) {
                leg.collectIfDue()
                launch { flush() }
                withTimeoutOrNull(2_000L) { wakeup.receive() }
            }
        }

        private suspend fun flush() = kotlinx.coroutines.coroutineScope {
            val due = benchOrder(PeerOutbox.due(log, System.currentTimeMillis()), leg)
            val lanes = Semaphore(6)
            for (queued in due) {
                if (!inFlight.add(queued.id)) continue
                launch {
                    try {
                        lanes.withPermit {
                            val now = log.firstOrNull { it.id == queued.id } ?: return@withPermit
                            if (PeerOutbox.due(listOf(now), System.currentTimeMillis()).isEmpty()) return@withPermit
                            handOffs.getOrPut(now.id) { AtomicInteger() }.incrementAndGet()
                            if (leg.handOff(now)) return@withPermit
                            if (log.firstOrNull { it.id == now.id }?.attempts != now.attempts) return@withPermit
                            val at = System.currentTimeMillis()
                            publish { PeerOutbox.markUnreached(it, now.id, at) }
                        }
                    } finally {
                        inFlight.remove(queued.id)
                    }
                }
            }
        }
    }

    @Test
    fun basement() = runBlocking {
        val base = System.getenv("PHANTOM_BENCH_RELAY")
        assumeTrue("стенд вимкнено", !base.isNullOrBlank())
        System.getenv("PHANTOM_BENCH_TRUST")?.let {
            System.setProperty("javax.net.ssl.trustStore", it)
            System.setProperty("javax.net.ssl.trustStorePassword", "benchpass")
        }
        val totalS = System.getenv("PHANTOM_BENCH_SECONDS")?.toLong() ?: 400L
        val textAt = (System.getenv("PHANTOM_BENCH_TEXT_AT") ?: "5,15,25,35,45,55,65,75,85,95")
            .split(",").map { it.trim().toLong() }
        val mediaAt = System.getenv("PHANTOM_BENCH_MEDIA_AT")?.toLongOrNull()
        val out = System.getenv("PHANTOM_BENCH_OUT")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        val da = Driver(a, b, base!!, scope)
        val db = Driver(b, a, base, scope)
        da.start()
        db.start()

        val t0 = System.currentTimeMillis()
        val sentAt = LinkedHashMap<String, Long>()
        val heldAt = ConcurrentHashMap<String, Long>()
        val deliveredAt = ConcurrentHashMap<String, Long>()
        val kinds = HashMap<String, String>()
        val sends = textAt.map { it to "text" } + listOfNotNull(mediaAt?.let { it to "media" })
        val watch = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                for (m in da.log) {
                    if (m.state == PeerMessage.State.HELD) heldAt.putIfAbsent(m.id, now)
                    if (m.state == PeerMessage.State.DELIVERED) deliveredAt.putIfAbsent(m.id, now)
                }
                delay(100)
            }
        }
        var n = 0
        for ((atS, kind) in sends.sortedBy { it.first }) {
            val wait = t0 + atS * 1000 - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            if (kind == "text") {
                val id = "t-${n++}"
                val m = PeerSealing.seal(
                    PeerMessage(id = id, fromId = a.id, toId = b.id, body = "Я в підвалі, все гаразд ($id)", sentAtMs = System.currentTimeMillis()),
                    a.signing.privateKey,
                )
                sentAt[id] = System.currentTimeMillis()
                kinds[id] = kind
                da.send(m)
            } else {
                val blobId = PeerFile.newBlobId()
                repeat(3) { i ->
                    val id = "c-$i"
                    val bytes = ByteArray(PeerFile.CHUNK_BYTES) { (it * 31 + i).toByte() }
                    val m = PeerSealing.seal(
                        PeerMessage(
                            id = id, fromId = a.id, toId = b.id,
                            body = PeerFile.encodeChunk(blobId, i, 3, bytes),
                            sentAtMs = System.currentTimeMillis(),
                        ),
                        a.signing.privateKey,
                    )
                    sentAt[id] = System.currentTimeMillis()
                    kinds[id] = "media"
                    da.send(m)
                }
            }
        }
        val end = t0 + totalS * 1000
        while (System.currentTimeMillis() < end) delay(500)
        watch.cancel()

        fun ms(v: Long?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull
        val rows = buildJsonArray {
            for ((id, s) in sentAt) {
                add(
                    buildJsonObject {
                        put("id", id)
                        put("kind", kinds[id])
                        put("sent_s", (s - t0) / 1000.0)
                        put("held_ms", ms(heldAt[id]?.let { it - s }))
                        put("arrived_ms", ms(db.arrivedAt[id]?.let { it - s }))
                        put("delivered_ms", ms(deliveredAt[id]?.let { it - s }))
                        put("copies_at_b", db.stored[id]?.get() ?: 0)
                        put("handoffs", da.handOffs[id]?.get() ?: 0)
                        put("final_state", da.log.firstOrNull { it.id == id }?.state?.name ?: "?")
                    },
                )
            }
        }
        val report = buildJsonObject {
            put("seconds", totalS)
            put("messages", rows)
            put("dials_a", da.leg.liveDials())
            put("dials_b", db.leg.liveDials())
            put("narrow_a", benchNarrow(da.leg))
        }
        println("BASEMENT_BENCH " + report.toString())
        out?.let { File(it).writeText(report.toString()) }
        da.leg.close()
        db.leg.close()
        scope.cancel()
    }
}

private fun benchOrder(due: List<PeerMessage>, leg: PeerMeshRelay): List<PeerMessage> =
    PeerLane.order(due, leg.narrow(), System.currentTimeMillis())

private fun benchNarrow(leg: PeerMeshRelay): JsonElement = JsonPrimitive(leg.narrow())
