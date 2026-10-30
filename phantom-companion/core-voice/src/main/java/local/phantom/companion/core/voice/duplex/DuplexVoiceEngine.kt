package local.phantom.companion.core.voice.duplex

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.core.voice.AudioLevelHub
import local.phantom.companion.core.voice.EnergyVad
import local.phantom.companion.core.voice.SpeechActivityHub
import local.phantom.companion.core.voice.TtsEngine
import local.phantom.companion.core.voice.VadEvent
import local.phantom.companion.core.voice.VoiceActivityDetector
import local.phantom.companion.core.voice.VoiceController
import local.phantom.companion.core.voice.turn.TurnClock
import local.phantom.companion.core.voice.turn.TurnDecision
import local.phantom.companion.core.voice.turn.TurnHub
import local.phantom.companion.core.voice.turn.TurnTaker

/**
 * OMNIPRESENT VOICE — the full-duplex continuum.
 *
 * One always-on acoustic channel to the symbiote, owned at **application**
 * scope. It has no ViewModel, no Composable, no Lifecycle and no idea that a
 * UI exists. That is the entire point: the operator dives Presence → Dialogue
 * → World and the conversation does not notice, because nothing it depends on
 * is mounted in a stratum.
 *
 * ## Why it intercepts instead of wrapping
 *
 * PHANTOM speaks from a dozen places — the chat turn, a map voice-cue, a nav
 * deadline, a notice. The obvious design is to route them all through a new
 * `engine.speak()` and rewrite every call site. This engine instead *observes*
 * [SpeechActivityHub], through which every utterance in the process already
 * funnels (`RoutedTts.speak` publishes there, and has since the speaking half
 * of the conversation was first made visible).
 *
 * So the moment *anything, anywhere* speaks, this engine sees it, closes the
 * transcription gate, arms barge-in, and drives the phase the Orb renders —
 * with zero changes at the call sites, and with no way for a future call site
 * to forget to opt in. The chat module keeps its `speak()` and loses its
 * ownership of what that means.
 *
 * ## The duplex loop
 *
 * The mic is open continuously (that is what makes it a channel rather than a
 * walkie-talkie), and what varies is what we *do* with it:
 *
 * - **Not speaking** — chunks reach the STT engines; [EnergyVad] segments the
 *   operator's utterances; a completed utterance is finalised, which is what
 *   emits it into [VoiceController.sttResults] for the conversation to pick
 *   up. Phase runs Listening → Hearing → Processing.
 * - **Speaking** — chunks are dropped before the STT engines (they are mostly
 *   PHANTOM's own voice returning through the loudspeaker; transcribing them
 *   would feed his words back to the model as the operator's), while amplitude
 *   keeps flowing to the [BargeInGate]. Phase is Speaking.
 * - **Barge-in** — the gate believes the operator is talking over him. He
 *   yields in two stages, the way a person does: volume drops within tens of
 *   milliseconds, and only a transcribed word kills the utterance. A slammed
 *   door costs a brief dip; a spoken word costs the floor.
 *
 * Interruption is deliberately routed through [TtsEngine.stop] rather than
 * coroutine cancellation, because the utterance may have been launched by a
 * caller in a scope we know nothing about — see the "intercepts instead of
 * wraps" note above. `stop()` is the one lever that works from outside.
 *
 * ## ЖИВА РОЗМОВА — кінець репліки у два кроки
 *
 * Один поріг тиші не може бути правильним (див. [TurnTaker]). Тут VAD більше
 * не вирішує — він лише повідомляє, що акустика стихла. Далі рішення ухвалює
 * [TurnTaker] за словами: завершена фраза закривається за 450–700 мс,
 * обірвана на «але» чекає 1200, а сама лише «ееее» не закривається взагалі.
 * Між провізорним кінцем і закриттям мікрофон НЕ вимикається, тож
 * продовження фрази нікуди не падає.
 */
