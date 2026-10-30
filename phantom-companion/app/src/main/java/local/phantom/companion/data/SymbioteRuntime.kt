package local.phantom.companion.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import local.phantom.companion.core.bioloop.UiTempo
import local.phantom.companion.core.context.SystemState
import local.phantom.companion.core.embodiment.HapticEmitter
import local.phantom.companion.core.embodiment.ProprioceptiveTicker

class SymbioteRuntime(
    private val hapticEnabled: StateFlow<Boolean>,
    private val hrvTempoEnabled: StateFlow<Boolean>,
    private val systemState: StateFlow<SystemState>,
    private val hapticEmitter: HapticEmitter,
) {
    constructor(
        prefs: SymbiotePreferences,
        systemState: StateFlow<SystemState>,
        hapticEmitter: HapticEmitter,
    ) : this(prefs.hapticEnabled, prefs.hrvTempoEnabled, systemState, hapticEmitter)

    private var tickerJob: Job? = null
    private var ticker: ProprioceptiveTicker? = null

    private val _tempoCycleSeconds = MutableStateFlow(UiTempo.STEADY.breathingCycleSeconds)
    val tempoCycleSeconds: StateFlow<Double> = _tempoCycleSeconds.asStateFlow()

    fun start(scope: CoroutineScope) {
        scope.launch {
            hapticEnabled.collectLatest { on ->
                if (on) startTicker(scope) else stopTicker()
            }
        }
    }

    fun applyHrv(bpm: Int) {
        if (!hrvTempoEnabled.value) return
        _tempoCycleSeconds.value = UiTempo.fromBpm(bpm).breathingCycleSeconds
    }

    private fun startTicker(scope: CoroutineScope) {
        if (tickerJob?.isActive == true) return
        val t = ProprioceptiveTicker(
            stateFlow = systemState,
            emitter = hapticEmitter,
        )
        ticker = t
        t.start(scope)
        tickerJob = scope.coroutineContext[Job]
    }

    private fun stopTicker() {
        ticker?.stop()
        ticker = null
        tickerJob = null
    }
}
