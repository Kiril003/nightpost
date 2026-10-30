package local.phantom.companion.feature.godmode.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 3 W4-3 — pure-Kotlin snapshot of the operator's most recent
 * text selection captured by the AccessibilityService.
 *
 * @param text          the selected substring, already trimmed
 * @param sourcePackage the app the selection came from
 * @param capturedAtMs  epoch-ms — the router uses this to drop stale
 *                      captures and the QuickAskBubble auto-dismisses
 *                      ~3 s after capture
 */
data class SelectedText(
    val text: String,
    val sourcePackage: String,
    val capturedAtMs: Long,
)

/**
 * Phase 3 W4-3 — process-wide bus for selected-text gestures captured
 * by `ScreenAwarenessService`. The service writes via [update] when
 * `TYPE_VIEW_TEXT_SELECTION_CHANGED` (or a long-click on a text node)
 * resolves to a non-blank selection. The Stream UI reads via [current]
 * to inject `[ОПЕРАТОР ВИБРАВ:"…"]` into the next AI prompt and to
 * decide when to surface the QuickAskBubble chip.
 *
 * Why an `object` (singleton) instead of DI: the AccessibilityService
 * is instantiated by Android, not under our control, so it has to
 * publish into a process-global. Mirrors the [ScreenContextStore] /
 * [GodModeEventBus] pattern.
 *
 * The [staleAfterMs] gate makes [current] return `null` when the
 * cached snapshot is older than 3 minutes by default — long enough
 * for the operator to switch back to Phantom and tap the chip, short
 * enough that an idle phone doesn't ship a yesterday's selection
 * into a new conversation. Tests reset state via [resetForTest].
 */
object SelectedTextRouter {
    @Volatile
    var staleAfterMs: Long = DEFAULT_STALE_AFTER_MS

    @Volatile
    var clockMs: () -> Long = System::currentTimeMillis

    private val _flow = MutableStateFlow<SelectedText?>(null)

    /** Latest non-null selection regardless of staleness. */
    val flow: StateFlow<SelectedText?> = _flow.asStateFlow()

    /**
     * Push a freshly-captured [SelectedText]. Last-writer wins.
     * Blank text is rejected so the service's caller stays naive.
     */
    fun update(selected: SelectedText) {
        if (selected.text.isBlank()) return
        _flow.value = selected.copy(text = selected.text.trim())
    }

    /** Drop the cached selection (e.g. after operator consumes it). */
    fun clear() {
        _flow.value = null
    }

    /**
     * Latest non-stale selection. Returns `null` when nothing has
     * been captured or when the cached snapshot is older than
     * [staleAfterMs]. Synchronous so the AI prompt enrichment path
     * can call it without suspending.
     */
    fun current(): SelectedText? {
        val sel = _flow.value ?: return null
        return if (clockMs() - sel.capturedAtMs > staleAfterMs) null else sel
    }

    /** Test-only — full reset to the same state as a fresh process. */
    internal fun resetForTest() {
        _flow.value = null
        staleAfterMs = DEFAULT_STALE_AFTER_MS
        clockMs = System::currentTimeMillis
    }

    /**
     * Formats a [SelectedText] as the `[ОПЕРАТОР ВИБРАВ:"…"]` line
     * prepended to the AI prompt context alongside `[LOCATION:…]`
     * and `[SCREEN:…]`. The text is clipped to [maxChars] so a
     * pasted-in document doesn't blow up the prompt budget; the
     * string is double-quoted so commas / colons inside the
     * selection don't tokenise as bracket separators for the model.
     *
     * Returns an empty string for `null` input so callers can drop
     * the line cleanly without an extra null-check.
     */
    fun formatForPrompt(selected: SelectedText?, maxChars: Int = DEFAULT_MAX_CHARS): String {
        if (selected == null) return ""
        val clipped = selected.text.take(maxChars.coerceAtLeast(0))
        if (clipped.isBlank()) return ""
        return "[ОПЕРАТОР ВИБРАВ:\"$clipped\"]"
    }

    private const val DEFAULT_STALE_AFTER_MS: Long = 3L * 60L * 1000L
    private const val DEFAULT_MAX_CHARS: Int = 600
}
