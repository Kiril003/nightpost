package local.phantom.companion.core.embodiment

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import local.phantom.companion.core.context.SystemState

/**
 * Tier 14-A — runs the 30-second per-state haptic tick. Reads the
 * live FSM state from [stateFlow], looks up the right pattern, emits
 * through [HapticEmitter]. Cancels itself cleanly on stop.
 *
 * Cadence is configurable so the constitutional layer can crank it
 * down when the operator is in GHOST or when the battery profile
 * forbids periodic vibration.
 */
class ProprioceptiveTicker(
    private val stateFlow: Flow<SystemState>,
    private val emitter: HapticEmitter,
    private val cadenceMs: Long = DEFAULT_CADENCE_MS,
    /** Returns the wall-time delay before the next tick. Default uses [cadenceMs]. */
    private val delayProvider: () -> Long = { DEFAULT_CADENCE_MS },
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            stateFlow.collectLatest { state ->
                while (true) {
                    val pattern = HapticPattern.forState(state)
                    if (pattern !== HapticPattern.GHOST) emitter.emit(pattern)
                    delay(delayProvider().coerceAtLeast(MIN_CADENCE_MS))
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        emitter.cancel()
    }

    companion object {
        /** 30-second cadence per vision Tier 14-A. */
        const val DEFAULT_CADENCE_MS = 30_000L
        const val MIN_CADENCE_MS = 5_000L
    }
}