class DuplexVoiceEngine(
    private val voice: VoiceController,
    private val tts: TtsEngine,
    private val scope: CoroutineScope,

    /** Master mic mute (MIC-2). A muted mic closes the channel entirely. */
    private val muted: () -> Boolean = { false },

    /**
     * Акустичний шар, і тільки він: 250 мс тиші — це «людина зробила паузу»,
     * а не «людина договорила». Що це означає, вирішує [TurnTaker].
     */
    private val vad: VoiceActivityDetector = EnergyVad(silenceMinMs = PROVISIONAL_SILENCE_MS),
    private val probe: EchoCouplingProbe = EchoCouplingProbe(),
    private val gate: BargeInGate = BargeInGate(echoCoupling = { probe.coupling() }),
    private val turnTaker: TurnTaker = TurnTaker(),

    /** Injectable for tests; production reads the process-global hubs. */
    private val micLevel: StateFlow<Float> = AudioLevelHub.level,
    private val speaking: StateFlow<Boolean> = SpeechActivityHub.speaking,
    private val outputLevel: StateFlow<Float> = SpeechActivityHub.amplitude,
    private val outputKnown: StateFlow<Boolean> = SpeechActivityHub.hasEnvelope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private var jobs: List<Job> = emptyList()

    @Volatile
    private var phase: VoicePhase = VoicePhase.Dormant

    @Volatile
    private var running = false

    /** Найсвіжіший партіал розпізнавача — те, на чому будується здогад. */
    @Volatile
    private var latestPartial: String = ""

    /** Таймер закриття репліки. Живе лише між провізорним кінцем і закриттям. */
    private var endpointJob: Job? = null

    /** Вікно підтвердження перехоплення. */
    private var yieldJob: Job? = null

    /** Запобіжник проти застиглої фази «обробляю». */
    private var processingGuard: Job? = null

    @Volatile
    private var ducked = false

    /** Open the channel. Idempotent. */
    fun start() {
        if (running) return
        if (muted()) {
            Log.i(TAG, "refusing to open the channel: mic master mute is on")
            return
        }
        running = true

        // Перед startCapturing: джерело запису обирається в мить відкриття
        // сесії, і саме цей канал — єдиний, кому потрібне придушення ехо.
        voice.setDuplex(true)
        voice.setTranscriptionEnabled(true)
        voice.startCapturing()
        setPhase(VoicePhase.Listening)

        jobs = listOf(
            scope.launch { driveMic() },
            scope.launch { followSpeech() },
            scope.launch { publishToOrb() },
            scope.launch { voice.partialResults.collect { latestPartial = it } },
        )
    }

    /** Close the channel. Idempotent. */
    fun stop() {
        if (!running) return
        running = false
        endpointJob?.cancel()
        yieldJob?.cancel()
        processingGuard?.cancel()
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        // Прапорець падає ПЕРЕД зупинкою, і це не косметика: поки він стоїть,
        // stopCapturing лише закриває репліку, лишаючи мікрофон відкритим
        // (див. VoiceManager.stopCapturing). Знятий після — і вухо не
        // закрилося б ніколи.
        voice.setDuplex(false)
        scope.launch { runCatching { voice.stopCapturing() } }
        vad.reset()
        gate.reset()
        turnTaker.reset()
        TurnHub.reset()
        setPhase(VoicePhase.Dormant)
        VoicePhaseHub.reset()
    }

    /**
     * The one loop that reads the microphone. Every frame goes to exactly one
     * of two judges, decided by whether PHANTOM currently holds the floor.
     */
    private suspend fun driveMic() {
        micLevel.collect { level ->
            val now = clock()

            if (phase == VoicePhase.Speaking) {
                // Поки воротар мовчить, у мікрофоні — рівно наше власне ехо.
                // Це і є вимірювання κ, без жодного калібрувального гудка.
                if (!ducked) probe.observe(level, outputLevel.value)
                val bargedIn = gate.onFrame(
                    micLevel = level,
                    outputLevel = outputLevel.value,
                    outputKnown = outputKnown.value,
                    echoCancelled = voice.echoCancelled.value,
                    nowMs = now,
                )
                if (bargedIn) yieldFloor()
                return@collect
            }

            // Not speaking: ordinary turn-taking on the operator's voice.
            when (vad.onAmplitude(level, now)) {
                VadEvent.SpeechStart -> {
                    // Продовження фрази: таймер закриття вмирає, здогад — теж.
                    endpointJob?.cancel()
                    endpointJob = null
                    turnTaker.onSpeechResumed()
                    TurnHub.setPatient(false)
                    setPhase(VoicePhase.Hearing)
                }

                // Не «людина договорила», а «акустика стихла». Що це означає,
                // вирішує [TurnTaker] — за словами, не за секундоміром.
                VadEvent.SpeechEnd -> beginEndpointing(now)

                null -> Unit
            }
        }
    }

    /**
     * Провізорний кінець репліки. Вухо лишається відкритим; поки триває це
     * вікно, фазою є Hearing — видиме мовчазне терпіння, а не «обробляю».
     */
    private fun beginEndpointing(now: Long) {
        if (endpointJob?.isActive == true) return
        TurnClock.startTurn("live-dialog")
        TurnClock.mark(TurnClock.Stage.SPEECH_END)
        // VAD уже відчекав свою тишу — репліка мовчить довше, ніж «зараз».
        val silenceSince = now - PROVISIONAL_SILENCE_MS
        endpointJob = scope.launch {
            while (isActive && running) {
                val silenceMs = clock() - silenceSince
                // Репліка з самих вагань не закривається ніколи — і без цієї
                // стелі 50-мілісекундний цикл крутився б до кінця світу.
                // Уламок помирає тихо: відповідати на «ееее» нема чим.
                if (silenceMs > ENDPOINT_MAX_MS) {
                    turnTaker.reset()
                    latestPartial = ""
                    TurnHub.setPatient(false)
                    if (running) setPhase(VoicePhase.Listening)
                    return@launch
                }
                val decision = turnTaker.onTick(
                    silenceMs = silenceMs,
                    partial = latestPartial,
                    afterQuestion = TurnHub.askedQuestion.value,
                )
                when (decision) {
                    TurnDecision.Provisional -> {
                        TurnClock.mark(TurnClock.Stage.SPECULATED)
                        TurnHub.speculate(latestPartial)
                    }
                    TurnDecision.Patience -> TurnHub.setPatient(true)
                    TurnDecision.Commit -> {
                        commitTurn()
                        return@launch
                    }
                    TurnDecision.Wait -> Unit
                }
                delay(ENDPOINT_TICK_MS)
            }
        }
    }

    private suspend fun commitTurn() {
        TurnClock.mark(TurnClock.Stage.COMMIT)
        TurnHub.setPatient(false)
        setPhase(VoicePhase.Processing)

        // Finalising is also what *delivers* the utterance: the STT engine
        // emits the same transcript into [VoiceController.sttResults], which
        // the conversation already collects. One utterance, one path, no
        // chance of saying everything twice. The recorder is NOT touched —
        // the ear stays open through the answer.
        runCatching { voice.finalizeUtterance() }
            .onFailure { Log.w(TAG, "finalizeUtterance failed", it) }
        turnTaker.reset()
        latestPartial = ""
        if (probe.measured) Log.i(TAG, probe.snapshot())

        // Окрема робота, НЕ хвіст цієї: доки таймер закриття живий, наступна
        // репліка не може відкрити свій, і друга фраза розмови зависла б.
        processingGuard?.cancel()
        processingGuard = scope.launch {
            // Модель могла не відповісти взагалі — Orb не має застигнути в
            // «обробляю» назавжди.
            delay(PROCESSING_MAX_MS)
            if (running && phase == VoicePhase.Processing) setPhase(VoicePhase.Listening)
        }
    }

    /**
     * Поступка словом у два кроки. Крок перший — стишитись: людина чує це за
     * десятки мілісекунд, а синтез тим часом їде далі. Крок другий — почуте
     * слово вбиває репліку; тиша повертає гучність. Ціна хибного
     * спрацювання падає з цілої фрази до короткого просідання, і саме тому
     * воротар може дозволити собі бути чутливішим.
     */
    private fun yieldFloor() {
        if (ducked) return
        ducked = true
        gate.reset()
        vad.reset()
        runCatching { tts.setGain(DUCK_GAIN) }
        voice.setTranscriptionEnabled(true)
        yieldJob?.cancel()
        yieldJob = scope.launch {
            val word = withTimeoutOrNull(YIELD_CONFIRM_MS) {
                voice.partialResults.first { it.isNotBlank() }
            }
            if (word != null) {
                Log.i(TAG, "barge-in confirmed — the operator has the floor")
                TurnHub.bargeIn()
                // Kills the audio track mid-buffer. RoutedTts.stop() also
                // clears SpeechActivityHub, which trips followSpeech() and
                // reopens transcription — so we do not race it here.
                runCatching { tts.stop() }
            } else {
                // Не голос — двері, кашель, вулиця. Повертаємо гучність і
                // закриваємо воротар назад, як зробила б людина, що
                // перепитала себе й повела далі.
                runCatching { tts.setGain(1f) }
                if (speaking.value) voice.setTranscriptionEnabled(false)
            }
            ducked = false
        }
    }

    /**
     * Follows *any* utterance in the process — wherever it was launched from —
     * and swings the channel into and out of Speaking around it.
     */
    private suspend fun followSpeech() {
        // No distinctUntilChanged: a StateFlow already conflates equal values,
        // so this only ever fires on an actual transition into or out of speech.
        speaking.collect { isSpeaking ->
            if (isSpeaking) {
                // Hearing, not transcribing: from here the mic feeds only the
                // barge-in gate. See VoiceController.setTranscriptionEnabled.
                voice.setTranscriptionEnabled(false)
                gate.reset()
                setPhase(VoicePhase.Speaking)
            } else {
                yieldJob?.cancel()
                ducked = false
                runCatching { tts.setGain(1f) }
                voice.setTranscriptionEnabled(true)
                gate.reset()
                vad.reset()
                if (running) setPhase(VoicePhase.Listening)
            }
        }
    }

    /**
     * The Orb's whole feed. Amplitude is phase-relative — the operator's voice
     * while hearing him, PHANTOM's own output envelope while speaking — and
     * `known` is false exactly when we are speaking through a route that
     * cannot measure itself (System TTS), so the visualiser can be honest
     * instead of inventing a waveform.
     */
    private suspend fun publishToOrb() {
        combine(micLevel, outputLevel, outputKnown) { mic, out, known ->
            when (phase) {
                VoicePhase.Speaking -> Triple(VoicePhase.Speaking, if (known) out else 0f, known)
                VoicePhase.Hearing -> Triple(VoicePhase.Hearing, mic, true)
                VoicePhase.Listening -> Triple(VoicePhase.Listening, mic, true)
                VoicePhase.Processing -> Triple(VoicePhase.Processing, 0f, false)
                VoicePhase.Dormant -> Triple(VoicePhase.Dormant, 0f, false)
            }
        }.collectLatest { (p, amp, known) ->
            VoicePhaseHub.publish(p, amp, known)
        }
    }

    private fun setPhase(next: VoicePhase) {
        if (phase == next) return
        phase = next
        // publishToOrb() only re-emits when an amplitude moves; a phase change
        // with a still mic (Listening → Processing) must land immediately.
        VoicePhaseHub.publish(
            next,
            when (next) {
                VoicePhase.Speaking -> if (outputKnown.value) outputLevel.value else 0f
                VoicePhase.Hearing, VoicePhase.Listening -> micLevel.value
                VoicePhase.Processing, VoicePhase.Dormant -> 0f
            },
            when (next) {
                VoicePhase.Speaking -> outputKnown.value
                VoicePhase.Hearing, VoicePhase.Listening -> true
                VoicePhase.Processing, VoicePhase.Dormant -> false
            },
        )
    }

    companion object {
        private const val TAG = "DuplexVoiceEngine"

        /**
         * Акустична тиша, після якої репліка вважається ПРОВІЗОРНО
         * закінченою. Це не «людина договорила» — це «настала пауза». Далі
         * рішення за [TurnTaker].
         */
        const val PROVISIONAL_SILENCE_MS = 250L

        /** Крок таймера закриття. Живе лише всередині однієї паузи. */
        private const val ENDPOINT_TICK_MS = 50L

        /** Стеля цієї паузи: далі уламок відпускається, а цикл гасне. */
        private const val ENDPOINT_MAX_MS = 8_000L

        /** Скільки Orb може висіти в «обробляю», якщо модель не відповіла. */
        private const val PROCESSING_MAX_MS = 12_000L

        /** −12 дБ: чутно одразу, але слово не втрачене. */
        private const val DUCK_GAIN = 0.25f

        /** Скільки чекаємо на СЛОВО, перш ніж вирішити, що це були двері. */
        private const val YIELD_CONFIRM_MS = 700L
    }
}
