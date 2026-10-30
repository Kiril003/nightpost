package local.phantom.companion.core.ai.context

/**
 * MAP-AI-2 — pure-Kotlin contract for "what is the operator's mood
 * arc right now?" injected into the AI's enrichedContext block as
 * `[MOOD:state=ELEVATED|BALANCED|LOW|CRITICAL]` (or `[MOOD:unknown]`).
 *
 * The AI uses the signal to gate suggestion-style behaviour:
 *
 *   - `CRITICAL` → constitutional refusal of optimisation pings,
 *     `propose_mood_route` MAY emit a calming walk loop (MAP-AI-10).
 *   - `LOW`      → softer tone, fewer follow-up questions, prefer
 *     `propose_mood_route` over generic search.
 *   - `BALANCED` → default behaviour.
 *   - `ELEVATED` → may include lighter, exploratory suggestions.
 *
 * Implementation lives in `:app` and reads from a `MoodLabel` snapshot
 * produced by `MoodArc.classify(MoodSample)` in `:core-health-mesh`.
 * This module stays free of `:core-health-mesh` so the contract +
 * formatter are unit-testable without that module on the classpath.
 *
 * The mood signal is sensitive — operator may disable it through the
 * Symbiote settings surface. When disabled, [current] returns `null`
 * and the formatter emits `[MOOD:unknown]` so the prompt block
 * remains deterministic.
 */
fun interface MoodContextProvider {
    /**
     * @return current mood band name (matching `MoodLabel.name` from
     *   `:core-health-mesh`) or `null` when no recent sample exists
     *   or the operator has opted out.
     */
    fun current(): String?
}

/**
 * Formats a mood band for the AI prompt preamble.
 *
 * - `null` → `[MOOD:unknown]`.
 * - Non-null → `[MOOD:state=<band>]` — the band string is forwarded
 *   verbatim from the source enum to avoid drift if `:core-health-mesh`
 *   introduces a new label that `:core-ai` has not learned about yet.
 */
object MoodContextFormatter {
    fun format(state: String?): String =
        if (state == null) "[MOOD:unknown]"
        else "[MOOD:state=$state]"
}
