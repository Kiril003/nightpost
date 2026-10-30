package local.phantom.companion.self

import android.util.Log
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import local.phantom.companion.R
import local.phantom.companion.core.data.locale.ReplyLanguage
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.impl.PeopleMatch
import local.phantom.companion.core.tool.impl.ReachablePerson
import local.phantom.companion.core.voice.live.BodyVerdict
import local.phantom.companion.core.voice.live.CallEnd
import local.phantom.companion.core.voice.live.CallHands
import local.phantom.companion.core.voice.live.CallPhase
import local.phantom.companion.core.voice.live.CallTranscript
import local.phantom.companion.core.voice.live.ConfirmGate
import local.phantom.companion.core.voice.live.LiveCallSession
import local.phantom.companion.core.voice.live.LiveCallState
import local.phantom.companion.core.voice.live.LiveConnector
import local.phantom.companion.core.voice.live.LivePersona
import local.phantom.companion.core.voice.live.LiveSocket
import local.phantom.companion.core.voice.live.LiveToolBridge
import local.phantom.companion.core.voice.live.LiveTokens
import local.phantom.companion.core.voice.live.Outcome
import local.phantom.companion.core.voice.live.OwnerKeyTokenSource
import local.phantom.companion.core.voice.live.PcTokenSource
import local.phantom.companion.core.voice.live.Person
import local.phantom.companion.core.voice.live.ServerTokenSource
import local.phantom.companion.core.voice.live.TranscriptSink
import local.phantom.companion.core.voice.live.TurnCall
import local.phantom.companion.core.voice.turn.VoiceFloor
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.BodyLadder
import local.phantom.companion.ui.messenger.AgencySessionManager
import local.phantom.companion.ui.messenger.AssistantInput
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Що бачить екран дзвінка. Слова стану — рівно три й «зʼєдную». */
data class CallUi(
    val active: Boolean = false,
    val mode: Mode = Mode.LIVE,
    val word: Word = Word.CONNECTING,
    val owner: String = "",
    val phantom: String = "",
    val ask: String? = null,
    val muted: Boolean = false,
    val speaker: Boolean = true,
    val captions: Boolean = true,
    /** Чесна примітка: чому покроково, чому текстом. */
    val note: String = "",
    val textOnly: Boolean = false,
) {
    enum class Mode { LIVE, TURN }

    enum class Word { CONNECTING, LISTENING, THINKING, SPEAKING, HOLD_TO_TALK }

    companion object {
        fun word(p: CallPhase): Word = when (p) {
            CallPhase.CONNECTING, CallPhase.RECONNECTING, CallPhase.ENDED -> Word.CONNECTING
            CallPhase.LISTENING -> Word.LISTENING
            CallPhase.THINKING -> Word.THINKING
            CallPhase.SPEAKING -> Word.SPEAKING
        }

        fun word(p: TurnCall.Phase): Word = when (p) {
            TurnCall.Phase.IDLE, TurnCall.Phase.ENDED -> Word.HOLD_TO_TALK
            TurnCall.Phase.RECORDING -> Word.LISTENING
            TurnCall.Phase.THINKING -> Word.THINKING
            TurnCall.Phase.SPEAKING -> Word.SPEAKING
        }
    }
}

/**
 * «Подзвонити PHANTOM» — двигун за дверима [local.phantom.companion.ui.home.PhantomCallScreen].
 *
 * Спершу живий голос: одноразовий токен (ПК, ключ на телефоні, інакше сервер) і
 * WebSocket просто з телефона до Gemini Live. Не піднявся або обірвався
 * безповоротно — дзвінок не кладеться, а переходить у покроковий режим
 * (Moonshine → розум → Supertonic або текст) з чесною приміткою чому.
 */
