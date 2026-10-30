package local.phantom.companion.core.voice.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Один WebSocket до Gemini Live. Реалізація — OkHttp у `:app`, у тестах — підробка. */
interface LiveSocket {
    fun send(text: String): Boolean

    fun close()

    interface Listener {
        fun onOpen()
        fun onFrame(text: String)
        fun onClosed(code: Int, reason: String)
        fun onFailure(why: String)
    }
}

fun interface LiveConnector {
    fun open(url: String, listener: LiveSocket.Listener): LiveSocket
}

/** Динамік дзвінка: 24 кГц PCM16. [flush] — негайна тиша (перебили). */
interface CallSpeaker {
    fun play(pcm24k: ByteArray)
    fun flush()

    /** Перший ступінь перехоплення: людина заговорила — PHANTOM стишується одразу. */
    fun duck(on: Boolean)
}

enum class CallPhase { CONNECTING, LISTENING, THINKING, SPEAKING, RECONNECTING, ENDED }

sealed interface CallEnd {
    data object HungUp : CallEnd

    /** Живий голос так і не піднявся — дзвінок іде покроковим режимом. */
    data class Unavailable(val why: String) : CallEnd

    /** Був і обірвався, відновити не вдалось. */
    data class Lost(val why: String) : CallEnd
}

data class LiveCallState(
    val phase: CallPhase = CallPhase.CONNECTING,
    val owner: String = "",
    val phantom: String = "",
    val pending: PendingAction? = null,
    val muted: Boolean = false,
    val lastLatencyMs: Long? = null,
    val end: CallEnd? = null,
    val source: LiveToken.Source? = null,
)

/**
 * Живий дзвінок з PHANTOM: телефон ↔ Gemini Live напряму.
 *
 * 16 кГц PCM угору ([mic]), 24 кГц униз ([CallSpeaker]). Кінець репліки
 * вирішує сервер; перебивання — сервер шле `interrupted`, і відтворення
 * гаситься негайно, а ще раніше локальний край мови стишує PHANTOM.
 * goAway і обрив — новий одноразовий токен і `sessionResumption`.
 *
 * Усі події (сокет, край мови, дотики) йдуть через одну чергу й обробляються
 * по одній — стан не має гонок і перевіряється підробленим сокетом.
 */
