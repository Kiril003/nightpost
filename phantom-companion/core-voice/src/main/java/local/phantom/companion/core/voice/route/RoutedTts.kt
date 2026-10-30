package local.phantom.companion.core.voice.route

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.voice.SpeechActivityHub
import local.phantom.companion.core.voice.TtsEngine
import local.phantom.companion.core.voice.stream.SpeechSpan

/**
 * QH3 — operator-facing label for the TTS route currently in use.
 *
 * - [Premium]: Supertonic 3 ONNX is loaded and speaking (streaming).
 * - [System]:  premium not loaded; Android System TTS handles speech.
 * - [None]:    neither engine is ready (e.g. system TTS init failed).
 *
 * Settings → Voice reads [RoutedTts.currentRoute] to render the
 * «голос: Supertonic 3» / «голос: System TTS» indicator so the
 * operator can tell whether his Supertonic download was actually
 * picked up by the running engine.
 */
enum class TtsRoute { Baked, Premium, System, None }

/**
 * Composite TTS router. Tries [premium] first (Supertonic 3 ONNX when
 * downloaded); falls back to [systemFallback] (Android System TTS) if the
 * premium engine is not ready or its [speak] call fails.
 *
 * Operator code targets only [TtsEngine] — the routing is fully transparent.
 *
 * Ліцензійного замка тут більше немає: VoicePremium перенесено у вільний
 * ярус після вироку власника про системного робота («її взагалі не має
 * бути»), і жодна підписана політика не може звузити вільний набір — тож
 * перевірка ключа стала б мертвою гілкою, а не воротами.
 */
