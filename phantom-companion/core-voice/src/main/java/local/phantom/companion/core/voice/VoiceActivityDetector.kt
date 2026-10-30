package local.phantom.companion.core.voice

/**
 * SV-2 — voice-activity detector contract.
 *
 * Implementations are pure: feed amplitude or PCM, get back a stream
 * of [VadEvent.SpeechStart] / [VadEvent.SpeechEnd] events. The
 * default [EnergyVad] uses dual-threshold + duration gating on 0..1
 * amplitude (which AudioRecorder publishes to AudioLevelHub).
 *
 * SV-3 will swap in a Silero ONNX impl backed by the bundled
 * `assets/silero_vad.onnx` model. The interface is designed so the
 * service swap is a one-line substitution — Silero processes 512-sample
 * windows and yields the same SpeechStart/SpeechEnd events.
 */
interface VoiceActivityDetector {
    /** Push a new amplitude reading (0..1). Returns the resulting event, or null. */
    fun onAmplitude(level: Float, nowMs: Long): VadEvent?

    /** Reset internal state — call when releasing or pausing. */
    fun reset()
}

sealed interface VadEvent {
    data object SpeechStart : VadEvent
    data object SpeechEnd : VadEvent
}

/**
 * Dual-threshold energy VAD with duration gating.
 *
 * State machine:
 *   - SILENT       — looking for amplitude ≥ [speechThreshold] sustained
 *                    for [speechMinMs] → emit SpeechStart, transition
 *                    to SPEAKING.
 *   - SPEAKING     — looking for amplitude < [silenceThreshold] sustained
 *                    for [silenceMinMs] → emit SpeechEnd, transition
 *                    back to SILENT.
 *
 * Defaults are tuned for a quiet room with the operator within 1m of
 * the phone:
 *   - 0.05 amplitude (≈ -26 dBFS) to start
 *   - 0.02 amplitude (≈ -34 dBFS) to end
 *   - 250 ms continuous above to debounce keyboard taps / coughs
 *   - 800 ms continuous below to allow natural pauses inside an
 *     utterance ("Фантоме… скільки зараз годин")
 *
 * Cross-fades between SILENT and SPEAKING use *sustained* duration so
 * a single noisy spike doesn't trigger; the operator's voice has to
 * stay above the threshold for the whole window.
 */
class EnergyVad(
    private val speechThreshold: Float = 0.05f,
    private val silenceThreshold: Float = 0.02f,
    private val speechMinMs: Long = 250L,
    private val silenceMinMs: Long = 800L,
) : VoiceActivityDetector {

    private enum class State { SILENT, SPEAKING }

    // Sentinel must be < any plausible nowMs (which can be 0 in tests).
    private val unset = -1L
    private var state = State.SILENT
    private var aboveSinceMs = unset
    private var belowSinceMs = unset

    /**
     * Шумовий підлог кімнати.
     *
     * Пороги були абсолютними: 0.05 почати, 0.02 закінчити. У тиші це
     * працює, але варто увімкнути вентилятор, вийти на вулицю чи ввімкнути
     * телевізор — і рівень уже ніколи не падає нижче 0.02. SpeechEnd не
     * настає, репліка не завершується, і жива розмова просто мовчить у
     * відповідь. Тому поріг тепер рахується ВІД тиші цієї кімнати, а
     * абсолютні значення лишились нижньою межею.
     *
     * Оновлюється лише в стані SILENT і лише з кадрів, тихіших за поріг
     * початку мовлення, — інакше підлог зʼїв би сам голос власника.
     */
    private var noiseFloor = 0f

    private val floorAlpha = 0.05f
    private val floorCeiling = 0.35f

    private val speechGate: Float
        get() = maxOf(speechThreshold, noiseFloor + speechThreshold)

    private val silenceGate: Float
        get() = maxOf(silenceThreshold, noiseFloor + silenceThreshold)

    override fun onAmplitude(level: Float, nowMs: Long): VadEvent? {
        val speechThreshold = speechGate
        val silenceThreshold = silenceGate
        if (state == State.SILENT && level < speechThreshold) {
            noiseFloor = (noiseFloor * (1f - floorAlpha) + level * floorAlpha)
                .coerceIn(0f, floorCeiling)
        }
        return when (state) {
            State.SILENT -> {
                if (level >= speechThreshold) {
                    if (aboveSinceMs == unset) aboveSinceMs = nowMs
                    if (nowMs - aboveSinceMs >= speechMinMs) {
                        state = State.SPEAKING
                        belowSinceMs = unset
                        aboveSinceMs = unset
                        VadEvent.SpeechStart
                    } else null
                } else {
                    aboveSinceMs = unset
                    null
                }
            }
            State.SPEAKING -> {
                if (level < silenceThreshold) {
                    if (belowSinceMs == unset) belowSinceMs = nowMs
                    if (nowMs - belowSinceMs >= silenceMinMs) {
                        state = State.SILENT
                        aboveSinceMs = unset
                        belowSinceMs = unset
                        VadEvent.SpeechEnd
                    } else null
                } else {
                    belowSinceMs = unset
                    null
                }
            }
        }
    }

    /** Шумовий підлог переживає reset: кімната не змінюється між репліками. */
    override fun reset() {
        state = State.SILENT
        aboveSinceMs = unset
        belowSinceMs = unset
    }
}
