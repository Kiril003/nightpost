package local.phantom.companion.core.net.peer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Phone to phone, with nothing in between.
 *
 * The pairing stack made this device a client of one server. Two operators in
 * the same building have no server, and on the day this product is for they
 * have no internet either — so one of them has to listen. This is that
 * listener, plus the sender that talks to it.
 *
 * It holds no policy: what a message means, whether it is believed and what
 * happens next is [PeerSealing] and [PeerOutbox]'s business. This only moves
 * bytes, and refuses everything it does not understand.
 */
class PeerLink(
    private val port: Int = PeerWire.PORT,
    /**
     * Called for each verified-shaped inbound frame. Returns the receipt to
     * ack with, or null to refuse. The receipt is minted upstream because
     * only upstream holds the pair key — this class must not be able to
     * write one, or it could confirm a delivery it never understood.
     */
    private val onMessage: suspend (json: String) -> String? = { null },
    /**
     * Answers a peer's hello with this phone's half, or null to refuse.
     *
     * Null-by-default is not a stub: a link built without it does not speak
     * [PeerWire.VERB_HELLO] at all and says so in the old build's own words,
     * which is exactly the shape a phone in the field meets.
     */
    private val onHello: (suspend (json: String) -> String?)? = null,
    /**
     * Приймає знайомство від того, хто прочитав наш код, і відповідає своєю
     * карткою — або null, і тоді відмова слово в слово така сама, як у збірки,
     * що про [PeerWire.VERB_INTRO] ніколи не чула. Це не ввічливість: окрема
     * відмова зробила б з кожного телефона на спільному хотспоті довідник
     * «хто кого знає».
     */
    private val onIntro: (suspend (json: String) -> String?)? = null,
    /** Fired once the socket is up, with the port the OS actually gave. */
    private val onBound: (Int) -> Unit = {},
) {

    /**
     * What came back. A boolean could not tell «nobody there» from «I do not
     * know that verb» from «that meeting is over», and those three want three
     * different things from the sender: wait, drop to the old envelope, meet
     * again.
     */
    sealed interface Outcome {
        /** The reply carried the receipt this exact frame earns. */
        data object Acked : Outcome

        /** The peer answered a hello with its own. */
        data class Met(val json: String) : Outcome

        /** They said no, and said why. */
        data class Refused(val reason: String) : Outcome

        /** Nobody answered, or the conversation died mid-frame. */
        data object NoContact : Outcome
    }

    private var server: ServerSocket? = null
    private var job: Job? = null

    /**
     * Зʼєднання тримається між кадрами: вітання й лист, що за ним, і наступний
     * лист тій самій людині йдуть одним сокетом, без нового рукостискання TCP.
     * Стара збірка закриває після першої відповіді — тоді просто набираємо знову.
     */
    private class Lane {
        val mutex = Mutex()
        var socket: Socket? = null
        var input: BufferedInputStream? = null
        var idleSinceMs = 0L

        fun drop() {
            runCatching { socket?.close() }
            socket = null
            input = null
        }
    }

    private val lanes = ConcurrentHashMap<String, Lane>()
    /** Вхідні зʼєднання; true — сокет чекає наступного кадру, нічого не обробляє. */
    private val inbound = ConcurrentHashMap<Socket, Boolean>()

    /** Обробники бачать вхідне по одному, як і досі: сокетів кілька, черга одна. */
    private val handlers = Mutex()

    val isListening: Boolean get() = server?.isClosed == false

    /**
     * The port actually bound. Pass 0 to let the OS pick one: the beacon
     * advertises whatever this reports, so a fixed number buys nothing and
     * costs a collision with whatever else on the phone wanted it.
     */
    val boundPort: Int get() = server?.localPort ?: -1

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val socket = runCatching { ServerSocket(port) }.getOrNull() ?: return@launch
            server = socket
            onBound(socket.localPort)
            // Кілька зʼєднань, але не безліч: телефон не датацентр, і лінк,
            // якого мережа змушує плодити потоки, — це атака на батарею.
            val slots = Semaphore(MAX_INBOUND)
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                // Усі місця зайняті: поступається той, хто просто тримає сокет
                // про запас, — його власник набере знову, а цей стукає зараз.
                if (!slots.tryAcquire()) {
                    inbound.entries.firstOrNull { it.value }?.key?.let { runCatching { it.close() } }
                    if (withTimeoutOrNull(EVICT_WAIT_MS) { slots.acquire() } == null) {
                        runCatching { client.close() }
                        continue
                    }
                }
                inbound[client] = false
                launch {
                    try {
                        runCatching { serveAll(client) }
                    } finally {
                        runCatching { client.close() }
                        inbound.remove(client)
                        slots.release()
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        inbound.keys.forEach { runCatching { it.close() } }
        inbound.clear()
        lanes.values.forEach { it.drop() }
        lanes.clear()
        job?.cancel()
        job = null
    }

    private suspend fun serveAll(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        var first = true
        while (true) {
            client.soTimeout = if (first) READ_TIMEOUT_MS else IDLE_TIMEOUT_MS
            if (!first) inbound[client] = true
            val line = readLine(input) ?: return
            inbound[client] = false
            client.soTimeout = READ_TIMEOUT_MS
            if (!serve(line, input, out)) return
            first = false
        }
    }

    /** false — далі цим зʼєднанням не говоримо. */
    private suspend fun serve(line: String, input: InputStream, out: OutputStream): Boolean {
        val header = PeerWire.parseHeader(line)
        if (header == null) {
            // Not ours — a port scan, a browser, another app. Say nothing.
            return false
        }
        val body = readExactly(input, header.bodyBytes) ?: run {
            out.write((PeerWire.errorLine("short body") + "\n").toByteArray())
            out.flush()
            return false
        }
        val inbound = PeerWire.classify(
            header,
            body,
            speaksHello = onHello != null,
            speaksIntro = onIntro != null,
        )
        when (inbound) {
            is PeerWire.Inbound.Message -> {
                val receipt = handlers.withLock { onMessage(inbound.json) }
                val reply = if (receipt != null) {
                    PeerWire.ackLine(receipt)
                } else {
                    PeerWire.errorLine("refused")
                }
                out.write((reply + "\n").toByteArray())
                out.flush()
            }
            // The one reply that carries a body of its own. A refusal is
            // word-for-word what a build from before the handshake says, so a
            // stranger who asks learns nothing they could not have learned
            // from a phone shipped last month. Falling back costs the caller
            // only the old envelope, which still works.
            is PeerWire.Inbound.Hello ->
                answerWithBody(out, PeerWire.VERB_HELLO, handlers.withLock { onHello?.invoke(inbound.json) })
            // Знайомство приймається за тим самим правилом і тією самою рукою:
            // хто не впустив — відповідає рядком, який пише і збірка, що цього
            // слова не знає. Один шлях на обидві відмови, щоб вони не розійшлись.
            is PeerWire.Inbound.Intro ->
                answerWithBody(out, PeerWire.VERB_INTRO, handlers.withLock { onIntro?.invoke(inbound.json) })
            // A receipt is believed only on the connection that carried the
            // message, where the sender knows which frame it answers. Standing
            // alone it names nothing, so it is read and dropped: this used to
            // be the door through which anyone on the LAN marked a message
            // delivered.
            is PeerWire.Inbound.Ack -> Unit
            is PeerWire.Inbound.Malformed -> {
                out.write((PeerWire.errorLine(inbound.reason) + "\n").toByteArray())
                out.flush()
            }
        }
        return true
    }

    /**
     * Sends one message and waits for the peer's receipt. [Outcome.Acked] ONLY
     * when the reply carries [receipt] — anything else, including a cheerful
     * ACK from somebody without the pair key, leaves the message owed, which
     * is what keeps it in the outbox and honest on screen.
     *
     * [peerPort] is THEIR port, from discovery — not ours. Reusing the local
     * one worked only while both sides happened to bind the same number.
     */
    suspend fun send(
        host: String,
        peerPort: Int,
        receipt: String,
        json: String,
    ): Outcome =
        roundTrip(host, peerPort, PeerWire.VERB_MSG, json) { input ->
            val reply = readLine(input)
            when {
                PeerWire.isAck(reply, receipt) -> Outcome.Acked
                reply == null -> Outcome.NoContact
                // An ACK with the wrong tag is a refusal, not silence:
                // it must still cost the message an attempt.
                else -> Outcome.Refused(PeerWire.errorReason(reply) ?: "not acked")
            }
        }

    /**
     * Offers this phone's half of a meeting and waits for the other half.
     *
     * [Outcome.Refused] with [PeerWire.UNKNOWN_VERB] is the answer a build
     * from before the handshake gives on its own — that is the whole detection,
     * and the caller drops to the old envelope on it rather than leaving a
     * person unable to write to somebody who has not updated.
     */
    suspend fun hello(host: String, peerPort: Int, json: String): Outcome =
        exchange(host, peerPort, PeerWire.VERB_HELLO, json)

    /** One frame out, one body-carrying frame back. Shared by HLO and INT. */
    private suspend fun exchange(
        host: String,
        peerPort: Int,
        verb: String,
        json: String,
    ): Outcome =
        roundTrip(host, peerPort, verb, json) { input ->
            val line = readLine(input)
            val header = PeerWire.parseHeader(line)
            when {
                header?.verb == verb ->
                    readExactly(input, header.bodyBytes)
                        ?.let { Outcome.Met(it) }
                        ?: Outcome.NoContact
                line == null -> Outcome.NoContact
                else -> Outcome.Refused(PeerWire.errorReason(line) ?: "no $verb")
            }
        }

    /**
     * Один кадр і одна відповідь — спершу тим сокетом, що лишився з минулого
     * разу, і лише коли той мертвий, новим. Повтор того самого кадру на новому
     * сокеті безпечний: лист приймач відсіє за id, вітання просто складеться знову.
     */
    private suspend fun roundTrip(
        host: String,
        peerPort: Int,
        verb: String,
        json: String,
        read: (BufferedInputStream) -> Outcome,
    ): Outcome = withContext(Dispatchers.IO) {
        val lane = lanes.computeIfAbsent("$host:$peerPort") { Lane() }
        lane.mutex.withLock {
            val bytes = json.toByteArray(Charsets.UTF_8)
            val now = System.currentTimeMillis()
            val kept = lane.socket?.takeIf { !it.isClosed && now - lane.idleSinceMs < REUSE_IDLE_MS }
            val keptInput = lane.input
            if (kept != null && keptInput != null) {
                val out = runCatching { exchangeOn(kept, keptInput, verb, bytes, read) }.getOrNull()
                if (out != null && out != Outcome.NoContact) {
                    lane.idleSinceMs = System.currentTimeMillis()
                    return@withLock out
                }
            }
            lane.drop()
            val socket = Socket()
            runCatching {
                socket.connect(InetSocketAddress(host, peerPort), CONNECT_TIMEOUT_MS)
                val input = BufferedInputStream(socket.getInputStream())
                exchangeOn(socket, input, verb, bytes, read).also { out ->
                    if (out != Outcome.NoContact) {
                        lane.socket = socket
                        lane.input = input
                        lane.idleSinceMs = System.currentTimeMillis()
                    }
                }
            }.getOrDefault(Outcome.NoContact).also { if (lane.socket !== socket) runCatching { socket.close() } }
        }
    }

    private fun exchangeOn(
        socket: Socket,
        input: BufferedInputStream,
        verb: String,
        bytes: ByteArray,
        read: (BufferedInputStream) -> Outcome,
    ): Outcome {
        socket.soTimeout = READ_TIMEOUT_MS
        val out = socket.getOutputStream()
        out.write((PeerWire.header(verb, bytes.size) + "\n").toByteArray())
        out.write(bytes)
        out.flush()
        return read(input)
    }

    private fun answerWithBody(out: java.io.OutputStream, verb: String, answer: String?) {
        if (answer == null) {
            out.write((PeerWire.errorLine(PeerWire.unknownVerb(verb)) + "\n").toByteArray())
        } else {
            val bytes = answer.toByteArray(Charsets.UTF_8)
            out.write((PeerWire.header(verb, bytes.size) + "\n").toByteArray())
            out.write(bytes)
        }
        out.flush()
    }

    /**
     * Hands our own card to the phone whose code we just read, and waits for
     * theirs. Same shape as [hello]: the answer carries a body.
     *
     * [Outcome.Refused] never says why in a way this side can act on — an old
     * build, a phone that removed us and a stale code all answer identically,
     * which is deliberate. The caller may only ever guess out loud.
     */
    suspend fun intro(host: String, peerPort: Int, json: String): Outcome =
        exchange(host, peerPort, PeerWire.VERB_INTRO, json)

    /** Reads one line without pulling the body after it. */
    private fun readLine(input: InputStream): String? {
        val buf = StringBuilder()
        while (buf.length < MAX_LINE) {
            val c = input.read()
            if (c < 0) return buf.takeIf { it.isNotEmpty() }?.toString()
            if (c == '\n'.code) return buf.toString()
            if (c != '\r'.code) buf.append(c.toChar())
        }
        return null
    }

    private fun readExactly(input: InputStream, length: Int): String? {
        if (length == 0) return ""
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) return null
            read += n
        }
        return String(bytes, Charsets.UTF_8)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 1_500
        const val READ_TIMEOUT_MS = 8_000
        const val IDLE_TIMEOUT_MS = 20_000
        const val REUSE_IDLE_MS = 15_000L
        const val MAX_INBOUND = 6
        const val EVICT_WAIT_MS = 1_000L
        const val MAX_LINE = 256
    }
}
