package local.phantom.companion.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.voice.route.VoiceTuning

private const val PREFS_NAME = "phantom_voice_prefs"
private const val PREFS_FALLBACK_NAME = "phantom_voice_prefs_fallback"
private const val KEY_TTS_ENABLED = "tts_enabled"
// Phase 3-E5 — Whisper Line settings
private const val KEY_WHISPER_OVERLAY_ENABLED = "whisper_overlay_enabled"
private const val KEY_WHISPER_FREQ_ORDINAL = "whisper_freq_ordinal"
private const val KEY_WHISPER_SOUND_ENABLED = "whisper_sound_enabled"
// Phase 2-A5 — STT enabled flag (default true; operator can disable
// during onboarding or in Settings to fall back to text-only input).
private const val KEY_STT_ENABLED = "stt_enabled"
// Просодія системного голосу: висота й темп, які крутить оператор.
// `KEY_TTS_PROSODY_SET` окремо від самих чисел навмисно — без нього «1.0»
// означало б водночас «людина обрала нейтраль» і «людина не обирала нічого»,
// а це різні речі: у другому випадку ми не маємо права чіпати темп, який
// вона виставила в системних налаштуваннях доступності.
private const val KEY_TTS_PITCH = "tts_pitch"
private const val KEY_TTS_RATE = "tts_rate"
private const val KEY_TTS_PROSODY_SET = "tts_prosody_set"
private const val KEY_PROACTIVE_ENABLED = "proactive_voice_enabled"
// W2-5 — retention TTL for the persisted Stream history. Default 90
// days; setting to 0 disables cleanup entirely (operator's choice).
// Last-cleanup timestamp is the «throttle» so the daily check only
// hits the table once every 24h regardless of how often the app is
// opened.
private const val KEY_CLEANUP_AFTER_DAYS = "cleanup_after_days"
private const val KEY_LAST_CLEANUP_AT = "last_cleanup_at_ms"
private const val KEY_LAST_DIGEST_EMIT_AT = "last_digest_emit_at_ms"
// W4-2 — Phase 3 Screen Awareness opt-in. The Accessibility-grant
// in OS Settings is necessary but not sufficient; the operator can
// flip this off in-app to stop feeding screen text to the AI without
// going to OS settings. Default false — operator-trust-defining
// permission, must be explicit.
private const val KEY_SCREEN_AWARENESS_ENABLED = "screen_awareness_enabled"
// MIC-2 — operator-controlled hard mute. When true, every mic-owning
// surface (PTT, future Live Dialog, future wake-word) refuses to start.
// Default false. The orb still draws (it samples AudioLevelHub which
// stays at 0f) — what changes is no recorder will open. Settings →
// Privacy → Микрофон exposes the toggle + a status panel naming the
// current owner, so the operator can always answer "who is holding
// the mic right now" without going to logcat.
private const val KEY_MIC_MUTED = "mic_muted"
// SV-2 — continuous Live Dialog mode. When true, LiveDialogService
// runs as a foreground service holding voiceManager open and using
// VAD on the recorder's amplitude to detect turn boundaries; final
// transcripts land in the same sttResults SharedFlow PTT uses, so
// the chat dispatch path is shared. Default false — operator opts
// in explicitly because the mic indicator stays on continuously.
private const val KEY_LIVE_DIALOG_ENABLED = "live_dialog_enabled"
private const val KEY_LIVE_VOICE_ON_MOBILE = "live_voice_on_mobile"
private const val KEY_LIVE_VOICE_REQUESTED = "live_voice_requested"

/**
 * Persists voice-output preferences using the same Keystore-backed
 * EncryptedSharedPreferences pattern as [AiKeyStore].
 *
 * If the Android Keystore is unavailable (corrupted after reinstall, MIUI
 * Keystore bug, or BouncyCastle JCE clash) the init falls back to plain
 * SharedPreferences so the application still boots. Voice preference is
 * non-sensitive (it is just a boolean toggle), so the fallback is safe.
 *
 * Exposes [ttsEnabled] as a [StateFlow] so composables can react to changes
 * (e.g. the Settings toggle flips the flag and [StreamViewModel] picks it up
 * on the next Final chunk without restarting).
 */