class LiveCallSession(
    private val scope: CoroutineScope,
    private val tokens: LiveTokens,
    private val connector: LiveConnector,
    private val speaker: CallSpeaker,
    private val bridge: LiveToolBridge,
    private val persona: String,
    private val transcript: CallTranscript,
    private val sink: TranscriptSink,
    private val now: () -> Long,
    private val maxReconnects: Int = 3,
    private val backoffMs: Long = 400,
) {
    private val _state = MutableStateFlow(LiveCallState())
    val state: StateFlow<LiveCallState> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)

    /** Рівень мікрофона 0..1 — для кільця навколо аватара. */
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Заміри «кінець мови → перший звук» за дзвінок. */
    val latencies: List<Long> get() = synchronized(measured) { measured.toList() }
    private val measured = mutableListOf<Long>()

    private sealed interface In {
        class Opened(val gen: Int) : In
        class Frame(val gen: Int, val text: String) : In
        class Dropped(val gen: Int, val why: String) : In
        class Speech(val started: Boolean, val atMs: Long) : In
        class Tap(val yes: Boolean) : In
        class Replied(val gen: Int, val reply: ToolReply) : In
        data object Hangup : In
    }

    private val inbox = Channel<In>(Channel.UNLIMITED)
    private var loop: Job? = null

    @Volatile private var socket: LiveSocket? = null
    @Volatile private var gen = 0
    @Volatile private var ready = false
    @Volatile private var muted = false
    private var model = DEFAULT_MODEL
    private var resumeHandle: String? = null
    private var everUp = false
    private var reconnects = 0
    private var speechEndAt: Long? = null
    private var ended = false
    private val toolJobs = HashMap<String, Job>()
    private val edge = SpeechEdge()

    fun start() {
        if (loop != null) return
        loop = scope.launch {
            connect()
            for (e in inbox) {
                handle(e)
                if (ended) break
            }
        }
    }

    /** Кадр мікрофона (16 кГц PCM16 LE) — з аудіопотоку, не з черги: затримка тут дорожча за все. */
    fun mic(pcm: ByteArray, length: Int = pcm.size) {
        if (muted || ended) {
            _level.value = 0f
            return
        }
        edge.feed(pcm, length, now())?.let { inbox.trySend(In.Speech(it.started, it.atMs)) }
        _level.value = edge.level
        if (ready) socket?.send(LiveProtocol.audio(pcm, 0, length))
    }

    fun setMuted(on: Boolean) {
        muted = on
        _state.value = _state.value.copy(muted = on)
    }

    fun confirm(yes: Boolean) {
        inbox.trySend(In.Tap(yes))
    }

    fun hangUp() {
        inbox.trySend(In.Hangup)
    }

    private suspend fun connect() {
        when (val m = tokens.take()) {
            is Mint.Refused -> finish(if (everUp) CallEnd.Lost(m.why) else CallEnd.Unavailable(m.why))
            is Mint.Got -> {
                model = m.token.model
                val g = ++gen
                _state.value = _state.value.copy(source = m.token.source)
                socket = connector.open(LiveProtocol.url(m.token.token), listener(g))
            }
        }
    }

    private fun listener(g: Int) = object : LiveSocket.Listener {
        override fun onOpen() {
            inbox.trySend(In.Opened(g))
        }

        override fun onFrame(text: String) {
            inbox.trySend(In.Frame(g, text))
        }

        override fun onClosed(code: Int, reason: String) {
            inbox.trySend(In.Dropped(g, "сокет закрито ($code)"))
        }

        override fun onFailure(why: String) {
            inbox.trySend(In.Dropped(g, why))
        }
    }

    private suspend fun handle(e: In) {
        when (e) {
            is In.Opened -> if (e.gen == gen) {
                socket?.send(LiveProtocol.setup(model, persona, bridge.declarations, resumeHandle))
            }
            is In.Frame -> if (e.gen == gen) LiveProtocol.parse(e.text).forEach { event(it) }
            is In.Dropped -> if (e.gen == gen) dropped(e.why)
            is In.Speech -> speech(e.started, e.atMs)
            is In.Tap -> tap(e.yes)
            is In.Replied -> if (e.gen == gen) {
                toolJobs.remove(e.reply.id)
                socket?.send(LiveProtocol.toolResponse(listOf(e.reply)))
                publish()
            }
            In.Hangup -> finish(CallEnd.HungUp)
        }
    }

    private suspend fun event(ev: LiveEvent) {
        when (ev) {
            LiveEvent.SetupComplete -> {
                ready = true
                everUp = true
                reconnects = 0
                phase(CallPhase.LISTENING)
            }
            is LiveEvent.HeardUser -> {
                transcript.owner(ev.text)
                bridge.gate.heard(ev.text)
                publish()
            }
            is LiveEvent.Audio -> {
                if (_state.value.phase != CallPhase.SPEAKING) {
                    speaker.duck(false)
                    speechEndAt?.let { end ->
                        val ms = now() - end
                        synchronized(measured) { measured += ms }
                        _state.value = _state.value.copy(lastLatencyMs = ms)
                    }
                    speechEndAt = null
                    phase(CallPhase.SPEAKING)
                }
                speaker.play(ev.pcm)
            }
            is LiveEvent.SaidModel -> {
                transcript.phantom(ev.text)
                publish()
            }
            LiveEvent.Interrupted -> {
                speaker.flush()
                speaker.duck(false)
                transcript.interrupted()
                phase(CallPhase.LISTENING)
            }
            LiveEvent.TurnComplete -> {
                transcript.closeTurn()
                bridge.gate.turnEnded()
                speaker.duck(false)
                phase(CallPhase.LISTENING)
            }
            is LiveEvent.ToolCalls -> {
                phase(CallPhase.THINKING)
                val g = gen
                ev.calls.forEach { call ->
                    toolJobs[call.id] = scope.launch { inbox.trySend(In.Replied(g, bridge.handle(call))) }
                }
            }
            is LiveEvent.ToolCancelled -> ev.ids.forEach { toolJobs.remove(it)?.cancel() }
            is LiveEvent.GoAway -> reconnect(proactive = true, why = "сервер попросив перепідключитись")
            is LiveEvent.Resumable -> resumeHandle = ev.handle
        }
    }

    private fun speech(started: Boolean, atMs: Long) {
        if (ended) return
        if (started) {
            speechEndAt = null
            // Сервер ще не сказав «перебили», а людина вже говорить — стишитись зараз.
            if (_state.value.phase == CallPhase.SPEAKING) speaker.duck(true)
        } else {
            speechEndAt = atMs
            if (_state.value.phase == CallPhase.LISTENING) phase(CallPhase.THINKING)
        }
    }

    private fun tap(yes: Boolean) {
        if (_state.value.pending == null) return
        bridge.gate.tapped(yes)
        if (ready) socket?.send(LiveProtocol.clientText(if (yes) "Так, підтверджую." else "Ні, не треба."))
        publish()
    }

    private suspend fun dropped(why: String) {
        if (ended) return
        if (!everUp) finish(CallEnd.Unavailable(why)) else reconnect(proactive = false, why = why)
    }

    private suspend fun reconnect(proactive: Boolean, why: String) {
        ready = false
        val old = socket
        gen++
        socket = null
        old?.close()
        speaker.flush()
        transcript.closeTurn()
        toolJobs.values.forEach { it.cancel() }
        toolJobs.clear()
        reconnects++
        if (reconnects > maxReconnects) {
            finish(CallEnd.Lost(why))
            return
        }
        phase(CallPhase.RECONNECTING)
        if (!proactive) delay(backoffMs * reconnects)
        if (!ended) connect()
    }

    private fun finish(reason: CallEnd) {
        if (ended) return
        ended = true
        ready = false
        gen++
        socket?.close()
        socket = null
        speaker.flush()
        toolJobs.values.forEach { it.cancel() }
        toolJobs.clear()
        bridge.gate.clear()
        transcript.closeTurn()
        val lines = transcript.lines()
        if (lines.isNotEmpty()) runCatching { sink.save(lines) }
        _level.value = 0f
        _state.value = _state.value.copy(phase = CallPhase.ENDED, end = reason, pending = null)
        inbox.close()
    }

    private fun phase(p: CallPhase) {
        _state.value = _state.value.copy(phase = p)
        publish()
    }

    private fun publish() {
        _state.value = _state.value.copy(
            owner = transcript.last(CallTranscript.Who.OWNER),
            phantom = transcript.last(CallTranscript.Who.PHANTOM),
            pending = bridge.gate.pending.value,
        )
    }

    companion object {
        /** Бюджет: перший звук відповіді не пізніше ніж за 800 мс від кінця мови. */
        const val FIRST_AUDIO_BUDGET_MS = 800L
    }
}

