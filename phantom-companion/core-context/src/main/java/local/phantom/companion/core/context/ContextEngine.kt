package local.phantom.companion.core.context

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.phantom.companion.core.context.decision.DecisionTree
import local.phantom.companion.core.context.nodes.AmbientAudioNode
import local.phantom.companion.core.context.nodes.MotionNode
import local.phantom.companion.core.context.nodes.TimeOfDayNode

class ContextEngine(
    private val tree: DecisionTree = DecisionTree(
        listOf(TimeOfDayNode(), MotionNode(), AmbientAudioNode())
    ),
    private val machine: StateMachine = StateMachine(),
    private val tickIntervalMs: Long = 30_000L,
    private val frameBuilder: ContextFrameBuilder? = null,
) {
    private val _snapshots = MutableSharedFlow<ContextSnapshot>(replay = 1, extraBufferCapacity = 8)
    val snapshots: SharedFlow<ContextSnapshot> = _snapshots.asSharedFlow()
    val state: StateFlow<SystemState> get() = machine.state

    val latestFrame: StateFlow<ContextFrame?>?
        get() = frameBuilder?.latestFrame

    var lastInput: ContextInput? = null
        private set

    private var job: Job? = null

    /** Starts the engine. [inputProvider] is called every tick to assemble the latest ContextInput. */
    fun start(scope: CoroutineScope, inputProvider: suspend () -> ContextInput) {
        frameBuilder?.startListening(scope)
        job = scope.launch {
            while (isActive) {
                val input = inputProvider()
                lastInput = input
                val decided = tree.decide(input, incumbent = machine.state.value)
                machine.consider(decided.state, input.nowMs)
                // Витримка машини мусить керувати тим, що бачать усі. Раніше
                // вниз ішов сирий переможець дерева, тож minDwellMs стримував
                // machine.state, а стрічка, пульс і кадр контексту читали стан,
                // який стрибав щотика.
                val snapshot = decided.copy(state = machine.state.value)
                _snapshots.tryEmit(snapshot)
                frameBuilder?.build(input, snapshot)
                delay(tickIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
