package local.phantom.companion.core.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Поріг ОЗП, від якого слух дає Vosk. 5,5 ГіБ, а не рівно 6: `MemTotal`
 *  віддає близько 91 % номіналу (SM-A525F, 8 ГБ → 7 668 936 кБ = 7,31 ГіБ),
 *  тож поріг у 6 ГіБ відсікав би саме ті «шестигігабайтні» апарати, заради
 *  яких він і писаний. */
const val VOSK_RAM_FLOOR_BYTES = 5_905_580_032L

/**
 * Яким вухом слухати цей апарат.
 *
 * Vosk точніший — 21,9 % проти 28,5 % WER (CV17 uk test, 300 кліпів, 12.09) —
 * але тримає 827 МБ RSS проти 248 у Moonshine. На апараті, де стільки нема,
 * точніша модель не програє в якості, а не запускається зовсім.
 */
fun hearingModelForRam(totalRamBytes: Long, voskShipped: Boolean = true): ModelType =
    if (voskShipped && totalRamBytes >= VOSK_RAM_FLOOR_BYTES) ModelType.VOSK else ModelType.MOONSHINE_UK

/** How much of an ear PHANTOM currently has. Mirrors [BrainState]. */
sealed interface HearingState {
    /** The STT model is on disk — the operator can be transcribed now. */
    data object Ready : HearingState
    /** No model yet, and PHANTOM is fetching one. [progress] is 0..1. */
    data class Provisioning(val progress: Float) : HearingState
    /** No model, none fetchable *yet* — offline. Resumes on reconnect. */
    data object Offline : HearingState
    /** The fetch failed for a reason retrying alone will not fix. */
    data class Stalled(val reason: String) : HearingState
    /** Voice was never touched — nothing fetched, nothing promised. */
    data object Idle : HearingState
}

/**
 * The hearing half of «Zero-Click AI Bootstrapping».
 *
 * [AiBootstrapper] fetches the LLM brain unattended so the operator is never
 * told to «configure AI». Hearing was left behind: with no Vosk model on disk
 * [VoiceManager] silently falls back to a fake engine — the mic runs, fake
 * partials tick, and not one word is ever transcribed. The operator talks to a
 * wall until he goes into Settings and taps «завантажити». That is the app
 * asking its user to do the app's job, and it is banned.
 *
 * This class closes that gap with the SAME [ModelManager] machinery: the first
 * time voice is actually used — a PTT press, the live-dialogue channel opening
 * — it pulls the ear [hearingModelForRam] picked for this device if missing:
 * Vosk (137 MiB over the wire, 416 MiB unpacked) where there is memory for it,
 * Moonshine (64 MiB) where there is not. It is a floor, not a ceiling: the
 * other ear stays one tap away in Settings, and the VAD needs no model at all
 * (EnergyVad is pure amplitude).
 *
 * Deliberately lazy: nothing is fetched at cold start. Intent is the licence —
 * touching voice arms the download (and, like the brain, overrides the meter,
 * because a silent app that will not hear until it sees Wi-Fi is worse than the
 * 137 MiB). The network watcher only re-fires once voice has been asked for, so
 * an operator who never speaks is never charged for an ear he isn't using.
 */
class HearingProvisioner(
    private val models: ModelManager,
    private val scope: CoroutineScope,
    /** Live connectivity. `null` probe → treated as online. */
    private val isOnline: () -> Boolean = { true },
    /** True when the active network bills by the byte. */
    private val isMetered: () -> Boolean = { false },
    /** The STT model PHANTOM may fetch unattended. Injectable for tests. */
    private val hearingModel: ModelType = ModelType.VOSK,
) {
    private val _state = MutableStateFlow<HearingState>(
        if (models.states.value[hearingModel]?.isPresent == true) HearingState.Ready
        else HearingState.Idle
    )
    val state: StateFlow<HearingState> = _state.asStateFlow()

    private val gate = Mutex()

    /** True once the transfer is under way (keeps us off the meter twice). */
    private var provisioning = false

    /** True once voice was actually touched — the network watcher stays inert
     *  until then, so a reconnect never pulls a model no one asked for. */
    @Volatile
    private var requested = false

    init {
        // The progress the operator sees comes from the transfer itself, so a
        // fetch started by Settings (a manual tap) still animates this state.
        scope.launch {
            models.downloadEvents.collect { ev ->
                if (ev.type != hearingModel) return@collect
                when (ev) {
                    is DownloadEvent.Started -> _state.value = HearingState.Provisioning(0f)
                    is DownloadEvent.Progress -> _state.value = HearingState.Provisioning(ev.ratio)
                    is DownloadEvent.Failed -> {
                        provisioning = false
                        _state.value = HearingState.Stalled(ev.message)
                    }
                    is DownloadEvent.Completed -> {
                        provisioning = false
                        _state.value = HearingState.Ready
                    }
                }
            }
        }
    }

    /**
     * Make sure a hearing model exists. Idempotent, cheap, safe to call on
     * every capture start — the download itself is de-duped by [ModelManager].
     *
     * @param urgent the operator is actively trying to talk — spend metered
     *   data rather than leave him unheard. First-PTT / first-live are urgent.
     */
    fun ensureHearing(urgent: Boolean = true) {
        requested = true
        scope.launch { ensureHearingNow(urgent) }
    }

    /** [ensureHearing]'s body, awaitable — the caller learns the state. */
    suspend fun ensureHearingNow(urgent: Boolean = true): HearingState = gate.withLock {
        if (models.states.value[hearingModel]?.isPresent == true) {
            provisioning = false
            _state.value = HearingState.Ready
            return@withLock HearingState.Ready
        }

        // Already transferring (this call, a previous one, or a Settings tap) —
        // let it finish; the event collector owns the state from here.
        if (provisioning || models.states.value[hearingModel]?.isDownloading == true) {
            return@withLock _state.value
        }

        if (!isOnline()) {
            _state.value = HearingState.Offline
            return@withLock HearingState.Offline
        }

        // The meter rule: an idle re-arm defers; a waiting operator does not.
        if (isMetered() && !urgent) {
            _state.value = HearingState.Offline
            return@withLock HearingState.Offline
        }

        provisioning = true
        _state.value = HearingState.Provisioning(0f)
        models.startDownload(hearingModel)
        return@withLock _state.value
    }

    /**
     * Wire the fetch to the network's return. Inert until voice has actually
     * been asked for — so this never turns a cold start's reconnect into an
     * unwanted download; it only resumes a fetch the operator already wanted.
     */
    fun observeNetwork(online: Flow<Boolean>) {
        scope.launch {
            online.collect { up -> if (up && requested) ensureHearingNow(urgent = false) }
        }
    }
}
