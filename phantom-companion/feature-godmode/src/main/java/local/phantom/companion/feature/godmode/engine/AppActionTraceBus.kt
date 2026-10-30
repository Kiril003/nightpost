package local.phantom.companion.feature.godmode.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Phase 3 W4-4 — process-wide bus that carries [AppActionProgress]
 * events tagged with the originating widget id, so the
 * `AppActionWidget` Composable can subscribe to its own slice
 * without knowing about the dispatcher's internals.
 *
 * Replay buffer of 16 keeps the most recent traces around even when
 * the widget composes after the first few steps already fired (e.g.
 * after a thread switch + re-render). The bus is a singleton object
 * so the AccessibilityService can write into the same instance the
 * Stream UI reads from.
 */
data class AppActionTraceEvent(
    val widgetId: String,
    val progress: AppActionProgress,
    val emittedAtMs: Long,
)

object AppActionTraceBus {
    private val _events = MutableSharedFlow<AppActionTraceEvent>(
        replay = REPLAY_BUFFER,
        extraBufferCapacity = EXTRA_BUFFER,
    )

    val events: SharedFlow<AppActionTraceEvent> = _events.asSharedFlow()

    fun emit(widgetId: String, progress: AppActionProgress) {
        _events.tryEmit(
            AppActionTraceEvent(
                widgetId = widgetId,
                progress = progress,
                emittedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** Test-only — drop replay so a fresh test starts from a clean slate. */
    internal fun resetForTest() {
        _events.resetReplayCache()
    }

    private const val REPLAY_BUFFER: Int = 16
    private const val EXTRA_BUFFER: Int = 32
}
