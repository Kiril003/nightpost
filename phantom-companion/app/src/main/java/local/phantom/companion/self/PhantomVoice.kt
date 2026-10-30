package local.phantom.companion.self

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.R
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.voice.SpeechActivityHub
import local.phantom.companion.core.voice.live.TurnCall
import local.phantom.companion.core.voice.stream.HeardLedger
import local.phantom.companion.core.voice.stream.IncrementalSpeaker
import local.phantom.companion.core.voice.stream.QuietGuard
import local.phantom.companion.core.voice.turn.TurnHub
import local.phantom.companion.core.voice.turn.VoiceFloor
import local.phantom.companion.data.AppContainer
import local.phantom.companion.ui.messenger.AgencySessionManager

/** Що розмова з PHANTOM віддає голосу по ходу відповіді. */
interface VoiceSink {
    /** id листа, яким ляже відповідь, — щоб знати, що з нього людина почула. */
    fun begin(messageId: String)

    fun token(delta: String)

    /** PHANTOM діє руками (інструмент) — сказати, що він зайнятий, а не мовчати. */
    fun acting()

    /** Прохід моделі скінчився викликами: його текст — не відповідь, наступний звучить з чистого. */
    fun nextRound()

    fun done(text: String?, messageId: String)

    fun failed(reason: String)
}

/**
 * Голосова розмова з PHANTOM з його ж розмови в месенджері: слух — той самий
 * дуплексний канал, відповідь — та сама розмова (памʼять, вчитель, розум),
 * мовлення — реченнями, поки відповідь ще пишеться.
 */
