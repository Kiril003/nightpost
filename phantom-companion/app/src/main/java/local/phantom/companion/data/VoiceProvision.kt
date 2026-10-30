package local.phantom.companion.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import local.phantom.companion.core.ai.ModelManager
import local.phantom.companion.core.ai.ModelState
import local.phantom.companion.core.ai.ModelType
import local.phantom.companion.ui.settings.VoiceSettingsCopy

/** What the operator is told about the live voice — every branch a word, never a spinner over nothing. */
sealed interface LiveVoiceState {
    data object Ready : LiveVoiceState
    data class Fetching(val progress: Float) : LiveVoiceState
    /**
     * 05.09 privacy audit — the resting state on every fresh install, and
     * forever after until the operator asks. Before this state existed,
     * [decideLiveVoice] fell straight through to [Fetch] the moment the
     * phone saw an unmetered network, so ~400 MB left for huggingface.co
     * with no tap and no key — see phantom-phone-downloads-without-a-key.
     * The system voice speaks the whole time this is the state; nothing
     * about the app depends on this model ever landing.
     */
    data object NotRequested : LiveVoiceState
    data object Offline : LiveVoiceState
    data class WaitingWifi(val sizeMb: Int) : LiveVoiceState
    data class NoSpace(val needMb: Int) : LiveVoiceState
    data class Stalled(val reason: String) : LiveVoiceState
    /** Decided: start the transfer now. */
    data object Fetch : LiveVoiceState
}

/** Повний голос тримає ~470 МБ під час синтезу, економний ~290; нижче 8 ГБ ОЗП беремо економний. */
internal fun liveVoiceModelFor(totalRamBytes: Long): ModelType =
    if (totalRamBytes in 1 until LITE_VOICE_BELOW_BYTES) ModelType.SUPERTONIC_LITE else ModelType.SUPERTONIC

/** Голос, що вже лежить на диску, говорить одразу: повний на малому телефоні кращий за системного робота. */
internal fun liveVoiceModelFor(totalRamBytes: Long, onDisk: (ModelType) -> Boolean): ModelType {
    val wanted = liveVoiceModelFor(totalRamBytes)
    val other = if (wanted == ModelType.SUPERTONIC_LITE) ModelType.SUPERTONIC else ModelType.SUPERTONIC_LITE
    return if (!onDisk(wanted) && onDisk(other)) other else wanted
}

internal const val LITE_VOICE_BELOW_BYTES: Long = 8L * 1024 * 1024 * 1024

internal const val LIVE_VOICE_SIZE_MB: Int = VoiceSettingsCopy.SUPERTONIC_SIZE_MB

// Slack for the .part files and the style extras that ride along.
internal const val LIVE_VOICE_NEED_BYTES: Long = (LIVE_VOICE_SIZE_MB + 50L) * 1024L * 1024L

/**
 * Pure policy: the live voice is free, so once asked for it fetches itself —
 * but never a byte before the operator asks, and never on his money without
 * a second word. [requested] is that first word: it is the operator's own
 * tap (the CapabilityStrip prompt or the model row in Налаштування → Голос),
 * persisted, so it is asked at most once per install, not once per app start.
 */
internal fun decideLiveVoice(
    model: ModelState?,
    requested: Boolean,
    online: Boolean,
    metered: Boolean,
    allowMobile: Boolean,
    freeBytes: Long,
    needBytes: Long = LIVE_VOICE_NEED_BYTES,
): LiveVoiceState {
    val stalledBy = model?.error
    return when {
        model?.isPresent == true -> LiveVoiceState.Ready
        model?.isDownloading == true -> LiveVoiceState.Fetching(model.progress)
        !requested -> LiveVoiceState.NotRequested
        stalledBy != null -> LiveVoiceState.Stalled(stalledBy)
        !online -> LiveVoiceState.Offline
        freeBytes < needBytes -> LiveVoiceState.NoSpace((needBytes / (1024L * 1024L)).toInt())
        metered && !allowMobile -> LiveVoiceState.WaitingWifi(LIVE_VOICE_SIZE_MB)
        else -> LiveVoiceState.Fetch
    }
}

class LiveVoiceProvisioner(
    private val models: ModelManager,
    private val requested: Flow<Boolean>,
    private val online: Flow<Boolean>,
    private val metered: Flow<Boolean>,
    private val allowMobile: Flow<Boolean>,
    private val freeBytes: () -> Long,
    private val scope: CoroutineScope,
    private val needBytes: Long = LIVE_VOICE_NEED_BYTES,
    private val model: ModelType = ModelType.SUPERTONIC,
) {
    private val _state = MutableStateFlow<LiveVoiceState>(LiveVoiceState.NotRequested)
    val state: StateFlow<LiveVoiceState> = _state.asStateFlow()

    private data class Inputs(
        val model: ModelState?,
        val requested: Boolean,
        val online: Boolean,
        val metered: Boolean,
        val allow: Boolean,
    )

    private var last = Inputs(null, false, false, true, false)

    fun arm() {
        scope.launch {
            combine(models.states, requested, online, metered, allowMobile) { s, r, o, m, a ->
                Inputs(s[model], r, o, m, a)
            }.collect { inputs ->
                last = inputs
                evaluate(inputs, force = false)
            }
        }
    }

    /** A Stalled voice is terminal until asked again; a network flip alone must not loop a dead download. */
    fun retry() = evaluate(last, force = true)

    private fun evaluate(inputs: Inputs, force: Boolean) {
        val decided = decideLiveVoice(
            inputs.model, inputs.requested, inputs.online, inputs.metered, inputs.allow, freeBytes(), needBytes,
        )
        val next = if (force && decided is LiveVoiceState.Stalled) LiveVoiceState.Fetch else decided
        if (next is LiveVoiceState.Fetch) {
            _state.value = LiveVoiceState.Fetching(0f)
            models.startDownload(model)
        } else {
            _state.value = next
        }
    }
}

/** Process-scoped handle: the container is frozen for edits, so Settings reads the voice from here. */
object LiveVoice {
    @Volatile
    var provisioner: LiveVoiceProvisioner? = null

    private val unarmed = MutableStateFlow<LiveVoiceState>(LiveVoiceState.NotRequested)

    val state: StateFlow<LiveVoiceState>
        get() = provisioner?.state ?: unarmed

    fun retry() {
        provisioner?.retry()
    }
}

/** True while the default network bills by the byte; absent network reads as metered. */
fun meteredNetworkFlow(context: Context): Flow<Boolean> = callbackFlow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    if (cm == null) {
        trySend(true)
        awaitClose()
        return@callbackFlow
    }
    fun meteredOf(caps: NetworkCapabilities?): Boolean =
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)?.not() ?: true
    trySend(meteredOf(runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()))
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            trySend(meteredOf(caps))
        }

        override fun onLost(network: Network) {
            trySend(true)
        }
    }
    runCatching { cm.registerDefaultNetworkCallback(callback) }
        .onFailure { trySend(true) }
    awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
}