class VoicePreferences(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { t ->
        Log.e("VoicePreferences", "EncryptedSharedPreferences init failed — using plaintext fallback", t)
        context.getSharedPreferences(PREFS_FALLBACK_NAME, Context.MODE_PRIVATE)
    }

    // «Озвучку на входе убрать» — троє тестувальників, 11.09. Перше, що робив
    // застосунок на свіжому апараті, — говорив уголос, часто в чужому місці.
    // Сусідній код у PhantomNavGraph уже писав «off by default in fresh
    // installs»; істиною був цей рядок, і він казав протилежне.
    private val _ttsEnabled = MutableStateFlow(prefs.getBoolean(KEY_TTS_ENABLED, false))
    // Phase 3-E5 — Whisper Line: overlay on/off, drift frequency
    // (ordinal-encoded WhisperFreq), sound on/off.
    private val _whisperOverlayEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_WHISPER_OVERLAY_ENABLED, false),
    )
    private val _whisperFreqOrdinal = MutableStateFlow(
        prefs.getInt(KEY_WHISPER_FREQ_ORDINAL, 1).coerceIn(0, 2), // 1 = Medium default
    )
    private val _whisperSoundEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_WHISPER_SOUND_ENABLED, false),
    )
    // Phase 2-A5 — STT enabled. Default true so a fresh install gets
    // the voice loop; operator can flip false in onboarding skip path
    // or via Settings → Voice. PttButton checks this before dispatching
    // recordings.
    private val _sttEnabled = MutableStateFlow(prefs.getBoolean(KEY_STT_ENABLED, true))

    /** Whether the operator has enabled voice read-aloud for AI responses. */
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    /**
     * Persists and broadcasts the new TTS-enabled flag.
     *
     * [StreamViewModel] reads [ttsEnabled].value on every Final chunk so
     * changes take effect immediately — no restart required.
     */
    fun setTtsEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()
        _ttsEnabled.value = value
    }

    /* ---- Phase 3-E5 — Whisper Line ---- */

    /** Whether the always-on Whisper Line overlay is on. */
    val whisperOverlayEnabled: StateFlow<Boolean> = _whisperOverlayEnabled.asStateFlow()

    /**
     * Drift-speed ordinal: 0=Slow / 1=Medium / 2=Fast (per
     * [local.phantom.companion.core.design.component.WhisperFreq]).
     * Persisted by ordinal — reordering the enum silently corrupts
     * saves, so [WhisperFreqTest] in :core-design pins the order.
     */
    val whisperFreqOrdinal: StateFlow<Int> = _whisperFreqOrdinal.asStateFlow()

    /** Soft sound cue on whisper-line message change (Phase 3-E5). */
    val whisperSoundEnabled: StateFlow<Boolean> = _whisperSoundEnabled.asStateFlow()

    fun setWhisperOverlayEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_WHISPER_OVERLAY_ENABLED, value).apply()
        _whisperOverlayEnabled.value = value
    }

    fun setWhisperFreqOrdinal(ordinal: Int) {
        val safe = ordinal.coerceIn(0, 2)
        prefs.edit().putInt(KEY_WHISPER_FREQ_ORDINAL, safe).apply()
        _whisperFreqOrdinal.value = safe
    }

    fun setWhisperSoundEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_WHISPER_SOUND_ENABLED, value).apply()
        _whisperSoundEnabled.value = value
    }

    /* ---- Phase 2-A5 — STT enabled ---- */

    /** Whether voice input (PTT → STT) is enabled. Default true. */
    val sttEnabled: StateFlow<Boolean> = _sttEnabled.asStateFlow()

    /**
     * Unprompted speech. Default OFF: a companion that talks first is a
     * different product, and the operator has to choose it. This replaces a
     * compile-time constant that left the whole proactive engine built,
     * wired — and unreachable on every device.
     */
    private val _proactiveEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_PROACTIVE_ENABLED, false))
    val proactiveEnabled: StateFlow<Boolean> = _proactiveEnabled.asStateFlow()

    fun setProactiveEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_PROACTIVE_ENABLED, value).apply()
        _proactiveEnabled.value = value
    }

    fun setSttEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_STT_ENABLED, value).apply()
        _sttEnabled.value = value
    }


    /* ---- Просодія системного TTS (висота й темп) ---- */

    /**
     * Затиск живе у [VoiceTuning], а не тут: рушій приймає будь-який float
     * мовчки, тож зіпсоване число дало б белькіт без жодної помилки в лозі.
     * Приватний `sane` там ззовні не видно — беремо його через єдиний
     * відкритий вхід `of`.
     */
    private fun saneProsody(raw: Float): Float = VoiceTuning.of(raw, raw).pitch


    private val _pitch = MutableStateFlow(
        saneProsody(prefs.getFloat(KEY_TTS_PITCH, VoiceTuning.NEUTRAL))
    )

    /** Для повзунка. Показує нейтраль, доки оператор нічого не обрав. */
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _rate = MutableStateFlow(
        saneProsody(prefs.getFloat(KEY_TTS_RATE, VoiceTuning.NEUTRAL))
    )

    val rate: StateFlow<Float> = _rate.asStateFlow()

    private val _prosody = MutableStateFlow(
        if (prefs.getBoolean(KEY_TTS_PROSODY_SET, false)) {
            VoiceTuning.of(_pitch.value, _rate.value)
        } else {
            null
        }
    )

    /**
     * Те, що подається рушієві — і `null`, доки оператор не торкнувся повзунка.
     *
     * Саме цим воно відрізняється від [pitch]/[rate]: ті завжди мають число,
     * бо повзунок мусить десь стояти. А `null` тут означає «ми не маємо думки
     * про темп», і рушій тоді не кличе `setSpeechRate` узагалі — тобто далі
     * шанує системні налаштування доступності. Нейтральні 1.0 «за
     * замовчуванням» мовчки скасували б вибір людини, зроблений поза PHANTOM.
     */
    val prosody: StateFlow<VoiceTuning?> = _prosody.asStateFlow()

    fun setPitch(value: Float) {
        val safe = saneProsody(value)
        prefs.edit().putFloat(KEY_TTS_PITCH, safe).putBoolean(KEY_TTS_PROSODY_SET, true).apply()
        _pitch.value = safe
        _prosody.value = VoiceTuning.of(safe, _rate.value)
    }

    fun setRate(value: Float) {
        val safe = saneProsody(value)
        prefs.edit().putFloat(KEY_TTS_RATE, safe).putBoolean(KEY_TTS_PROSODY_SET, true).apply()
        _rate.value = safe
        _prosody.value = VoiceTuning.of(_pitch.value, safe)
    }

    /**
     * Назад до «ми не маємо думки про темп», а НЕ до нейтральних 1.0.
     *
     * Різниця не косметична: [KEY_TTS_PROSODY_SET] мусить згаснути, інакше
     * [prosody] лишиться непорожнім і рушій далі кликатиме `setSpeechRate(1.0)`
     * — тобто мовчки скасує прискорення, яке людина виставила в системних
     * налаштуваннях доступності. Повзунки при цьому стають на нейтраль, бо
     * стояти їм десь треба.
     */
    fun resetProsody() {
        prefs.edit()
            .putFloat(KEY_TTS_PITCH, VoiceTuning.NEUTRAL)
            .putFloat(KEY_TTS_RATE, VoiceTuning.NEUTRAL)
            .putBoolean(KEY_TTS_PROSODY_SET, false)
            .apply()
        _pitch.value = VoiceTuning.NEUTRAL
        _rate.value = VoiceTuning.NEUTRAL
        _prosody.value = null
    }


    /* ---- W2-5 — Stream retention TTL ---- */

    private val _cleanupAfterDays = MutableStateFlow(
        prefs.getInt(KEY_CLEANUP_AFTER_DAYS, DEFAULT_CLEANUP_DAYS).coerceIn(0, 365 * 5),
    )

    /** Days to retain Stream history. 0 disables cleanup. Default 90. */
    val cleanupAfterDays: StateFlow<Int> = _cleanupAfterDays.asStateFlow()

    fun setCleanupAfterDays(value: Int) {
        val safe = value.coerceIn(0, 365 * 5)
        prefs.edit().putInt(KEY_CLEANUP_AFTER_DAYS, safe).apply()
        _cleanupAfterDays.value = safe
    }

    /** Last time the cleanup job ran (epoch-ms). 0 = never. */
    fun lastCleanupAtMs(): Long = prefs.getLong(KEY_LAST_CLEANUP_AT, 0L)

    fun markCleanupRan(atMs: Long) {
        prefs.edit().putLong(KEY_LAST_CLEANUP_AT, atMs).apply()
    }

    /** Last time the notification digest was emitted today (epoch-ms). */
    fun lastDigestEmitAtMs(): Long = prefs.getLong(KEY_LAST_DIGEST_EMIT_AT, 0L)

    fun markDigestEmitted(atMs: Long) {
        prefs.edit().putLong(KEY_LAST_DIGEST_EMIT_AT, atMs).apply()
    }

    /* ---- W4-2 — Phase 3 Screen Awareness ---- */

    private val _screenAwarenessEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_SCREEN_AWARENESS_ENABLED, false),
    )

    /**
     * Whether the AI may consume `[SCREEN: app=…, top-text=…]` from the
     * AccessibilityService capture. The OS-side Accessibility grant is
     * a separate gate — both must be true for screen text to actually
     * flow into the AI prompt. Default false.
     */
    val screenAwarenessEnabled: StateFlow<Boolean> = _screenAwarenessEnabled.asStateFlow()

    fun setScreenAwarenessEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_SCREEN_AWARENESS_ENABLED, value).apply()
        _screenAwarenessEnabled.value = value
    }

    /* ---- MIC-2 — master microphone mute ---- */

    private val _micMuted = MutableStateFlow(prefs.getBoolean(KEY_MIC_MUTED, false))

    /**
     * Master mic mute. While true, [setPtt] in PhantomViewModel and any
     * future Live Dialog / wake-word service must refuse to open the
     * recorder. The orb visualiser still works visually because it
     * samples AudioLevelHub, which stays at 0f when no recorder is live.
     */
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    fun setMicMuted(value: Boolean) {
        prefs.edit().putBoolean(KEY_MIC_MUTED, value).apply()
        _micMuted.value = value
    }

    /* ---- SV-2 — continuous Live Dialog mode ---- */

    private val _liveDialogEnabled = MutableStateFlow(prefs.getBoolean(KEY_LIVE_DIALOG_ENABLED, false))

    /**
     * Continuous Live Dialog mode. When true, [LiveDialogService] runs
     * as a foreground service and the operator can speak without
     * holding PTT — VAD-detected turns finalise transcripts that
     * route through the same StreamViewModel chat dispatch as PTT.
     */
    val liveDialogEnabled: StateFlow<Boolean> = _liveDialogEnabled.asStateFlow()

    fun setLiveDialogEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE_DIALOG_ENABLED, value).apply()
        _liveDialogEnabled.value = value
    }

    private val _liveVoiceOnMobile = MutableStateFlow(prefs.getBoolean(KEY_LIVE_VOICE_ON_MOBILE, false))

    /** The operator's word that ~400 MB of live voice may ride mobile data. Off until said. */
    val liveVoiceOnMobile: StateFlow<Boolean> = _liveVoiceOnMobile.asStateFlow()

    fun setLiveVoiceOnMobile(value: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE_VOICE_ON_MOBILE, value).apply()
        _liveVoiceOnMobile.value = value
    }

    private val _liveVoiceRequested = MutableStateFlow(
        if (prefs.contains(KEY_LIVE_VOICE_REQUESTED)) prefs.getBoolean(KEY_LIVE_VOICE_REQUESTED, true)
        else true
    )

    /**
     * 05.09 privacy audit — the operator's word that Supertonic may fetch itself
     * AT ALL. Off on every fresh install: [LiveVoiceProvisioner] used to arm this
     * fetch unconditionally at boot, so ~400 MB left the phone for huggingface.co
     * on the first Wi-Fi connection with no tap and no key — the exact shape of
     * the promise this build is supposed to keep. The system voice speaks until
     * this flips true, from either the CapabilityStrip prompt or the model's own
     * download button in Налаштування → Голос; both set the same flag, so a
     * pause for a metered network or low disk resumes correctly either way.
     */
    val liveVoiceRequested: StateFlow<Boolean> = _liveVoiceRequested.asStateFlow()

    fun setLiveVoiceRequested(value: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE_VOICE_REQUESTED, value).apply()
        _liveVoiceRequested.value = value
    }

    companion object {
        // Owner's decision, not the engineer's: flipping this to 0 makes deletion
        // opt-in — no conversation is ever removed until the operator picks a term
        // by hand. The retention screen honours whatever this is. One-line change.
        const val DEFAULT_CLEANUP_DAYS: Int = 90
    }
}
