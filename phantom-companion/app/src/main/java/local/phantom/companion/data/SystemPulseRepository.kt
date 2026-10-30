package local.phantom.companion.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import local.phantom.companion.core.context.ContextEngine
import local.phantom.companion.core.context.ContextSnapshot
import local.phantom.companion.core.context.SystemState
import local.phantom.companion.core.sensor.MobileSensorAdapter
import local.phantom.companion.core.sensor.SensorBatch
import kotlin.math.cos

/**
 * Single source of truth for the System Pulse header. Aggregates live signals
 * from [ContextEngine], [MobileSensorAdapter], and the AI/Link availability
 * providers into one [StateFlow] the UI can subscribe to.
 *
 * The four "constellation dots" map to:
 *  - AI     — composite of cloud key configured / local model ready
 *  - VOICE  — current mic_rms level normalised to 0..1
 *  - SENSOR — pulses on each SensorBatch tick (1 Hz)
 *  - LINK   — WS connection to PHANTOM OS
 */
class SystemPulseRepository(
    private val contextEngine: ContextEngine?,
    private val sensorAdapter: MobileSensorAdapter?,
    private val aiAvailable: () -> Boolean,
    private val linkConnected: () -> Boolean,
) {
    private val _pulse = MutableStateFlow(initialPulse())
    val pulse: StateFlow<SystemPulse> = _pulse.asStateFlow()

    private val _events = MutableSharedFlow<StateTransition>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    val transitions: SharedFlow<StateTransition> = _events.asSharedFlow()

    private var lastState: SystemState = SystemState.FOCUS
    private var stateSinceMs: Long = System.currentTimeMillis()
    private var latestSnapshot: ContextSnapshot? = null
    private var latestBatchTimestampMs: Long = 0L
    private var latestMicDb: Float? = null

    fun start(scope: CoroutineScope) {
        contextEngine?.snapshots?.let { flow ->
            scope.launch { flow.collect { onSnapshot(it) } }
        }
        sensorAdapter?.batches?.let { flow ->
            scope.launch { flow.collect { onBatch(it) } }
        }
        scope.launch {
            while (true) {
                tick()
                delay(500)
            }
        }
    }

    private fun onSnapshot(snap: ContextSnapshot) {
        if (snap.state != lastState) {
            val previous = lastState
            lastState = snap.state
            stateSinceMs = snap.timestampMs
            _events.tryEmit(
                StateTransition(
                    from = previous,
                    to = snap.state,
                    timestampMs = snap.timestampMs,
                    rationale = snap.rationale.firstOrNull { it.isNotBlank() },
                ),
            )
        }
        latestSnapshot = snap
        tick()
    }

    private fun onBatch(batch: SensorBatch) {
        latestBatchTimestampMs = batch.timestampMs
        latestMicDb = batch.micRmsDb
        tick()
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val snap = latestSnapshot

        val ai = run {
            val available = runCatching { aiAvailable() }.getOrDefault(false)
            PulseSignal(
                brightness = if (available) 0.55f else 0f,
                label = "AI",
                status = if (available) PulseSignal.Status.IDLE else PulseSignal.Status.OFF,
            )
        }
        val link = run {
            val connected = runCatching { linkConnected() }.getOrDefault(false)
            PulseSignal(
                brightness = if (connected) 0.7f else 0f,
                label = "LINK",
                status = if (connected) PulseSignal.Status.LIVE else PulseSignal.Status.OFF,
            )
        }
        val voice = run {
            val db = latestMicDb
            if (db != null) {
                val normalized = ((db + 80f) / 60f).coerceIn(0f, 1f)
                PulseSignal(
                    brightness = normalized,
                    label = "VOICE",
                    status = PulseSignal.Status.LIVE,
                )
            } else {
                PulseSignal(0f, "VOICE", PulseSignal.Status.OFF)
            }
        }
        val sensor = run {
            val sinceTick = now - latestBatchTimestampMs
            if (latestBatchTimestampMs > 0L && sinceTick < 2_500L) {
                val phase = ((now / 220.0) % (2 * Math.PI)).toFloat()
                val pulseBrightness = 0.55f + 0.30f * cos(phase)
                PulseSignal(
                    brightness = pulseBrightness.coerceIn(0f, 1f),
                    label = "SENSOR",
                    status = PulseSignal.Status.LIVE,
                )
            } else {
                PulseSignal(0f, "SENSOR", PulseSignal.Status.OFF)
            }
        }

        _pulse.value = SystemPulse(
            state = lastState,
            dwellMs = now - stateSinceMs,
            narrative = composeNarrative(snap),
            confidence = snap?.confidence ?: 0f,
            ai = ai,
            voice = voice,
            sensor = sensor,
            link = link,
        )
    }

    private fun composeNarrative(snap: ContextSnapshot?): String {
        if (snap == null) return "система прокидається"
        val parts = snap.rationale.filter { it.isNotBlank() }.distinct().take(2)
        if (parts.isEmpty()) return stateNarrative(lastState)
        return parts.joinToString(" · ")
    }

    private fun stateNarrative(state: SystemState): String = when (state) {
        SystemState.FOCUS    -> "робочий ритм"
        SystemState.SENTINEL -> "пасивне спостереження"
        SystemState.GHOST    -> "режим примари"
        SystemState.SHADOW   -> "енергозбереження"
        SystemState.DIALOGUE -> "спілкування"
        SystemState.DREAM    -> "фонова обробка"
    }

    private fun initialPulse(): SystemPulse = SystemPulse(
        state = SystemState.FOCUS,
        dwellMs = 0L,
        narrative = "ініціалізація",
        confidence = 0f,
        ai = PulseSignal(0f, "AI", PulseSignal.Status.OFF),
        voice = PulseSignal(0f, "VOICE", PulseSignal.Status.OFF),
        sensor = PulseSignal(0f, "SENSOR", PulseSignal.Status.OFF),
        link = PulseSignal(0f, "LINK", PulseSignal.Status.OFF),
    )
}

data class SystemPulse(
    val state: SystemState,
    val dwellMs: Long,
    val narrative: String,
    val confidence: Float,
    val ai: PulseSignal,
    val voice: PulseSignal,
    val sensor: PulseSignal,
    val link: PulseSignal,
)

data class PulseSignal(
    val brightness: Float,
    val label: String,
    val status: Status,
) {
    enum class Status { LIVE, IDLE, OFF }
}

data class StateTransition(
    val from: SystemState,
    val to: SystemState,
    val timestampMs: Long,
    val rationale: String?,
)
