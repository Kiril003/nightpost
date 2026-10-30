package local.phantom.companion.core.context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StateMachine(
    initial: SystemState = SystemState.FOCUS,
    /**
     * Витримка мусить бути довшою за тик рушія (30 с), інакше вона не стримує
     * нічого: умова `now - lastChange >= 30_000` виконувалась на кожному тику.
     */
    private val minDwellMs: Long = 5 * 60_000L,
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<SystemState> = _state.asStateFlow()
    private var lastChangeMs = 0L

    fun consider(next: SystemState, nowMs: Long) {
        if (next != _state.value && nowMs - lastChangeMs >= minDwellMs) {
            _state.value = next
            lastChangeMs = nowMs
        }
    }
}
