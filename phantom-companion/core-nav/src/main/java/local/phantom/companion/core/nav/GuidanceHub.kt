package local.phantom.companion.core.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-global guidance state, mirroring [VoicePhaseHub]'s reason for
 * existing: the Android Auto head runs with no Activity of ours alive, so it
 * cannot read a ViewModel-owned [GuidanceController]. Whoever owns the drive
 * publishes here; the car head and any other Activity-less consumer read it.
 *
 * Mirror, not source of truth — the controller still owns the session.
 */
object GuidanceHub {

    private val _state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    /**
     * The route being driven, each point carrying the register its road was
     * drawn with (map-register-schema.md §1). The car head draws its own
     * map from this: a NavigationTemplate with no surface behind it is a
     * turn banner floating over nothing, which is not navigation — and a
     * line that never varies its own confidence lies by omission about the
     * ~85% of the country's roads nothing has ever surveyed.
     */
    private val _routeLine = MutableStateFlow<List<RoutePoint>>(emptyList())
    val routeLine: StateFlow<List<RoutePoint>> = _routeLine.asStateFlow()

    private val _destination = MutableStateFlow<String?>(null)

    /** Human name of where the drive is headed, when one is known. */
    val destination: StateFlow<String?> = _destination.asStateFlow()

    private val _cues = kotlinx.coroutines.flow.MutableSharedFlow<NavCue>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /**
     * Turn cues, for a speaker that outlives the screen. They used to be
     * consumed only in the Activity-scoped host, so guidance went silent the
     * moment the phone was pocketed or the car took over the display —
     * `AppContainer.navSpeaker` now collects this process-wide and is armed
     * before any car host can connect (`PhantomApp.onCreate`), so a cue
     * emitted here reaches the car whether or not our Activity ever ran.
     */
    val cues: kotlinx.coroutines.flow.SharedFlow<NavCue> =
        _cues.asSharedFlow()

    fun emitCue(cue: NavCue) {
        _cues.tryEmit(cue)
    }

    fun publish(state: GuidanceState) {
        _state.value = state
    }

    fun setRoute(points: List<RoutePoint>) {
        _routeLine.value = points
    }

    fun clear() {
        _state.value = GuidanceState.Idle
        _destination.value = null
        _routeLine.value = emptyList()
    }
}
