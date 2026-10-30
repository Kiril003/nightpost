package local.phantom.companion.core.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import local.phantom.companion.core.tool.permission.SymbioteStop

/**
 * The board every long mission is pinned to, alive for the whole process.
 *
 * A research run measured in tens of minutes cannot live in a ViewModel: the
 * operator leaves the chat, the screen sleeps, and the scope that was doing
 * the work is gone with no trace of how far it got. The hub is the seam
 * between whoever ASKS for a mission (the chat) and whoever KEEPS IT ALIVE
 * (a foreground service) — neither has to know about the other.
 */
object AgentMissionHub {

    /** Longer than this and the mission runs in the service, not in the turn. */
    const val LONG_MISSION_MS = 120_000L

    enum class Phase { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    data class Mission(
        val id: String,
        val goal: String,
        val startedAtMs: Long,
        val budgetMs: Long,
        val maxSteps: Int,
        val step: Int = 0,
        val action: String = "",
        val note: String = "",
        val phase: Phase = Phase.QUEUED,
        val summary: String? = null,
    ) {
        fun elapsedMs(nowMs: Long): Long = (nowMs - startedAtMs).coerceAtLeast(0L)
        val active: Boolean get() = phase == Phase.QUEUED || phase == Phase.RUNNING
    }

    private val _missions = MutableStateFlow<List<Mission>>(emptyList())
    val missions: StateFlow<List<Mission>> = _missions.asStateFlow()

    private val _requests = MutableSharedFlow<AgentSpec>(replay = 8, extraBufferCapacity = 8)
    val requests: SharedFlow<AgentSpec> = _requests.asSharedFlow()

    private val _cancellations = MutableSharedFlow<String>(replay = 4, extraBufferCapacity = 8)
    val cancellations: SharedFlow<String> = _cancellations.asSharedFlow()

    /** Replayed: the chat may come back to the screen after the mission ended. */
    private val _results = MutableSharedFlow<AgentResult>(replay = 8, extraBufferCapacity = 8)
    val results: SharedFlow<AgentResult> = _results.asSharedFlow()

    val hasActive: Boolean get() = _missions.value.any { it.active }

    /** Ask for a mission. The host picks it up; the caller does not wait. */
    fun request(spec: AgentSpec, nowMs: Long = System.currentTimeMillis()) {
        if (SymbioteStop.stopped.value) {
            _missions.update { list ->
                list.filterNot { it.id == spec.agentId } + Mission(
                    id = spec.agentId,
                    goal = spec.goal,
                    startedAtMs = nowMs,
                    budgetMs = spec.budgetMs,
                    maxSteps = spec.maxSteps,
                    phase = Phase.CANCELLED,
                )
            }
            return
        }
        _missions.update { list ->
            list.filterNot { it.id == spec.agentId } + Mission(
                id = spec.agentId,
                goal = spec.goal,
                startedAtMs = nowMs,
                budgetMs = spec.budgetMs,
                maxSteps = spec.maxSteps,
            )
        }
        if (SymbioteStop.stopped.value) {
            cancel(spec.agentId)
            return
        }
        _requests.tryEmit(spec)
    }

    fun onProgress(progress: AgentProgress) = update(progress.agentId) {
        it.copy(
            phase = Phase.RUNNING,
            step = progress.stepIndex,
            action = progress.action,
            note = progress.detail.orEmpty(),
        )
    }

    fun onFinished(result: AgentResult) {
        _results.tryEmit(result)
        finish(result)
    }

    private fun finish(result: AgentResult) = update(result.agentId) {
        when (result) {
            is AgentResult.Done -> it.copy(phase = Phase.DONE, summary = result.summary)
            is AgentResult.Failed ->
                it.copy(phase = Phase.FAILED, summary = result.partialSummary, note = result.reason)
            is AgentResult.Cancelled -> it.copy(phase = Phase.CANCELLED)
        }
    }

    /** Operator pressed stop — on the card or on the notification. */
    fun cancel(id: String) {
        _cancellations.tryEmit(id)
        update(id) { it.copy(phase = Phase.CANCELLED) }
    }

    /** Drops finished missions; the active ones stay on the board. */
    fun clearFinished() = _missions.update { list -> list.filter { it.active } }

    fun find(id: String): Mission? = _missions.value.firstOrNull { it.id == id }

    private fun update(id: String, edit: (Mission) -> Mission) {
        _missions.update { list -> list.map { if (it.id == id) edit(it) else it } }
    }
}
