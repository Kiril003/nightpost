package local.phantom.companion.core.ai.context

/**
 * Phase 4 — Phantom adaptive surface. Replaces the hard-coded
 * `AppActionAliases.KNOWN` map with a live list of the operator's
 * actual launchable apps. AI receives this in the system-prompt
 * context block and emits exact package names — no static alias
 * catalogue, no «is the label in my map» branch, just whatever the
 * operator has installed today.
 */
fun interface InstalledAppsProvider {
    fun current(): List<InstalledApp>
}

data class InstalledApp(
    val label: String,
    val packageName: String,
)

/**
 * Renders the operator's installed-apps list into a single `[APPS:]`
 * line for the prompt-enrichment chain. Empty list → null (so the
 * formatter never injects a noisy empty block).
 *
 * Format keeps each entry as `label=package`, separated by `; `.
 * The renderer caps at [maxEntries] to keep the prompt under 2-3 KB
 * even on a phone with 200 apps installed. The truncation is silent
 * — Gemini sees the most-relevant slice (caller's responsibility to
 * pre-rank).
 */
object InstalledAppsFormatter {
    const val DEFAULT_MAX_ENTRIES: Int = 50

    fun format(
        apps: List<InstalledApp>?,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ): String? {
        if (apps.isNullOrEmpty()) return null
        val capped = apps.take(maxEntries.coerceAtLeast(1))
        val body = capped.joinToString("; ") { "${it.label}=${it.packageName}" }
        return "[APPS: $body]"
    }
}
