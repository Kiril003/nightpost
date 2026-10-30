package local.phantom.companion.core.ai.route

import local.phantom.companion.core.ai.SystemPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the arithmetic that keeps a local turn inside the KV cache.
 *
 * The number that matters: the full persona prompt does not fit the bundled
 * Qwen's 1280-token window and never will, so the route has to notice and
 * swap rather than hand MediaPipe a prompt ten times the size of its cache.
 */
class PromptBudgetTest {

    @Test
    fun `the shipped persona prompt does not fit the shipped model`() {
        val full = PromptBudget.estimateTokens(SystemPrompts.DEFAULT_PHANTOM)
        assertTrue(
            "DEFAULT_PHANTOM measured at $full tokens — if it ever shrinks below " +
                "the 1280-token window this test's premise is stale",
            full > 4 * 1280,
        )
        assertTrue(
            "the persona prompt must be recognised as an overflow at the 1280 window",
            PromptBudget.systemOverflows(SystemPrompts.DEFAULT_PHANTOM, PromptBudget.inputBudget(1280)),
        )
    }

    @Test
    fun `the compact prompt does fit`() {
        val budget = PromptBudget.inputBudget(1280)
        assertTrue(
            "LOCAL_COMPACT is ${PromptBudget.estimateTokens(SystemPrompts.LOCAL_COMPACT)} tokens, " +
                "allowance is ${PromptBudget.systemAllowance(budget)}",
            !PromptBudget.systemOverflows(SystemPrompts.LOCAL_COMPACT, budget),
        )
    }

    @Test
    fun `cyrillic is costed heavier than latin`() {
        val cyrillic = PromptBudget.estimateTokens("абвгдеєжзиійклмнопрстуфхцч")
        val latin = PromptBudget.estimateTokens("abcdefghijklmnopqrstuvwxyz")
        assertTrue("$cyrillic vs $latin", cyrillic > latin)
    }

    @Test
    fun `output reserve is always held back`() {
        assertEquals(1280 - PromptBudget.OUTPUT_RESERVE, PromptBudget.inputBudget(1280))
        assertTrue("a tiny window still leaves room to speak", PromptBudget.inputBudget(256) >= 128)
    }

    @Test
    fun `fitTail keeps the newest blocks and drops the oldest`() {
        val blocks = listOf("oldest", "middle", "newest").map { it.repeat(20) }
        val budget = PromptBudget.estimateTokens(blocks[1]) + PromptBudget.estimateTokens(blocks[2])

        val kept = PromptBudget.fitTail(blocks, budget)

        assertEquals(listOf(blocks[1], blocks[2]), kept)
    }

    @Test
    fun `fitTail returns nothing rather than overflowing`() {
        val one = "x".repeat(4000)
        assertTrue(PromptBudget.fitTail(listOf(one), 10).isEmpty())
    }

    @Test
    fun `clip lands under the budget and keeps the head`() {
        val text = "слово ".repeat(2000)
        val clipped = PromptBudget.clip(text, 50)

        assertTrue(PromptBudget.estimateTokens(clipped) <= 50)
        assertTrue(clipped.isNotEmpty())
        assertTrue(text.startsWith(clipped))
    }

    @Test
    fun `clip leaves a short text alone`() {
        assertEquals("коротко", PromptBudget.clip("коротко", 50))
    }
}
