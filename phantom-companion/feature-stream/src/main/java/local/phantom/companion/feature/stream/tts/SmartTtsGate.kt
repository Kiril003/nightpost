package local.phantom.companion.feature.stream.tts

import android.app.NotificationManager
import android.content.Context

/**
 * Decides whether the assistant's `say` text actually reaches the
 * speaker. The gate respects the operator's DND/priority interruption
 * filter so phantom does not interrupt focus blocks or sleep.
 *
 * Phase 0.4 — minimal version. Tier 14 Embodiment can later extend
 * via [shouldSpeak] to factor headphones-connected, in-call,
 * proprioceptive 6-state FSM (e.g. GHOST = mute).
 *
 * Stateless; safe to invoke from any thread.
 */
object SmartTtsGate {

    /**
     * @return true iff [sayText] should actually be spoken now.
     *   - blank text          → false
     *   - DND total           → false
     *   - DND alarms only     → false
     *   - DND priority        → true only when [hasPriorityCue]
     *   - anything else       → true
     *
     * On older Android (< API 23) the interruption-filter API is
     * unavailable; we default to "speak" so we don't silently
     * regress legacy devices.
     */
    fun shouldSpeak(context: Context, sayText: String): Boolean {
        if (sayText.isBlank()) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return true
        return when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> false
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> false
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> hasPriorityCue(sayText)
            else -> true
        }
    }

    /**
     * Heuristic for "important enough to break DND-priority":
     * - short (< 80 chars) — likely a status snap (timer done, route ready)
     * - contains exclamation — explicit emphasis from the model
     *
     * Tier 28 (Diplomacy) can later replace this with a real
     * tone-classifier when we have one trained on operator preference.
     */
    private fun hasPriorityCue(text: String): Boolean =
        text.length < 80 || text.contains('!')
}
