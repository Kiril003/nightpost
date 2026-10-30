package local.phantom.companion.core.ai.route

/**
 * Keeps a local prompt inside the model's KV cache.
 *
 * MediaPipe's `setMaxTokens` is the WHOLE window — input plus output share it,
 * there is no separate output cap. So whatever the route hands
 * `generateResponseAsync` has to leave room for the answer, or the answer is
 * whatever fits in the remainder. Nothing enforced that before: the route sent
 * the full system prompt (33k characters, roughly 13k tokens) at a window of
 * 1024, which is an order of magnitude over.
 */
object PromptBudget {

    /** Tokens held back for the reply. A turn that cannot answer in this many
     *  is not a turn a 0.5B model was going to finish coherently anyway. */
    const val OUTPUT_RESERVE: Int = 256

    /** Share of the input budget the system block may take before it gets
     *  swapped for the compact one — the rest belongs to the operator. */
    private const val SYSTEM_SHARE = 0.45

    /**
     * Characters per token, per script. Deliberately pessimistic: MediaPipe
     * gives us no tokenizer, and under-counting silently truncates the turn
     * while over-counting only costs a little unused window.
     */
    private const val CYRILLIC_CHARS_PER_TOKEN = 2.0
    private const val LATIN_CHARS_PER_TOKEN = 3.0

    fun estimateTokens(text: String): Int {
        var cyrillic = 0
        for (c in text) if (c in 'Ѐ'..'ӿ') cyrillic++
        val other = text.length - cyrillic
        return Math.ceil(cyrillic / CYRILLIC_CHARS_PER_TOKEN + other / LATIN_CHARS_PER_TOKEN).toInt()
    }

    fun inputBudget(contextWindow: Int): Int = (contextWindow - OUTPUT_RESERVE).coerceAtLeast(128)

    fun systemAllowance(inputBudget: Int): Int = (inputBudget * SYSTEM_SHARE).toInt()

    /** True when [system] is too large to be worth keeping whole. */
    fun systemOverflows(system: String, inputBudget: Int): Boolean =
        estimateTokens(system) > systemAllowance(inputBudget)

    /**
     * Newest-first fill. Returns the tail of [blocks] that fits in [budget],
     * back in original order, so history is dropped from the far end where it
     * matters least.
     */
    fun fitTail(blocks: List<String>, budget: Int): List<String> {
        var left = budget
        val kept = ArrayDeque<String>()
        for (block in blocks.asReversed()) {
            val cost = estimateTokens(block)
            if (cost > left) break
            left -= cost
            kept.addFirst(block)
        }
        return kept.toList()
    }

    /** Clips [text] to roughly [budget] tokens, keeping the head. */
    fun clip(text: String, budget: Int): String {
        if (estimateTokens(text) <= budget) return text
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (estimateTokens(text.take(mid)) <= budget) lo = mid else hi = mid - 1
        }
        return text.take(lo)
    }
}
