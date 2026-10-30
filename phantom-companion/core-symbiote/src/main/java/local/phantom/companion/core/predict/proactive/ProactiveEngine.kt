package local.phantom.companion.core.predict.proactive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Watches a frame stream and lets [reasoner] decide, unprompted, whether to speak.
 * The engine owns only battery hygiene (salience floor), pacing (min gap) and
 * anti-repetition — it NEVER decides the topic. The model is the sole judge.
 */
class ProactiveEngine(
    private val frames: Flow<ProactiveFrame>,
    private val reasoner: ProactiveReasoner,
    private val sink: UtteranceSink,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val minGapMs: Long = 20_000L,
    private val salienceFloor: Int = 15,
    private val recentWindow: Int = 8,
    private val recentDialogue: () -> String = { "" },
) {
    private val _recentlySaid = ArrayDeque<String>()
    val recentlySaid: List<String> get() = _recentlySaid.toList()

    private var lastDeliveredMs: Long? = null
    private var lastSeenSignals: Map<String, String>? = null

    fun start(scope: CoroutineScope): Job = scope.launch { run() }

    suspend fun run() = frames.collect { step(it) }

    private suspend fun step(frame: ProactiveFrame) {
        if (!worthAsking(frame)) return
        lastSeenSignals = frame.signals
        val utterance = reasoner.judge(frame, recentDialogue(), recentlySaid) ?: return
        if (isRepeat(utterance.say)) return
        sink.emit(utterance, frame)
        remember(utterance.say)
        lastDeliveredMs = clock()
    }

    // Cheap pre-gate: is it even worth waking the model? Never a topic filter.
    private fun worthAsking(frame: ProactiveFrame): Boolean {
        if (frame.salienceHint < salienceFloor) return false
        lastDeliveredMs?.let { if (clock() - it < minGapMs) return false }
        if (frame.signals == lastSeenSignals) return false
        return true
    }

    private fun isRepeat(say: String): Boolean {
        val norm = say.trim()
        return _recentlySaid.any { it.trim().equals(norm, ignoreCase = true) }
    }

    private fun remember(say: String) {
        if (_recentlySaid.size >= recentWindow) _recentlySaid.removeFirst()
        _recentlySaid.addLast(say)
    }
}
