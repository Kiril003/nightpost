package local.phantom.companion.feature.ambient.whisper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 3-E4 — process-global publisher of the foreground-app
 * package name. Mirrors the [WhisperEventBus] pattern: producer
 * pushes (PhantomApp wires `GodModeEventBus.events.filter
 * Isinstance<ScreenChanged>` → push), consumer reads
 * ([WhisperLineService.RibbonScaffold]).
 *
 * Initial value `null` so the overlay defaults to «visible» until
 * the first ScreenChanged arrives. This matters at service start
 * before AccessibilityService has fired its first event.
 */
object ForegroundAppMonitor {
    private val _currentPackage = MutableStateFlow<String?>(null)
    val currentPackage: StateFlow<String?> = _currentPackage.asStateFlow()

    fun update(packageName: String?) {
        _currentPackage.value = packageName?.takeIf { it.isNotBlank() }
    }

    /** Reset for tests / service shutdown. */
    fun reset() {
        _currentPackage.value = null
    }
}
