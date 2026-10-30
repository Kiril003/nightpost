package local.phantom.companion.core.net.peer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.core.net.crypto.b64ToBytes
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * Живий канал сховка: одне зʼєднання замість опитування кожні 2 с.
 *
 * Підписка — ті самі [PeerRelay.FETCH_TAGS] адрес, що й забір, тож сервер не
 * дізнається нічого понад те, що вже бачив. Щойно в скриньку лягає копія, вона
 * приходить сюди ([Events.onBlobs]); при підписці сервер віддає й те, що
 * лежало раніше, тож окремого забору після зʼєднання не треба.
 *
 * Вкидання теж ходять сюди ([drop]), коли сервер це оголосив: кадр по вже
 * відкритому сокету замість HTTPS-запиту на кожен лист.
 *
 * Таймерів тут немає: коли набирати знову, каже [redialInMs], а набирає
 * власник ([ensure]) — і весь розклад перевіряється підставленим годинником.
 * Той самий такт стежить за живістю: набір, що не встиг за [PeerLinkMeter.dialTimeoutMs],
 * кидається; підписаний сокет, що мовчить, спершу питають пінгом, і лише
 * мовчання на пінг вважається смертю. Пінга OkHttp тут немає навмисно: він
 * рвав зʼєднання на кожному 25-секундному завмиранні ефіру, яке TCP пережив би.
 */