class PhantomCall(private val c: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ctx get() = c.applicationContext

    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui.asStateFlow()

    private val _level = MutableStateFlow(0f)

    /** Рівень для кільця: мікрофон, поки людина говорить, голос PHANTOM, поки він. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val brainToken by lazy { BrainLiveToken.forApp(c, http) }

    private var live: LiveCallSession? = null
    private var turn: TurnCall? = null
    private var audio: LiveCallAudio? = null
    private val watchers = mutableListOf<Job>()

    fun begin() {
        if (_ui.value.active) return
        _ui.value = CallUi(active = true)
        VoiceFloor.claim(VoiceFloor.PHANTOM_CHAT)
        // Мікрофон один: дуплексний слух поступається дзвінку на весь його час.
        runCatching { c.duplexVoice.stop() }
        if (c.voicePrefs.micMuted.value) _ui.value = _ui.value.copy(muted = true)
        watchers += scope.launch { startLive() }
    }

    fun end() {
        if (!_ui.value.active) return
        live?.hangUp()
        turn?.end()
        watchers.forEach { it.cancel() }
        watchers.clear()
        audio?.close()
        audio = null
        live = null
        turn = null
        _level.value = 0f
        VoiceFloor.release(VoiceFloor.PHANTOM_CHAT)
        if (c.voicePrefs.liveDialogEnabled.value) runCatching { c.duplexVoice.start() }
        _ui.value = CallUi()
    }

    fun toggleMute() {
        val on = !_ui.value.muted
        _ui.value = _ui.value.copy(muted = on)
        live?.setMuted(on)
    }

    fun toggleSpeaker() {
        val on = !_ui.value.speaker
        _ui.value = _ui.value.copy(speaker = on)
        audio?.route(on)
    }

    fun toggleCaptions() {
        _ui.value = _ui.value.copy(captions = !_ui.value.captions)
    }

    fun confirm(yes: Boolean) {
        live?.confirm(yes)
    }

    /** Покроковий режим: утримання кнопки. */
    fun press() {
        if (!_ui.value.muted) turn?.press()
    }

    fun release() {
        turn?.release()
    }

    private suspend fun startLive() {
        val memory = runCatching { c.phantomSelf.promptMemory() }.getOrDefault("")
        val now = System::currentTimeMillis
        val tokens = LiveTokens(
            listOf(
                PcTokenSource { c.api.liveToken() },
                OwnerKeyTokenSource({ c.aiKeyStore.geminiKey.value.takeUnless { c.ownKeyDead() } }, ::postMint),
                ServerTokenSource { brainToken.fetch() },
            ),
            now,
        )
        val sound = LiveCallAudio(ctx).also { it.open(_ui.value.speaker) }
        audio = sound
        val session = LiveCallSession(
            scope = scope,
            tokens = tokens,
            connector = OkLiveConnector(http),
            speaker = sound,
            bridge = LiveToolBridge(AppCallHands(c), ConfirmGate(now)),
            persona = LivePersona.text(memory, ReplyLanguage.locale(ReplyLanguage.picked(c.applicationContext)).language),
            transcript = CallTranscript(now),
            sink = ChatTranscriptSink(c),
            now = now,
        )
        live = session
        session.setMuted(_ui.value.muted)
        if (!sound.startMic { pcm, n -> session.mic(pcm, n) }) {
            Log.w(TAG, "мікрофон дзвінка не піднявся")
        }
        session.start()
        if (!_ui.value.active) {
            // Поклали, поки складався дзвінок (end() на головному потоці).
            session.hangUp()
            sound.close()
            return
        }
        watchers += scope.launch { session.level.collect { if (it > 0f || _ui.value.word != CallUi.Word.SPEAKING) _level.value = it } }
        watchers += scope.launch { sound.level.collect { if (it > 0f) _level.value = it } }
        watchers += scope.launch { session.state.collect { liveState(it) } }
    }

    private var loggedLatency: Long? = null

    private fun liveState(s: LiveCallState) {
        if (!_ui.value.active || live == null) return
        s.lastLatencyMs?.let { ms ->
            if (ms != loggedLatency) {
                loggedLatency = ms
                // Замір для перевірки на апараті (docs/live-call-device-plan.md, §3).
                Log.i(TAG, "перший звук за $ms мс (бюджет ${LiveCallSession.FIRST_AUDIO_BUDGET_MS})")
            }
        }
        _ui.value = _ui.value.copy(
            mode = CallUi.Mode.LIVE,
            word = CallUi.word(s.phase),
            owner = s.owner,
            phantom = s.phantom,
            ask = s.pending?.ask,
        )
        if (s.phase != CallPhase.ENDED) return
        when (val e = s.end) {
            // Причина — для журналу, не для людини: «немає ключа Gemini», «не
            // спарений з компʼютером» — мова розробника, ще й завжди українська (FR-6).
            is CallEnd.Unavailable -> {
                Log.i(TAG, "живий голос недоступний: ${e.why}")
                fallBack(ctx.getString(R.string.call_live_unavailable))
            }
            is CallEnd.Lost -> {
                Log.i(TAG, "живий голос обірвався: ${e.why}")
                fallBack(ctx.getString(R.string.call_live_lost))
            }
            else -> Unit
        }
    }

    private fun fallBack(note: String) {
        audio?.close()
        audio = null
        live = null
        val t = TurnCall(
            scope = scope,
            ear = VoiceManagerEar(c),
            brain = { c.phantomVoice.ask(it) },
            voice = TtsVoice(c),
            narrow = { c.mesh.linkNarrow.value },
        )
        turn = t
        _ui.value = _ui.value.copy(mode = CallUi.Mode.TURN, word = CallUi.Word.HOLD_TO_TALK, note = note, ask = null)
        watchers += scope.launch {
            t.state.collect { s ->
                if (!_ui.value.active) return@collect
                _ui.value = _ui.value.copy(
                    word = CallUi.word(s.phase),
                    owner = s.owner,
                    phantom = s.phantom,
                    textOnly = s.textOnly,
                    note = s.note.ifBlank { note },
                )
            }
        }
        watchers += scope.launch { c.voiceManager.audioLevel.collect { _level.value = it } }
    }

    private suspend fun postMint(url: String, apiKey: String, body: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
        }.also { (code, text) -> if (code != 200) c.noteOwnKeyRefusal(code, text) }

    private companion object {
        const val TAG = "PhantomCall"
    }
}

