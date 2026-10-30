package local.phantom.companion.core.ai.context

/**
 * MAP-AI-2 — pure-Kotlin contract for "which geofences are currently
 * armed?" injected into the AI's enrichedContext block as
 * `[GEOFENCES: count=N inside=M]` (or `[GEOFENCES:none]`).
 *
 * MAP-AI-6 lands the actual `:core-geofence` module that persists
 * registrations through WorkManager polling. Until then this stub
 * returns an empty snapshot so the prompt block remains deterministic
 * and the wiring path is in place for the future drop-in.
 *
 * Per Tier-29 constitutional axiom: only counts surface in the prompt —
 * raw polygon / address / Matter-scene names are vault-only and must
 * be unlocked through an explicit tool call before the AI sees them.
 */
fun interface GeofenceContextProvider {
    /** @return summary view of every armed geofence registration. */
    fun current(): List<GeofenceSummary>
}

/**
 * View-model snapshot of one armed geofence. Only `inside` is exposed
 * to the AI — pin coords / Matter-action names stay vault-side.
 */
data class GeofenceSummary(
    val inside: Boolean,
)

/**
 * Formats geofence summaries for the AI prompt preamble.
 *
 * - Empty list → `[GEOFENCES:none]`.
 * - Otherwise → `[GEOFENCES: count=N inside=M]` where M = count of
 *   entries with `inside = true`.
 */
object GeofenceContextFormatter {
    fun format(geofences: List<GeofenceSummary>): String {
        if (geofences.isEmpty()) return "[GEOFENCES:none]"
        val inside = geofences.count { it.inside }
        return "[GEOFENCES: count=${geofences.size} inside=$inside]"
    }
}

/**
 * Default stub returned by AppContainer until MAP-AI-6 lands the real
 * `:core-geofence` module. Always emits an empty snapshot so the
 * formatter degrades to `[GEOFENCES:none]`.
 */
object NoopGeofenceContextProvider : GeofenceContextProvider {
    override fun current(): List<GeofenceSummary> = emptyList()
}
