package local.phantom.companion.feature.ambient.whisper

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Phase 3-E3 — singleton fan-out for whisper-bound events.
 *
 * Mirrors the [GodModeEventBus] pattern: one process-global
 * MutableSharedFlow that producers (PhantomApp's link-events bridge)
 * push to and consumers (WhisperLineService) collect from. The bus
 * lives in :feature-ambient because the only consumer is the
 * WhisperLineService that lives here, and :app already depends on
 * :feature-ambient (via the manifest merge), so the producer side
 * can reach it without a cyclic dependency.
 *
 * Replay = 0 — events are presence-channel pings, not durable state.
 * If the service starts after the link event fires, it just waits for
 * the next one. Buffer size 32 covers a brief burst of proactive cycles
 * without dropping messages on a slow consumer.
 */
object WhisperEventBus {
    private val _events = MutableSharedFlow<WhisperEvent>(
        replay = 0,
        extraBufferCapacity = 32,
    )

    val events: SharedFlow<WhisperEvent> = _events.asSharedFlow()

    /**
     * Push a wire event into the bus. Non-blocking — drops the event
     * silently if all 32 buffer slots are full and no consumer is
     * keeping up. tryEmit's return value is ignored on purpose: a
     * dropped whisper is a missed peripheral cue, never a correctness
     * bug.
     */
    fun push(event: WhisperEvent) {
        _events.tryEmit(event)
    }
}

/**
 * Minimal envelope the whisper line cares about. Translated from the
 * raw `agent.stream` wire frame at the producer side so the consumer
 * doesn't need a JSON dependency. `text` is the operator-readable
 * snippet when the server included one; `emotion` is the named
 * emotion when present (drives the «відчуття · X» ribbon line).
 *
 * `kind` is left as a free-form string rather than a sealed enum so a
 * new server-side event type doesn't crash the bridge — the mapper
 * will fall through to null and the event drops out of the queue.
 */
data class WhisperEvent(
    val kind: String,
    val text: String? = null,
    val emotion: String? = null,
)