/** OkHttp WebSocket → [LiveSocket]. Адресу (у ній токен) не логуємо ніде. */
class OkLiveConnector(private val http: OkHttpClient) : LiveConnector {
    override fun open(url: String, listener: LiveSocket.Listener): LiveSocket {
        val ws = http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onFrame(text)
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onFrame(bytes.utf8())
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(code, reason)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    listener.onFailure(response?.let { "HTTP ${it.code}" } ?: (t.javaClass.simpleName ?: "збій мережі"))
            },
        )
        return object : LiveSocket {
            override fun send(text: String): Boolean = ws.send(text)
            override fun close() {
                ws.close(1000, null)
            }
        }
    }
}

/** Руки PHANTOM у дзвінку — справжні: памʼять, мапа, месенджер, драбина тіла. */
class AppCallHands(private val c: AppContainer) : CallHands {

    override suspend fun recall(query: String): Outcome {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}ʼ']+")).filter { it.length >= 3 }
        val facts = runCatching { c.memoryFactStore.recallFor(words, null, System.currentTimeMillis()) }
            .getOrElse { return Outcome(false, "памʼять зараз не читається") }
        if (facts.isEmpty()) return Outcome(true, "Про це в памʼяті нічого немає.")
        return Outcome(true, facts.joinToString("\n") { it.text })
    }

    override suspend fun mapLookup(query: String): Outcome {
        val tool = c.toolRegistry.get("spatial.search") ?: return Outcome(false, "пошуку на мапі в цій збірці немає")
        // Ті самі ворота, сторож і слід, що й у розмові з PHANTOM у месенджері.
        val res = c.agencyManager.runGuarded(tool, buildJsonObject { put("query", JsonPrimitive(query)) })
        return when (res) {
            is ToolResult.Ok -> Outcome(true, res.output)
            is ToolResult.Err -> Outcome(false, res.message)
            is ToolResult.PermissionDenied -> Outcome(false, "немає дозволу: ${res.permission}")
            is ToolResult.Cancelled -> Outcome(false, "пошук скасовано")
        }
    }

    override suspend fun readMessages(who: String?): Outcome {
        val me = c.mesh.selfIdFlow.value
        val roster = roster()
        val names = roster.associate { it.id to it.name }
        val lines = if (who == null) {
            c.mesh.outbox.value
                .filter { it.fromId != me && it.fromId != AgencySessionManager.PHANTOM_AGENT_ID && it.fromId in names }
                .sortedBy { it.sentAtMs }
                .takeLast(READ_MAX)
        } else {
            val p = when (val found = person(who)) {
                is Person.NotFound -> return Outcome(false, found.why)
                is Person.One -> found
            }
            c.mesh.conversation(p.id).takeLast(READ_MAX)
        }
        if (lines.isEmpty()) return Outcome(true, "Листів немає.")
        val said = lines.map { m -> m to (AssistantInput.promptOf(m.body) ?: "[вкладення]") }
        return Outcome(
            true,
            said.joinToString("\n") { (m, body) ->
                val from = if (m.fromId == me) "Ти" else names[m.fromId] ?: "Невідомий"
                "$from: ${body.take(300)}"
            },
        )
    }

    override suspend fun person(who: String): Person =
        when (val m = PeopleMatch.one(roster(), who)) {
            is PeopleMatch.One -> Person.One(m.person.id, m.person.name)
            is PeopleMatch.Many -> Person.NotFound("«$who» — кілька людей: ${m.names.joinToString(", ")}. Уточни, хто саме.")
            PeopleMatch.None -> Person.NotFound("«$who» немає в реєстрі людей Nightpost")
        }

    override suspend fun sendMessage(personId: String, text: String): Outcome {
        val sent = c.mesh.send(personId, text)
            ?: return Outcome(false, "мережа Nightpost ще не готова — лист не записано")
        // Чесно: «у дорозі», а не «доставлено» — квитанція прийде пізніше.
        return Outcome(true, "лист записано й відправлено в дорогу (${sent.id.take(6)})")
    }

    override fun bodyVerdict(verb: String): BodyVerdict =
        when (val v = BodyLadder.of(c.applicationContext).judge(verb)) {
            is BodyLadder.Verdict.Do -> BodyVerdict.Do
            BodyLadder.Verdict.Ask, BodyLadder.Verdict.Suggest -> BodyVerdict.Ask
            is BodyLadder.Verdict.Refuse -> BodyVerdict.Refuse(v.why)
        }

    override fun bodyTitle(verb: String): String? = BodyLadder.RULES[verb]?.title

    override suspend fun bodyAct(verb: String, args: Map<String, String>): Outcome {
        val r = c.symbiote.actFromCall(verb, args)
        return Outcome(r.ok, r.detail)
    }

    private fun roster(): List<ReachablePerson> {
        val now = System.currentTimeMillis()
        return c.peers.peers.value.map { p ->
            ReachablePerson(id = p.id, name = p.name, live = p.isLive(now), lastSeenMs = p.lastSeenMs, verified = p.isVerified)
        }
    }

    private companion object {
        const val READ_MAX = 8
    }
}

