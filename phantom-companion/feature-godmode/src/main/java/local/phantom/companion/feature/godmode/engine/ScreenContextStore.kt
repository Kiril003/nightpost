package local.phantom.companion.feature.godmode.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 3 W4-2 — process-wide cache of the latest [ScreenContext]
 * captured by `ScreenAwarenessService`. AppContainer reads from this
 * exact instance when wiring `StreamViewModel.screenContextProvider`;
 * the service writes via [update] on every debounced
 * AccessibilityEvent tick.
 *
 * Why an `object` (singleton) instead of a DI-injected class:
 * Android instantiates the AccessibilityService on its own — there is
 * no constructor we control — so the service has to reach for a
 * process-global to publish into. [ScreenContextStore] mirrors the
 * `GodModeEventBus` pattern for that reason.
 *
 * The [staleAfterMs] gate makes [current] return `null` when the
 * cached snapshot is older than 60 s by default (operator put the
 * phone down, screen went off, or the service got revoked). The AI
 * prompt enrichment path uses [current] so the model isn't told a
 * stale window is what the operator currently sees.
 *
 * Tests reset state via [resetForTest].
 */
object ScreenContextStore {
    @Volatile
    var staleAfterMs: Long = DEFAULT_STALE_AFTER_MS

    @Volatile
    var clockMs: () -> Long = System::currentTimeMillis

    private val _flow = MutableStateFlow<ScreenContext?>(null)

    /** Latest captured context (or `null` when nothing captured yet). */
    val flow: StateFlow<ScreenContext?> = _flow.asStateFlow()

    /** Push a freshly-built [ScreenContext]. Last-writer wins. */
    fun update(ctx: ScreenContext) {
        _flow.value = ctx
    }

    /** Drop the cached snapshot. */
    fun clear() {
        _flow.value = null
    }

    /**
     * Latest non-stale snapshot. Returns `null` when nothing has been
     * captured or when the cached snapshot is older than
     * [staleAfterMs]. Synchronous so the AI prompt enrichment path
     * can call it without suspending.
     */
    fun current(): ScreenContext? {
        val ctx = _flow.value ?: return null
        return if (clockMs() - ctx.capturedAtMs > staleAfterMs) null else ctx
    }

    /** Test-only — full reset to the same state as a fresh process. */
    internal fun resetForTest() {
        _flow.value = null
        staleAfterMs = DEFAULT_STALE_AFTER_MS
        clockMs = System::currentTimeMillis
    }

    private const val DEFAULT_STALE_AFTER_MS: Long = 60_000L
}
