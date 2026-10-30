package local.phantom.companion.core.data.errors

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * QH4 — central event bus for [PhantomError]s.
 *
 * Two surfaces:
 *  - [events] — hot SharedFlow for live consumers (e.g. StreamViewModel
 *    subscribes and renders Error/Critical as Notice items in the
 *    Stream the moment they happen).
 *  - [history] — last [maxHistory] entries kept in a StateFlow so
 *    Settings → Діагностика can render them on demand without
 *    needing to be subscribed at the moment the error fired.
 *
 * Newest entries are at the END of the history list. The buffer is
 * bounded so a runaway error loop can't OOM the app (we drop the
 * oldest entries silently — the operator only sees the last 50
 * anyway).
 */
class PhantomErrorBus(
    private val maxHistory: Int = 50,
) {
    private val _events = MutableSharedFlow<PhantomError>(
        replay = 0,
        extraBufferCapacity = 32,
    )
    val events: SharedFlow<PhantomError> = _events.asSharedFlow()

    private val _history = MutableStateFlow<List<PhantomError>>(emptyList())
    val history: StateFlow<List<PhantomError>> = _history.asStateFlow()

    /** Emit a fully-built error envelope. */
    fun emit(error: PhantomError) {
        _events.tryEmit(error)
        _history.update { existing ->
            val next = existing + error
            if (next.size > maxHistory) next.takeLast(maxHistory) else next
        }
    }

    /** Convenience: build + emit. Most call sites use this overload. */
    fun emit(
        source: String,
        severity: PhantomError.Severity,
        message: String,
        cause: Throwable? = null,
    ) {
        emit(PhantomError.create(source = source, severity = severity, message = message, cause = cause))
    }

    /** Test-helper / Settings «очистити журнал» action. */
    fun clearHistory() {
        _history.value = emptyList()
    }
}