/**
 * Стенограма лягає в розмову з PHANTOM — репліками, з часом, коли їх сказано.
 * Перебита репліка PHANTOM позначена «—»: у чаті видно, де людина його зупинила.
 */
class ChatTranscriptSink(private val c: AppContainer) : TranscriptSink {
    override fun save(lines: List<CallTranscript.Line>) {
        val me = c.mesh.selfIdFlow.value.ifBlank { "operator_me" }
        val phantom = AgencySessionManager.PHANTOM_AGENT_ID
        lines.forEach { l ->
            val mine = l.who == CallTranscript.Who.OWNER
            c.mesh.ingestDirect(
                PeerMessage(
                    id = "call_" + UUID.randomUUID().toString().take(12),
                    fromId = if (mine) me else phantom,
                    toId = if (mine) phantom else me,
                    body = if (l.cut) l.text + " —" else l.text,
                    sentAtMs = l.atMs,
                    state = PeerMessage.State.DELIVERED,
                ),
            )
        }
    }
}

/** Вухо покрокового режиму: той самий слух (Moonshine, коли модель на телефоні), натисни-і-говори. */
private class VoiceManagerEar(private val c: AppContainer) : TurnCall.Ear {
    override fun start(): Boolean = runCatching { c.voiceManager.startCapturing() }.isSuccess
    override suspend fun stop(): String? = runCatching { c.voiceManager.stopCapturing() }.getOrNull()
}

/** Голос покрокового режиму: Supertonic, коли він є; інакше — чесно текстом. */
private class TtsVoice(private val c: AppContainer) : TurnCall.Voice {
    override suspend fun ready(): Boolean {
        val routed = c.ttsEngine as? local.phantom.companion.core.voice.route.RoutedTts ?: return false
        return routed.premiumReady()
    }

    override suspend fun speak(text: String): Boolean = c.ttsEngine.speak(text).isSuccess
    override fun stop() = c.ttsEngine.stop()
}
