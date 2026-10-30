package local.phantom.companion.core.voice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Спільний мовчазний канал: один екземпляр на процес, ніхто в нього не пише. */
private val NO_STT_NOTICES = MutableSharedFlow<SttNotice>()

/**
 * Test-friendly contract for everything a consumer of VoiceManager needs.
 * Lets ViewModels accept a fake without subclassing the Android-bound implementation.
 */
interface VoiceController {
    val sttResults: SharedFlow<String>
    val partialResults: SharedFlow<String>

    /** Те, чого слух НЕ розібрав, — словами. Порожньо для тест-дублерів. */
    val sttNotices: SharedFlow<SttNotice> get() = NO_STT_NOTICES
    val audioLevel: StateFlow<Float>
    val activeEngine: StateFlow<SttRouteKind>
    fun startCapturing()
    suspend fun stopCapturing(): String?

    /**
     * ЖИВА РОЗМОВА — закрити репліку, НЕ закриваючи вухо.
     *
     * [stopCapturing] тримає обидві ролі: воно і завершує розпізнавання, і
     * зупиняє мікрофон. У живому діалозі це означало, що між кожними двома
     * репліками AudioRecord вмирав і народжувався знову — рівно там, куди
     * лягає продовження фрази, — а разом із сесією щоразу перезбігався й
     * апаратний придушувач ехо.
     *
     * Тут репліка закривається зливом розпізнавача, а мікрофон лишається
     * відкритим. Типова реалізація за замовчуванням зберігає стару
     * поведінку для тест-дублерів.
     */
    suspend fun finalizeUtterance(): String? = stopCapturing()

    /**
     * OMNIPRESENT VOICE — whether the live capture session has a genuinely
     * enabled [android.media.audiofx.AcousticEchoCanceler] on it.
     *
     * The duplex layer needs this to decide how much to trust the mic while
     * PHANTOM is speaking; see
     * [local.phantom.companion.core.voice.duplex.BargeInGate]. Defaulted so
     * existing fakes in tests keep compiling — and defaulted to `false`,
     * which is the conservative answer (strict barge-in), never the flattering
     * one.
     */
    val echoCancelled: StateFlow<Boolean> get() = MutableStateFlow(false)

    /**
     * OMNIPRESENT VOICE — decouple *hearing* from *transcribing*.
     *
     * In a duplex channel the mic stays open while PHANTOM talks, because
     * that is the only way to notice the operator cutting in. But the capture
     * stream during that window contains PHANTOM's own voice (echo), and
     * feeding it to the STT engines would transcribe him talking to himself
     * and dispatch it to the model as if the operator had said it.
     *
     * So while speaking, the recorder keeps running — amplitude keeps flowing
     * to [AudioLevelHub], the barge-in gate keeps watching — but the chunks
     * are dropped on the floor instead of reaching the recogniser. The instant
     * a real barge-in is detected, transcription is re-enabled and the
     * operator's words are captured from that moment on.
     *
     * Default no-op keeps existing [VoiceController] fakes valid.
     */
    fun setTranscriptionEnabled(enabled: Boolean) = Unit

    /**
     * Чи буде PHANTOM говорити в цей самий мікрофон.
     *
     * Від цього залежить, який тракт запису відкриє ОС: дуплексу потрібне
     * придушення ехо й воно приходить разом із AGC телефонії, а одиночному
     * запису AGC тільки шкодить (див. [CapturePolicy]). Ставиться ПЕРЕД
     * [startCapturing] — джерело обирається в мить відкриття сесії.
     *
     * Типова реалізація нічого не робить: натисни-і-говори — не дуплекс, і
     * тест-дублери лишаються чинними.
     */
    fun setDuplex(enabled: Boolean) = Unit
}
