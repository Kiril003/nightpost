package local.phantom.companion.core.ai.context

import java.util.Locale

/**
 * MAP-AI-2 — pure-Kotlin contract for "which offline-tile regions has the
 * operator pre-downloaded?" injected into the AI's enrichedContext block
 * as `[OFFLINE_REGIONS: name@MB, name@MB, …]` (or `[OFFLINE_REGIONS:none]`).
 *
 * The AI uses this signal to decide whether suggesting an offline-only
 * action (route in airplane mode, hike planner) is feasible without
 * silently downloading hundreds of MB.
 *
 * Implementation lives in `:app` and reads from
 * `OfflineRegionsManager.regions.value`. This module stays MapLibre-free
 * so the contract + formatter can be unit-tested without Robolectric.
 */
fun interface OfflineRegionsContextProvider {
    /** @return summary view of every offline pack currently on disk. */
    fun current(): List<OfflineRegionSummary>
}

/**
 * View-model snapshot of a single offline pack. Kept minimal — only
 * fields the AI prompt actually needs. Name is operator-readable;
 * sizeBytes drives the on-disk MB number; ready signals whether the
 * pack has finished downloading (incomplete packs still surface so
 * the AI can mention "downloading in progress").
 */
data class OfflineRegionSummary(
    val name: String,
    val sizeBytes: Long,
    val ready: Boolean,
)

/**
 * Formats a region list for the AI prompt preamble.
 *
 * - Empty list → `[OFFLINE_REGIONS:none]`.
 * - Otherwise → `[OFFLINE_REGIONS: name@1.2MB, name@456kB]` (size compact;
 *   incomplete packs annotated with `*`: `name@2.1MB*`).
 *
 * Output is single-line and deterministic — entries follow the iteration
 * order of the input list, so callers control ordering (typically
 * `OfflineRegionsManager.regions.value`, sorted by last-modified).
 */
object OfflineRegionsContextFormatter {
    fun format(regions: List<OfflineRegionSummary>): String {
        if (regions.isEmpty()) return "[OFFLINE_REGIONS:none]"
        val entries = regions.joinToString(separator = ", ") { r ->
            val size = humaniseBytes(r.sizeBytes)
            val flag = if (!r.ready) "*" else ""
            "${r.name}@$size$flag"
        }
        return "[OFFLINE_REGIONS: $entries]"
    }

    private fun humaniseBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0fkB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2fGB", gb)
    }
}