class PhantomVoice(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    val ledger = HeardLedger()

    private var listen: Job? = null
    private var barge: Job? = null

    @Volatile
    private var turn: Turn? = null

    fun start(): Boolean {
        if (_on.value) return true
        if (container.voicePrefs.micMuted.value) return false
        VoiceFloor.claim(VoiceFloor.PHANTOM_CHAT)
        runCatching { container.duplexVoice.start() }
        listen = scope.launch { container.voiceManager.sttResults.collect { heard(it) } }
        barge = scope.launch { TurnHub.bargedIn.collect { turn?.interrupt() } }
        _on.value = true
        return true
    }

    fun stop() {
        if (!_on.value) return
        listen?.cancel()
        barge?.cancel()
        turn?.interrupt()
        turn = null
        VoiceFloor.release(VoiceFloor.PHANTOM_CHAT)
        // Постійний живий діалог людина вмикала сама — його не гасимо.
        if (!container.voicePrefs.liveDialogEnabled.value) runCatching { container.duplexVoice.stop() }
        _on.value = false
    }

    /** Для історії моделі: відповідь, яку перебили, — лише дослухані речення. */
    fun forPrompt(messageId: String, body: String): String =
        ledger.forPrompt(messageId, body) { kept ->
            container.applicationContext.getString(R.string.voice_heard_until, kept)
        }

    /**
     * Покроковий дзвінок ([TurnCall]): та сама розмова з PHANTOM — обидві репліки
     * лягають у чат, — але відповідь повертається дзвінку, а не вимовляється тут.
     */
    suspend fun ask(text: String): TurnCall.Answer {
        val said = text.trim()
        if (said.isEmpty()) return TurnCall.Answer.None(container.applicationContext.getString(R.string.call_not_heard))
        val asked = postOwner(said)
        val answer = CompletableDeferred<TurnCall.Answer>()
        val ctx = container.applicationContext
        val sink = object : VoiceSink {
            override fun begin(messageId: String) = Unit
            override fun token(delta: String) = Unit
            override fun acting() = Unit
            override fun nextRound() = Unit
            override fun done(text: String?, messageId: String) {
                answer.complete(
                    if (text.isNullOrBlank()) TurnCall.Answer.Said(ctx.getString(R.string.call_answer_in_chat))
                    else TurnCall.Answer.Said(speakable(text, ctx.getString(R.string.voice_code_in_chat))),
                )
            }
            override fun failed(reason: String) {
                answer.complete(TurnCall.Answer.None(reason))
            }
        }
        container.agencyManager.dispatchPhantomDialogue(said, asked.fromId, sink, asked.id)
        return withTimeoutOrNull(WATCH_MAX_MS) { answer.await() }
            ?: TurnCall.Answer.None(ctx.getString(R.string.call_brain_silent))
    }

    private fun postOwner(said: String): PeerMessage {
        val me = container.mesh.selfIdFlow.value.ifBlank { "operator_me" }
        val asked = PeerMessage(
            id = "msg_" + UUID.randomUUID().toString().take(12),
            fromId = me,
            toId = AgencySessionManager.PHANTOM_AGENT_ID,
            body = said,
            sentAtMs = System.currentTimeMillis(),
            state = PeerMessage.State.DELIVERED,
        )
        container.mesh.ingestDirect(asked)
        return asked
    }

    private fun heard(text: String) {
        val said = text.trim()
        if (said.isEmpty()) return
        turn?.interrupt()
        val asked = postOwner(said)
        val t = Turn()
        turn = t
        container.agencyManager.dispatchPhantomDialogue(said, asked.fromId, t, asked.id)
    }

    private inner class Turn : VoiceSink {
        private val ctx = container.applicationContext
        private val guard = QuietGuard(
            fillers = ctx.resources.getStringArray(R.array.voice_fillers).toList(),
            longWait = ctx.getString(R.string.voice_long_wait),
        )
        private val speaker = IncrementalSpeaker(
            scope = scope,
            openSpan = { (container.ttsEngine as? local.phantom.companion.core.voice.route.RoutedTts)?.openStream() },
            fallbackSpeak = { container.ttsEngine.speak(it) },
            onSpanBegin = { SpeechActivityHub.begin("") },
            onSpanEnd = { SpeechActivityHub.end() },
            onEnqueue = { guard.spoke(it, System.currentTimeMillis()) },
        )
        private val watch: Job
        private val sink = SpeakingSink(
            speaker = speaker,
            actingLine = ctx.getString(R.string.voice_acting),
            codeInChat = ctx.getString(R.string.voice_code_in_chat),
        ) {
            guard.closed()
            watch.cancel()
        }

        init {
            guard.started(System.currentTimeMillis())
            speaker.open()
            // Стеля: якщо розмова так і не скаже done(), годинник не цокає вічно.
            watch = scope.launch {
                kotlinx.coroutines.withTimeoutOrNull(WATCH_MAX_MS) {
                    while (isActive) {
                        delay(TICK_MS)
                        guard.next(System.currentTimeMillis())?.let { speaker.say(it) }
                    }
                }
            }
        }

        override fun begin(messageId: String) = sink.begin(messageId)

        override fun token(delta: String) = sink.token(delta)

        override fun acting() = sink.acting()

        override fun nextRound() = sink.nextRound()

        override fun done(text: String?, messageId: String) = sink.done(text, messageId)

        override fun failed(reason: String) = sink.failed(reason)

        fun interrupt() {
            watch.cancel()
            guard.closed()
            val heard = speaker.heardSoFar()
            speaker.cancel()
            val id = sink.messageId ?: return
            val full = container.mesh.conversation(AgencySessionManager.PHANTOM_AGENT_ID)
                .lastOrNull { it.id == id }?.body ?: heard
            ledger.interrupted(id, full, heard)
        }
    }

    companion object {
        private const val TICK_MS = 250L
        private const val WATCH_MAX_MS = 90_000L
        private val CODE = Regex("[;{}]|^\\s*(?:int|var|for|if|else|static|Console)\\b")

        /** Код голосом не читається: він лежить у розмові, голос каже, де його шукати. */
        fun speakable(text: String, codeInChat: String): String {
            var dropped = false
            val kept = text.lines().filter { line ->
                val code = CODE.containsMatchIn(line)
                if (code) dropped = true
                !code
            }
            // Жирне в розмові — «**…**»; голос інакше читає зірочки.
            val out = kept.joinToString("\n").replace("**", "").replace(Regex("\\n{3,}"), "\n\n").trim()
            return if (dropped) "$out\n\n$codeInChat" else out
        }
    }
}