/**
 * Край мови за енергією кадру: початок — одразу, кінець — після [hangMs] тиші,
 * а часом кінця вважається ОСТАННІЙ гучний кадр (від нього рахується бюджет).
 */
class SpeechEdge(private val threshold: Double = 700.0, private val hangMs: Long = 300) {
    data class Edge(val started: Boolean, val atMs: Long)

    private var speaking = false
    private var lastLoudAt = 0L

    @Volatile
    var level: Float = 0f
        private set

    fun feed(pcm: ByteArray, length: Int, atMs: Long): Edge? {
        val rms = rms(pcm, length)
        level = (rms / 6000.0).coerceIn(0.0, 1.0).toFloat()
        if (rms >= threshold) {
            lastLoudAt = atMs
            if (!speaking) {
                speaking = true
                return Edge(true, atMs)
            }
        } else if (speaking && atMs - lastLoudAt >= hangMs) {
            speaking = false
            return Edge(false, lastLoudAt)
        }
        return null
    }

    companion object {
        fun rms(pcm: ByteArray, length: Int): Double {
            val n = length / 2
            if (n == 0) return 0.0
            var sum = 0.0
            var i = 0
            while (i + 1 < length) {
                val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
                sum += s * s
                i += 2
            }
            return kotlin.math.sqrt(sum / n)
        }
    }
}
