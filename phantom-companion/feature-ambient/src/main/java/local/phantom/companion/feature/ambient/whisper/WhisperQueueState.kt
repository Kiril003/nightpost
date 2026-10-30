package local.phantom.companion.feature.ambient.whisper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 3-E3 — bounded FIFO holding whisper-line messages waiting to
 * drift past the operator. The Composable side reads [current] and
 * calls [advance] after one full ribbon cycle + the prompt's required
 * 2s breathing pause; the producer side (event bus subscriber) calls
 * [enqueue] every time a new agent.stream message arrives.
 *
 * State is [MutableStateFlow]-backed so the Composable can observe via
 * `collectAsState` without a re-keyed `remember` dance.
 *
 * Bound = [MAX_QUEUE_SIZE]. If the operator hasn't been looking at
 * the screen during a burst (10+ proactive cycles in a single
 * minute, e.g. a notification cluster), older items drop off the front.
 * The whisper line is a peripheral "what's happening NOW" channel —
 * stale messages have no value.
 */
class WhisperQueueState {

    private val pending: ArrayDeque<String> = ArrayDeque(MAX_QUEUE_SIZE)

    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()

    /** Operator-visible snapshot of the buffered messages (read-only). */
    @get:Synchronized
    val buffered: List<String>
        get() = pending.toList()

    /** Total count of items waiting + currently visible. */
    @get:Synchronized
    val size: Int
        get() = pending.size + (if (_current.value != null) 1 else 0)

    /**
     * Add a new message. Becomes [current] immediately if the ribbon
     * was idle; otherwise waits in [pending]. Blank messages are
     * silently dropped (defensive — the mapper already filters, this
     * is belt-and-braces).
     */
    @Synchronized
    fun enqueue(message: String) {
        if (message.isBlank()) return
        if (_current.value == null) {
            _current.value = message
            return
        }
        if (pending.size >= MAX_QUEUE_SIZE) {
            // Drop the oldest — newest in, oldest out.
            pending.removeFirst()
        }
        pending.addLast(message)
    }

    /**
     * Drop the currently-shown message and promote the next pending
     * one (or null when nothing is queued). Called by the Composable
     * after one full drift cycle + the 2s pause.
     */
    @Synchronized
    fun advance() {
        _current.value = pending.removeFirstOrNull()
    }

    /** Reset to the resting state. Used on service stop / restart. */
    @Synchronized
    fun clear() {
        pending.clear()
        _current.value = null
    }

    companion object {
        const val MAX_QUEUE_SIZE: Int = 16
    }
}