class PeerRelayLive(
    private val url: String,
    private val events: Events,
    private val jitter: (Long) -> Long = ::defaultJitter,
    val meter: PeerLinkMeter = PeerLinkMeter(),
    private val dial: (String, WebSocketListener) -> WebSocket = ::defaultDial,
) {

    interface Events {
        fun onBlobs(blobs: List<PeerRelayTransport.Blob>, storeSinceMs: Long?)

        /** Живий кадр (дзвінок, «друкує»). Сервер його не зберігав і не зберігатиме. */
        fun onLive(tag: String, data: ByteArray) {}

        fun onSubscribed() {}

        fun onClosed() {}
    }

    enum class State { IDLE, CONNECTING, OPEN, SUBSCRIBED }

    @Volatile var state: State = State.IDLE
        private set

    /** Скільки разів набирали сервер — для заміру запитів. */
    @Volatile var dials: Int = 0
        private set

    /** Сервер не має живого каналу (404 на рукостисканні). Тоді набираємо рідко. */
    @Volatile var unsupported: Boolean = false
        private set

    /** Проміжний сервер (проксі, captive) рве апгрейд 4xx: сокет тут не живе, лишається пакет. */
    @Volatile var blocked: Boolean = false
        private set

    private var socket: WebSocket? = null
    private var tags: List<String> = emptyList()
    private var storeSinceMs: Long? = null
    private var failures = 0
    private var nextDialMs = 0L
    private var lastDialMs = 0L
    private var seq = 0

    // Годинник власника ([ensure]) і монотонний — для обертань — різні навмисно:
    // перший підставляють тести розкладу, другий міряє справжній ефір.
    @Volatile private var heard = false
    @Volatile private var doubt = false
    private var lastHeardMs = 0L
    private var subbedAtMs = 0L
    private var pendingSinceMs = 0L
    private var probeAtMs = 0L
    private var lastProbeMs = 0L
    private var kaMs = SERVER_KA_MS
    @Volatile private var pingNanos = 0L
    @Volatile private var subNanos = 0L
    private val acks = HashMap<Int, CompletableDeferred<PeerRelayTransport.Dropped?>>()
    private val lock = Any()

    val subscribed: Boolean get() = state == State.SUBSCRIBED

    /**
     * Такт власника: підписатись на [wanted], а якщо зʼєднання немає й уже час —
     * набрати сервер. Нова доба (інші адреси) — нова підписка на тому ж зʼєднанні.
     */
    fun ensure(wanted: List<String>, nowMs: Long) {
        val dead = synchronized(lock) {
            val changed = wanted != tags
            tags = wanted
            if (heard) {
                heard = false
                lastHeardMs = nowMs
                probeAtMs = 0L
            }
            when (state) {
                State.SUBSCRIBED -> {
                    if (changed) {
                        subscribeLocked()
                        pendingSinceMs = nowMs
                    }
                    watchLocked(nowMs)
                }
                State.CONNECTING, State.OPEN -> {
                    if (changed && state == State.OPEN) subscribeLocked()
                    // Набір чи підписка без відповіді довше за строк — зависле
                    // рукостискання: OkHttp з readTimeout 0 чекав би його вічно.
                    if (nowMs - pendingSinceMs >= meter.dialTimeoutMs()) abandonLocked(nowMs) else false
                }
                State.IDLE -> {
                    if (wanted.size == PeerRelay.FETCH_TAGS && nowMs >= nextDialMs) {
                        state = State.CONNECTING
                        dials++
                        lastDialMs = nowMs
                        pendingSinceMs = nowMs
                        lastHeardMs = nowMs
                        subbedAtMs = 0L
                        probeAtMs = 0L
                        nextDialMs = nowMs + backoffMs(failures)
                        socket = dial(url, listener)
                    }
                    false
                }
            }
        }
        if (dead) {
            meter.fail()
            events.onClosed()
        }
    }

    /** Підписаний сокет мовчить довше за паузу сервера — пінг; мовчить і на пінг — мертвий. */
    private fun watchLocked(nowMs: Long): Boolean {
        val ws = socket ?: return false
        if (probeAtMs > 0L) {
            return if (nowMs - probeAtMs >= meter.replyTimeoutMs(PING_BYTES, PROBE_FLOOR_MS)) abandonLocked(nowMs) else false
        }
        val every = if (meter.narrow) NARROW_PROBE_EVERY_MS else PROBE_EVERY_MS
        val doubted = doubt.also { doubt = false }
        if (doubted || nowMs - lastHeardMs >= kaMs + QUIET_GRACE_MS || nowMs - lastProbeMs >= every) {
            probeAtMs = nowMs
            lastProbeMs = nowMs
            pingNanos = System.nanoTime()
            ws.send(PING)
        }
        return false
    }

    private fun abandonLocked(nowMs: Long): Boolean {
        val ws = socket ?: return false
        socket = null
        state = State.IDLE
        failAcksLocked()
        countLossLocked(nowMs)
        ws.cancel()
        return true
    }

    private fun countLossLocked(nowMs: Long) {
        // Скидати паузу набору можна лише за підписку, що прожила: інакше ефір,
        // який мигає щохвилини, набирали б щоразу з нуля.
        if (subbedAtMs > 0L && nowMs - subbedAtMs >= STABLE_MS) failures = 0
        failures++
        subbedAtMs = 0L
    }

    /** Скільки ще чекати до наступного набору. 0 — можна вже. */
    fun redialInMs(nowMs: Long): Long = synchronized(lock) { (nextDialMs - nowMs).coerceAtLeast(0L) }

    /** Мережа змінилась або людина повернулась: забути паузи й «сервер не вміє». */
    fun reset() {
        synchronized(lock) {
            unsupported = false
            blocked = false
            failures = 0
            nextDialMs = 0L
        }
    }

    /**
     * Вкидання по сокету: `0x01 | seq u32 BE | тег 43 ASCII | копія`.
     * null — сокета немає, він упав до квитанції, або квитанція спізнилась;
     * тоді власник вкидає те саме пакетом, а одержувач відсіє повтор за id.
     */
    suspend fun drop(tag: String, blob: ByteArray): PeerRelayTransport.Dropped? {
        if (!PeerRelay.isTag(tag) || !PeerRelay.isBlobSize(blob.size)) return null
        val wait = CompletableDeferred<PeerRelayTransport.Dropped?>()
        val (ws, n) = synchronized(lock) {
            val ws = socket?.takeIf { state == State.OPEN || state == State.SUBSCRIBED } ?: return null
            val n = ++seq
            acks[n] = wait
            ws to n
        }
        val frame = ByteArray(DROP_HEADER + blob.size)
        val started = System.nanoTime()
        frame[0] = DROP_OP
        frame[1] = (n ushr 24).toByte()
        frame[2] = (n ushr 16).toByte()
        frame[3] = (n ushr 8).toByte()
        frame[4] = n.toByte()
        tag.toByteArray(Charsets.US_ASCII).copyInto(frame, 5)
        blob.copyInto(frame, DROP_HEADER)
        return try {
            if (!ws.send(frame.toByteString())) {
                null
            } else {
                val got = withTimeoutOrNull(meter.replyTimeoutMs(frame.size, ACK_TIMEOUT_MS)) { wait.await() }
                when {
                    got == null -> if (synchronized(lock) { socket === ws }) {
                        meter.fail()
                        // Квитанції немає, а сокет нібито живий: спитати пінгом на
                        // наступному такті, а не чекати паузи сервера.
                        doubt = true
                    }
                    // Великий кадр міряє смугу, а не обертання.
                    blob.size <= PeerRelay.BLOB_SIZES.first() -> meter.rtt((System.nanoTime() - started) / 1_000_000L)
                    else -> meter.ok()
                }
                got
            }
        } finally {
            synchronized(lock) { acks.remove(n) }
        }
    }

    /** Живий кадр іншим підписникам цієї скриньки. false — каналу зараз немає. */
    fun sendLive(tag: String, data: ByteArray): Boolean {
        val ws = synchronized(lock) { socket.takeIf { state == State.SUBSCRIBED } } ?: return false
        val frame = buildJsonObject {
            put("op", "live")
            put("tag", tag)
            put("data", java.util.Base64.getEncoder().encodeToString(data))
        }
        return ws.send(frame.toString())
    }

    fun close() {
        synchronized(lock) {
            socket?.close(NORMAL, null)
            socket = null
            state = State.IDLE
            failAcksLocked()
        }
    }

    private fun subscribeLocked() {
        val ws = socket ?: return
        if (tags.size != PeerRelay.FETCH_TAGS) return
        state = State.OPEN
        // Вузький ефір: сервер стукає рідше. Старий сервер поле пропустить і
        // стукатиме як завжди — тоді ми лише терплячіші, ніж треба.
        kaMs = if (meter.narrow) NARROW_KA_S * 1000L else SERVER_KA_MS
        subNanos = System.nanoTime()
        ws.send(
            buildJsonObject {
                put("op", "sub")
                put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
                if (meter.narrow) put("ka", NARROW_KA_S)
            }.toString(),
        )
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (!mine(webSocket)) return
                // Набирач може відкрити зʼєднання ще до того, як повернув його нам.
                socket = webSocket
                state = State.OPEN
                subscribeLocked()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!mine(webSocket)) return
            heard = true
            handle(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, null, code)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            lost(webSocket, response?.code, null)
    }

    private fun lost(webSocket: WebSocket, httpCode: Int?, closeCode: Int?) {
        val failed = synchronized(lock) {
            if (!mine(webSocket)) return
            socket = null
            state = State.IDLE
            failAcksLocked()
            val now = maxOf(lastHeardMs, lastDialMs)
            when {
                httpCode == 404 || httpCode == 405 -> {
                    // Пауза набору рахувалась до того, як сервер сказав «немає».
                    unsupported = true
                    nextDialMs = maxOf(nextDialMs, lastDialMs + UNSUPPORTED_RETRY_MS)
                    failures++
                    false
                }
                httpCode != null && httpCode in 400..499 -> {
                    blocked = true
                    nextDialMs = maxOf(nextDialMs, lastDialMs + BLOCKED_RETRY_MS)
                    failures++
                    false
                }
                // 1013 — сервер просить перепідключитись (перезапуск, перевантаження
                // одного вузла). Це не наша невдача і не привід для паузи.
                closeCode == TRY_AGAIN_LATER -> {
                    nextDialMs = lastDialMs
                    false
                }
                else -> {
                    countLossLocked(now)
                    true
                }
            }
        }
        if (failed) meter.fail()
        events.onClosed()
    }

    private fun failAcksLocked() {
        acks.values.forEach { it.complete(null) }
        acks.clear()
    }

    /** Своє зʼєднання — або те, що саме набирається й ще не повернулось. */
    private fun mine(webSocket: WebSocket): Boolean =
        socket === webSocket || (socket == null && state == State.CONNECTING)

    internal fun handle(text: String) {
        val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (frame.str("t")) {
            "subbed" -> {
                synchronized(lock) {
                    state = State.SUBSCRIBED
                    subbedAtMs = maxOf(lastHeardMs, lastDialMs, 1L)
                    unsupported = false
                    blocked = false
                    storeSinceMs = frame["store_since_ms"]?.jsonPrimitive?.longOrNull
                }
                if (subNanos > 0L) meter.rtt((System.nanoTime() - subNanos) / 1_000_000L)
                subNanos = 0L
                events.onSubscribed()
            }
            "pong" -> {
                if (pingNanos > 0L) meter.rtt((System.nanoTime() - pingNanos) / 1_000_000L)
                pingNanos = 0L
            }
            "blobs" -> {
                val blobs = runCatching { frame["blobs"]!!.jsonArray }.getOrNull() ?: return
                val parsed = blobs.mapNotNull { element ->
                    val o = element as? JsonObject ?: return@mapNotNull null
                    val bytes = runCatching { b64ToBytes(o.str("blob") ?: "") }.getOrNull()
                        ?: return@mapNotNull null
                    PeerRelayTransport.Blob(o.str("id") ?: return@mapNotNull null, o.str("tag") ?: "", bytes)
                }
                if (parsed.isNotEmpty()) events.onBlobs(parsed, storeSinceMs)
            }
            "dropped" -> {
                val n = frame["seq"]?.jsonPrimitive?.longOrNull?.toInt() ?: return
                val wait = synchronized(lock) { acks.remove(n) } ?: return
                wait.complete(verdictOf(frame))
            }
            "live" -> {
                val tag = frame.str("tag") ?: return
                val data = runCatching { java.util.Base64.getDecoder().decode(frame.str("data")) }
                    .getOrNull() ?: return
                events.onLive(tag, data)
            }
        }
    }

    private fun verdictOf(frame: JsonObject): PeerRelayTransport.Dropped {
        val retry = frame["retry_after_s"]?.jsonPrimitive?.longOrNull?.let { it * 1000L }
        return when (frame.str("status")) {
            "held" -> PeerRelayTransport.Dropped(
                PeerRelayTransport.Drop.HELD,
                frame["held_until_ms"]?.jsonPrimitive?.longOrNull,
            )
            "full" -> PeerRelayTransport.Dropped(PeerRelayTransport.Drop.FULL, retryAfterMs = retry)
            "slow" -> PeerRelayTransport.Dropped(PeerRelayTransport.Drop.BUSY, retryAfterMs = retry)
            else -> PeerRelayTransport.Dropped(PeerRelayTransport.Drop.REFUSED)
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun backoffMs(failed: Int): Long = when {
        unsupported -> UNSUPPORTED_RETRY_MS
        blocked -> BLOCKED_RETRY_MS
        failed == 0 -> 0L
        failed <= FAST_TRIES -> jitter(minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl minOf(failed - 1, 4)))
        // Кожен набір — це TLS і підписка, кілька кілобайтів ефіру. Після
        // півхвилини провалів поспіль ефір або мертвий, або сокети ріже
        // хтось посередині; листи тим часом їдуть пакетом.
        else -> jitter(minOf(SLOW_BACKOFF_MS, MAX_BACKOFF_MS shl minOf(failed - FAST_TRIES, 4)))
    }

    companion object {
        const val BASE_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 5_000L
        const val FAST_TRIES = 8
        const val SLOW_BACKOFF_MS = 60_000L
        const val BLOCKED_RETRY_MS = 600_000L
        const val STABLE_MS = 60_000L

        /** Тиша сервера ([relay_live.KEEPALIVE_S]) і стільки ще, перш ніж питати пінгом. */
        const val SERVER_KA_MS = 25_000L
        const val NARROW_KA_S = 55
        const val QUIET_GRACE_MS = 10_000L
        const val PROBE_FLOOR_MS = 20_000L
        const val PROBE_EVERY_MS = 120_000L
        const val NARROW_PROBE_EVERY_MS = 300_000L
        private const val PING = """{"op":"ping"}"""
        private const val PING_BYTES = 64

        /** Запасний строк. Головний вхід — [reset] на зміну мережі й повернення людини. */
        const val UNSUPPORTED_RETRY_MS = 3_600_000L
        const val ACK_TIMEOUT_MS = 5_000L
        private const val NORMAL = 1000
        private const val TRY_AGAIN_LATER = 1013
        private const val DROP_OP: Byte = 0x01
        private const val DROP_HEADER = 1 + 4 + PeerRelay.TAG_CHARS
        private val json = Json { ignoreUnknownKeys = true }

        /** До пʼятої частини раніше, ніколи пізніше: телефони, що впали разом, не стукають разом. */
        fun defaultJitter(ms: Long): Long = ms - java.util.concurrent.ThreadLocalRandom.current().nextLong(ms / 5 + 1)

        /** Адреса сховка з https на wss плюс шлях живого каналу. Відкритим текстом він не ходить. */
        fun urlFor(baseUrl: String): String =
            baseUrl.trim().trimEnd('/').replaceFirst(Regex("^https://"), "wss://") + "/relay/v2/live"

        private val shared: OkHttpClient by lazy {
            // NAT тримає відкритим тиша сервера; живість — [ensure].
            OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(PeerLinkMeter.MIN_DIAL_MS, TimeUnit.MILLISECONDS)
                .dns(PeerStickyDns.shared)
                .build()
        }

        private fun defaultDial(url: String, listener: WebSocketListener): WebSocket =
            shared.newWebSocket(Request.Builder().url(url).build(), listener)
    }
}
