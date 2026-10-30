package local.phantom.companion.feature.godmode.engine

/**
 * Phase 3 W4-2 — structured snapshot of what is currently on the
 * operator's screen. Built by [ScreenContextExtractor] from the
 * AccessibilityService node tree, cached in [ScreenContextStore], and
 * read by `StreamViewModel.enrichedContext` as the `[SCREEN:...]`
 * preamble alongside `[LOCATION:...]`.
 *
 * The data class is pure-Kotlin so the extractor + formatter stay
 * unit-testable without Robolectric — the AccessibilityNodeInfo →
 * [ScreenContext] walk is the only Android-touching code (lives in
 * `ScreenAwarenessService`).
 *
 * @param packageName  active app's package, e.g. `"com.android.chrome"`
 * @param appLabel     friendly label resolved via `PackageManager`,
 *                     null when resolution failed (uninstalled racing
 *                     window, restricted profile, etc.)
 * @param rootClass    root view's class name, null when the service
 *                     has not yet captured a root
 * @param visibleText  top-N node texts ordered by descending screen
 *                     area (extractor de-duplicates and trims)
 * @param capturedAtMs epoch-ms set by extractor at build time; used
 *                     by [ScreenContextStore.current] to drop stale
 *                     snapshots
 */
data class ScreenContext(
    val packageName: String,
    val appLabel: String?,
    val rootClass: String?,
    val visibleText: List<String>,
    val capturedAtMs: Long,
)

/**
 * Pure intermediate produced by the AccessibilityService walk for
 * each node carrying user-visible text. [areaPx] is `width × height`
 * in pixels — bigger nodes win when ranking which texts make it into
 * [ScreenContext.visibleText].
 */
data class ScreenNode(
    val text: String,
    val areaPx: Long,
)

/**
 * Pure builder that turns a list of [ScreenNode]s into a
 * [ScreenContext]. The extractor:
 *
 *  1. drops nodes with blank text (after trim);
 *  2. de-duplicates exact text matches keeping the largest area copy;
 *  3. orders by descending area;
 *  4. takes the top [topN] entries (default 8 — enough to describe a
 *     typical scroll-page header + first-row article without dragging
 *     too much noise into the prompt).
 *
 * Designed to run on the AccessibilityService's debounced cadence
 * (W4-2 uses 500 ms) so the cost of the sort is bounded by however
 * many nodes the service actually sees per tick.
 */
object ScreenContextExtractor {
    const val DEFAULT_TOP_N: Int = 8

    fun build(
        packageName: String,
        appLabel: String?,
        rootClass: String?,
        nodes: List<ScreenNode>,
        nowMs: Long,
        topN: Int = DEFAULT_TOP_N,
    ): ScreenContext {
        val cap = topN.coerceAtLeast(0)
        val texts = if (cap == 0) emptyList() else nodes
            .asSequence()
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotBlank() }
            .groupingBy { it.text }
            .reduce { _, acc, n -> if (n.areaPx > acc.areaPx) n else acc }
            .values
            .sortedByDescending { it.areaPx }
            .take(cap)
            .map { it.text }
            .toList()
        return ScreenContext(
            packageName = packageName,
            appLabel = appLabel,
            rootClass = rootClass,
            visibleText = texts,
            capturedAtMs = nowMs,
        )
    }
}

/**
 * Formats a [ScreenContext] as the `[SCREEN: ...]` block prepended to
 * the AI's enrichedContext alongside `[LOCATION:...]`. Output shape:
 *
 *   `[SCREEN: app=Chrome, top-text="Buy tickets · Eurostar London"]`
 *
 * Texts are joined with ` · ` (mid-dot used as a soft separator the
 * model tokenises distinctly), each clipped to 60 chars, the joined
 * string clipped to [maxTextChars] so a verbose page doesn't blow up
 * the prompt budget. `app` falls back to the package name when
 * `PackageManager` could not resolve a friendly name.
 *
 * Returns an empty string for `null` input so callers can drop the
 * line cleanly without an extra null-check.
 */
object ScreenContextFormatter {
    private const val PER_TEXT_CLIP: Int = 60
    private const val DEFAULT_MAX_CHARS: Int = 240

    fun format(ctx: ScreenContext?, maxTextChars: Int = DEFAULT_MAX_CHARS): String {
        if (ctx == null) return ""
        val app = ctx.appLabel?.takeIf { it.isNotBlank() } ?: ctx.packageName
        val joined = ctx.visibleText
            .joinToString(separator = " · ") { it.take(PER_TEXT_CLIP) }
            .take(maxTextChars.coerceAtLeast(0))
        return if (joined.isBlank()) {
            "[SCREEN: app=$app]"
        } else {
            "[SCREEN: app=$app, top-text=\"$joined\"]"
        }
    }
}
