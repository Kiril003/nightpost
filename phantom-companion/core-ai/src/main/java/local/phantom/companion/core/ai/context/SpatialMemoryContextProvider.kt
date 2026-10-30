package local.phantom.companion.core.ai.context

import java.util.Locale

/**
 * MAP-AI-2 — pure-Kotlin contract for "are any spatial Memory-Palace
 * pins nearby?" injected into the AI's enrichedContext block as
 * `[SPATIAL_MEMORY: count=N closest=Xm]` (or `[SPATIAL_MEMORY:none]`).
 *
 * The signal is intentionally **count + closest-distance only** —
 * never the raw `MemoryEntity.title` or `id`. Chamber content stays in
 * the vault even when the AI is asked "what was here?"; the AI must
 * request explicit unlock through the existing question widget before
 * the bridge surfaces titles. Tier-29 constitutional axiom: spatial
 * memories are operator-confidential by default.
 *
 * Implementation lives in `:app` and reads from
 * `SpatialMemoryStore.nearest(chamber="earth", x=lat, y=lon, z=0, n=N)`
 * combined with `LocationContextProvider.current()`. This module stays
 * room/Android-free so the contract + formatter are unit-testable.
 */
fun interface SpatialMemoryContextProvider {
    /** @return summary of the N closest pins within the configured radius. */
    fun current(): List<NearbyMemorySummary>
}

/**
 * View-model snapshot of one nearby Memory-Palace pin. Only
 * `distanceMeters` is exposed; `id` is opaque (caller-managed) and
 * intentionally NOT included in the formatted block. The AI receives
 * count + closest-distance; full reveal needs a separate tool call
 * gated by operator consent.
 */
data class NearbyMemorySummary(
    val distanceMeters: Double,
)

/**
 * Formats nearby-memory summaries for the AI prompt preamble.
 *
 * - Empty list → `[SPATIAL_MEMORY:none]`.
 * - Otherwise → `[SPATIAL_MEMORY: count=N closest=Xm]` where X is the
 *   closest pin's metric distance, rounded to integer meters for
 *   distances < 1 km and to one decimal km for ≥ 1 km.
 *
 * The deliberate omission of titles is the **privacy contract** the AI
 * is trained on: spatial memories never leak through this preamble.
 */
object SpatialMemoryContextFormatter {
    fun format(nearby: List<NearbyMemorySummary>): String {
        if (nearby.isEmpty()) return "[SPATIAL_MEMORY:none]"
        val closest = nearby.minByOrNull { it.distanceMeters }?.distanceMeters ?: return "[SPATIAL_MEMORY:none]"
        val distLabel = if (closest < 1000) {
            String.format(Locale.US, "%dm", closest.toInt())
        } else {
            String.format(Locale.US, "%.1fkm", closest / 1000.0)
        }
        return "[SPATIAL_MEMORY: count=${nearby.size} closest=$distLabel]"
    }
}
