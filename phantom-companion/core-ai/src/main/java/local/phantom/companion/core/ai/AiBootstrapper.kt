package local.phantom.companion.core.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * How much of a brain PHANTOM currently has. The Dialogue reads this and
 * nothing else — it never asks the operator to inspect routes, keys or model
 * files, because none of that is his job.
 */
sealed interface BrainState {

    /** A route can answer right now (cloud key, or a local model on disk). */
    data object Ready : BrainState

    /**
     * No route yet, and PHANTOM is fetching one. [progress] is 0..1 over the
     * micro-model transfer. Anything the operator says while this runs is
     * held, not lost — see [AiBootstrapper.ensureBrain].
     *
     * [patience] is how long the fetch has already been running, said in
     * words. A transfer can stop dead without failing — the network dies
     * with no exception, or a mirror hands over a byte a minute — and then
     * [progress] is a true number that never moves again. Freezing on it is
     * the same silence the unwired stall was made of, only with no event
     * coming to end it. The wake never gives up and never fakes movement;
     * it changes what it says. Defaulted so every existing construction
     * site keeps compiling and keeps meaning what it meant.
     */
    data class Waking(
        val progress: Float,
        val patience: WakePatience = WakePatience.Fresh,
    ) : BrainState

    /**
     * No route, and PHANTOM cannot get one *yet* — he is offline. Not an
     * error and not a to-do list for the operator: the wake resumes by
     * itself the moment the network returns.
     */
    data object Offline : BrainState

    /**
     * Nobody has asked yet — nothing fetched, nothing promised.
     *
     * The cold-start sweep and the reconnect watcher both stop here. Mirrors
     * [HearingState.Idle], which is the barrier the ear has had since the
     * 05.09 privacy audit; the brain had none, and the only thing standing
     * between a fresh phone on Wi-Fi and 521 MB was a compile-time flag.
     */
    data object Idle : BrainState

    /**
     * The wake failed. This is the ONLY state that may ever surface a setup
     * hint, and even then it is phrased as PHANTOM's problem.
     *
     * [retryable] decides whether a «try again» is offered at all. A transfer
     * that died mid-flight earns another press. A stall that comes from this
     * build having no engine for the file does not: the press re-enters
     * [AiBootstrapper.ensureBrain], which re-reads the same compile-time
     * [ModelType.wired] and parks on this same state. That button was
     * pressable for ever and could never once change the answer.
     */
    data class Stalled(val reason: String, val retryable: Boolean = true) : BrainState
}

/**
 * How long a wake has been running, in the only unit that reaches the
 * operator: which sentence PHANTOM has earned the right to say.
 *
 * This is not a severity ladder and never becomes an error — a slow link is
 * not a fault, and the last rung still means «I am still pulling». Only the
 * words change; the transfer, the held messages and the polling do not.
 */
enum class WakePatience {
    /** Inside the time a healthy link genuinely needs. Nothing to remark on. */
    Fresh,

    /** Past it. Nothing is proven broken — it is simply slower than usual. */
    Stretched,

    /** Past what even a poor link needs. Something is wrong; say so plainly,
     *  and keep pulling anyway. */
    Strained,
}

/**
 * Чи є зараз чим думати: ПК на звʼязку або ключ хмари. Сам телефон моделі
 * більше не тягне, тож [BrainState.Waking] і [BrainState.Stalled] тут не
 * народжуються; без розуму стан [BrainState.Idle], а що робити, каже
 * відповідь BrainChain (AIError.NoBrain).
 */
class AiBootstrapper(
    private val router: AIRouter,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<BrainState>(BrainState.Idle)
    val state: StateFlow<BrainState> = _state.asStateFlow()

    fun recheck() {
        scope.launch { ensureBrainNow() }
    }

    @Suppress("UNUSED_PARAMETER")
    fun ensureBrain(urgent: Boolean = false) {
        scope.launch { ensureBrainNow() }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun ensureBrainNow(urgent: Boolean = false): BrainState {
        val routes = runCatching { router.availableRoutes() }.getOrDefault(emptySet())
        val next = if (routes.isNotEmpty()) BrainState.Ready else BrainState.Idle
        _state.value = next
        return next
    }

    fun observe(signal: Flow<*>) {
        scope.launch { signal.collect { ensureBrainNow() } }
    }
}