class RoutedTts(
    private val premium: TtsEngine,
    private val systemFallback: TtsEngine,
    /** Pre-rendered nav phrases: PHANTOM's voice at ~zero latency, when the cue is one of them. */
    private val baked: BakedNavVoice? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : TtsEngine {

    override val displayName: String
        get() = "auto (premium → fallback)"

    private val _currentRoute = MutableStateFlow(TtsRoute.None)
    val currentRoute: StateFlow<TtsRoute> = _currentRoute.asStateFlow()

    private val _fallbacks = MutableSharedFlow<VoiceFallback>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every hand-off to the system voice (or to silence), as it happens. */
    val fallbacks: SharedFlow<VoiceFallback> = _fallbacks.asSharedFlow()

    private val _lastFallback = MutableStateFlow<VoiceFallback?>(null)

    /**
     * Відступ, який ще описує теперішнє: непорожньо рівно тоді, коли остання
     * фраза, що прозвучала, пішла НЕ живим голосом. Живий голос, заговоривши,
     * стирає слід ([spoke]) — інакше «відступав годину тому» і «відступає
     * зараз» дають на склі однакову плашку.
     */
    val lastFallback: StateFlow<VoiceFallback?> = _lastFallback.asStateFlow()

    private class LiveMiss(val reason: VoiceFallback.Reason, val detail: String?)

    @Volatile
    private var absentLogged = false

    override suspend fun isReady(): Boolean =
        baked?.isReady() == true || premium.isReady() || systemFallback.isReady()

    /**
     * Чи говорить зараз саме живий голос (Supertonic). Покроковий дзвінок PHANTOM
     * без нього відповідає текстом, а не системним роботом.
     */
    suspend fun premiumReady(): Boolean = premium.isReady()

    /**
     * Every utterance in the process funnels through here, so this is the
     * one honest place to tell the UI that PHANTOM is talking — see
     * [SpeechActivityHub]. The `finally` matters: a failed or cancelled
     * speak must still clear the flag, or the overlay would stick open
     * forever on the first TTS error.
     */
    override suspend fun speak(text: String, locale: String): Result<Unit> {
        SpeechActivityHub.begin(text)
        try {
            baked?.let { pack ->
                val hit = pack.speak(text, locale)
                if (hit.isSuccess) {
                    spoke(TtsRoute.Baked)
                    return hit
                }
            }
            val miss = if (premium.isReady()) {
                val result = premium.speak(text, locale)
                if (result.isSuccess) {
                    spoke(TtsRoute.Premium)
                    return result
                }
                LiveMiss(VoiceFallback.Reason.LIVE_FAILED, result.exceptionOrNull()?.message)
            } else {
                LiveMiss(VoiceFallback.Reason.LIVE_NOT_LOADED, liveLoadDetail())
            }
            return handOff(text, miss, systemFallback.speak(text, locale))
        } finally {
            SpeechActivityHub.end()
        }
    }

    /**
     * ON-TIME VOICE — the maneuver-cue path: pick the engine that can start
     * SPEAKING soonest, not the one that sounds best.
     *
     * Supertonic gets the cue only when its own measured time-to-first-audio
     * says it has earned it ([URGENT_FIRST_AUDIO_MAX_MS]); unmeasured (cold —
     * checking would mean loading ORT sessions while the turn approaches) or
     * measured-slow, the cue goes straight to System TTS, which speaks in
     * ~100–300 ms. The premium voice remains the LAST resort rather than
     * being skipped outright — a slow cue still beats a silent turn.
     */
    suspend fun speakUrgent(text: String, locale: String = "uk-UA"): Result<Unit> {
        SpeechActivityHub.begin(text)
        try {
            // The stage is cleared FIRST. System TTS queues with QUEUE_ADD
            // and the premium synth serializes on its own thread — either
            // way, a turn cue submitted mid-chat-monologue would politely
            // wait out the paragraph and then describe a turn already made.
            // A NOW cue owns the speaker; the interrupted sentence loses.
            premium.stop()
            systemFallback.stop()
            baked?.stop()
            // A baked clip is PHANTOM's voice AND the fastest start there is,
            // so it wins the urgent path outright when the cue is in the pack.
            baked?.let { pack ->
                val hit = pack.speak(text, locale)
                if (hit.isSuccess) {
                    spoke(TtsRoute.Baked)
                    return hit
                }
            }
            val supertonic = premium as? SupertonicRoute
            val premiumFast = supertonic != null &&
                (supertonic.hasPrewarmed(text, locale) ||
                    (supertonic.isWarm && (supertonic.lastFirstAudioMs ?: Long.MAX_VALUE) <= URGENT_FIRST_AUDIO_MAX_MS))
            // isReady() is asked only once the engine is fast: on a cold engine it is the ORT load itself.
            val miss = when {
                !premiumFast && supertonic?.lastLoadError != null ->
                    LiveMiss(VoiceFallback.Reason.LIVE_NOT_LOADED, liveLoadDetail())
                !premiumFast -> LiveMiss(VoiceFallback.Reason.LIVE_UNPROVEN, unprovenDetail(supertonic))
                !premium.isReady() -> LiveMiss(VoiceFallback.Reason.LIVE_NOT_LOADED, liveLoadDetail())
                else -> {
                    val result = supertonic?.speak(text, locale, isNavigation = true)
                        ?: premium.speak(text, locale)
                    if (result.isSuccess) {
                        spoke(TtsRoute.Premium)
                        return result
                    }
                    LiveMiss(VoiceFallback.Reason.LIVE_FAILED, result.exceptionOrNull()?.message)
                }
            }
            if (systemFallback.isReady()) {
                val sysResult = (systemFallback as? AndroidSystemTts)?.speak(text, locale, isNavigation = true)
                    ?: systemFallback.speak(text, locale)
                if (sysResult.isSuccess) {
                    _currentRoute.value = TtsRoute.System
                    note(miss.reason, text, miss.detail)
                    return sysResult
                }
            }
            val last = supertonic?.speak(text, locale, isNavigation = true) ?: premium.speak(text, locale)
            if (last.isSuccess) {
                spoke(TtsRoute.Premium)
            } else {
                _currentRoute.value = TtsRoute.None
                note(VoiceFallback.Reason.NO_VOICE, text, miss.detail ?: last.exceptionOrNull()?.message)
            }
            return last
        } finally {
            SpeechActivityHub.end()
        }
    }

    /**
     * HIGHWAY / voice — [speak]'s fallback order, but premium/system are
     * asked to use the ASSISTANCE_NAVIGATION_GUIDANCE channel instead of
     * chat's. For SOON/INFO guidance cues, which don't preempt like
     * [speakUrgent]'s NOW tier but are still navigation, not chat.
     * Exclusively fed by [local.phantom.companion.core.nav.NavCueSpeaker]
     * via AppContainer.navSpeaker.
     */
    suspend fun speakNavigation(text: String, locale: String = "uk-UA"): Result<Unit> {
        SpeechActivityHub.begin(text)
        try {
            baked?.let { pack ->
                val hit = pack.speak(text, locale)
                if (hit.isSuccess) {
                    spoke(TtsRoute.Baked)
                    return hit
                }
            }
            val miss = if (premium.isReady()) {
                val result = (premium as? SupertonicRoute)?.speak(text, locale, isNavigation = true)
                    ?: premium.speak(text, locale)
                if (result.isSuccess) {
                    spoke(TtsRoute.Premium)
                    return result
                }
                LiveMiss(VoiceFallback.Reason.LIVE_FAILED, result.exceptionOrNull()?.message)
            } else {
                LiveMiss(VoiceFallback.Reason.LIVE_NOT_LOADED, liveLoadDetail())
            }
            val sysResult = (systemFallback as? AndroidSystemTts)?.speak(text, locale, isNavigation = true)
                ?: systemFallback.speak(text, locale)
            return handOff(text, miss, sysResult)
        } finally {
            SpeechActivityHub.end()
        }
    }

    private fun handOff(text: String, miss: LiveMiss, sysResult: Result<Unit>): Result<Unit> {
        if (miss.reason == VoiceFallback.Reason.LIVE_NOT_LOADED && !absentLogged) {
            absentLogged = true
            Log.w(TAG, "Supertonic absent (${miss.detail ?: "no model files"}): system voice speaks")
        }
        _currentRoute.value = if (sysResult.isSuccess) TtsRoute.System else TtsRoute.None
        note(
            if (sysResult.isSuccess) miss.reason else VoiceFallback.Reason.NO_VOICE,
            text,
            miss.detail ?: sysResult.exceptionOrNull()?.message,
        )
        return sysResult
    }

    /**
     * Голос PHANTOM (живий синтез або запечена фраза — обидва його) справді
     * прозвучав, тож попередній відступ став історією і мусить зникнути з
     * плашки. Скидаємо ЛИШЕ звідси, з доведеної репліки: [refreshPremiumStatus]
     * знає тільки про готовність двигуна, а готовність — ще не звук у динаміку.
     */
    private fun spoke(route: TtsRoute) {
        _currentRoute.value = route
        _lastFallback.value = null
    }

    private fun note(reason: VoiceFallback.Reason, text: String, detail: String?) {
        val fallback = VoiceFallback(reason, text, clockMs(), detail)
        _lastFallback.value = fallback
        _fallbacks.tryEmit(fallback)
    }

    private fun liveLoadDetail(): String? = (premium as? SupertonicRoute)?.lastLoadError?.message

    private fun unprovenDetail(supertonic: SupertonicRoute?): String =
        supertonic?.lastFirstAudioMs?.let { "first audio ${it}ms > ${URGENT_FIRST_AUDIO_MAX_MS}ms" }
            ?: "first audio never measured"

    /**
     * Один прогрів живого голосу, щойно застосунок намалював перший кадр.
     * false — Supertonic на телефоні немає або він не вантажиться; тоді
     * говорить системний голос, і про це один рядок у журналі.
     */
    suspend fun warmUp(locale: String = "uk-UA"): Boolean {
        val supertonic = premium as? SupertonicRoute ?: return false
        if (!supertonic.isReady()) {
            if (!absentLogged) {
                absentLogged = true
                Log.w(TAG, "Supertonic absent (${liveLoadDetail() ?: "no model files"}): system voice speaks")
            }
            return false
        }
        val warmed = supertonic.warmUp(locale)
        warmed.exceptionOrNull()?.let { Log.w(TAG, "Supertonic warm-up failed: ${it.message}") }
        if (warmed.isSuccess) _currentRoute.value = TtsRoute.Premium
        return warmed.isSuccess
    }

    /** ON-TIME VOICE — hand a predicted cue to the premium engine's cache,
     *  so [speakUrgent] can give it the beautiful voice AND a fast start. */
    suspend fun prewarm(text: String, locale: String = "uk-UA") {
        (premium as? SupertonicRoute)?.prewarm(text, locale)
    }

    override fun stop() {
        baked?.stop()
        premium.stop()
        systemFallback.stop()
        SpeechActivityHub.end()
    }

    override fun setGain(gain: Float) {
        premium.setGain(gain)
    }

    /**
     * ЖИВА РОЗМОВА — потокова репліка через преміальний голос, коли він
     * доступний. `null` означає «стрімити нема чим» — той, хто питає,
     * повертається до звичайного [speak] по завершенні ходу.
     */
    suspend fun openStream(locale: String = "uk-UA"): SpeechSpan? {
        val supertonic = premium as? SupertonicRoute ?: return null
        if (!premium.isReady()) return null
        _currentRoute.value = TtsRoute.Premium
        val span = supertonic.openStream(locale)
        // Більшість реплік іде цим шляхом, а не через speak(), — без цієї
        // обгортки слід відступу пережив би цілу живу розмову. Відкритий потік
        // сам по собі ще не звук: стирає слід тільки синтезований шматок
        // (порожній сегмент повертає true, нічого не промовивши).
        return object : SpeechSpan by span {
            override suspend fun say(segment: String): Boolean =
                span.say(segment).also { if (it && segment.isNotBlank()) spoke(TtsRoute.Premium) }
        }
    }

    /** ЖИВА РОЗМОВА — фраза-підтвердження в кеш, щоб вона стартувала миттєво. */
    suspend fun prewarmAll(lines: List<String>, locale: String = "uk-UA") {
        val supertonic = premium as? SupertonicRoute ?: return
        lines.forEach { runCatching { supertonic.prewarm(it, locale) } }
    }

    /**
     * QH3 — re-evaluate which engine is active and update [currentRoute]
     * without speaking. AppContainer calls this on
     * [DownloadEvent.Completed] for SUPERTONIC so the Settings indicator
     * flips to «голос: Supertonic 3» the moment the files land —
     * no need to wait for the next speak() to find out.
     */
    /** Тиск памʼяті: звільнити важкий голос; системний лишається. */
    suspend fun releasePremium() {
        (premium as? SupertonicRoute)?.release()
    }

    suspend fun refreshPremiumStatus() {
        _currentRoute.value = when {
            premium.isReady() -> TtsRoute.Premium
            systemFallback.isReady() -> TtsRoute.System
            else -> TtsRoute.None
        }
    }

    private companion object {
        const val TAG = "RoutedTts"

        /** A maneuver cue tolerates this much first-audio latency and no more. */
        const val URGENT_FIRST_AUDIO_MAX_MS = 700L
    }
}
