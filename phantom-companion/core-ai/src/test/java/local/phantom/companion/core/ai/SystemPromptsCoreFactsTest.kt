package local.phantom.companion.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptsCoreFactsTest {

    @Test
    fun `empty facts returns base prompt unchanged`() {
        val out = SystemPrompts.withCoreFacts(facts = emptyList())
        assertEquals(SystemPrompts.DEFAULT_PHANTOM, out)
    }

    @Test
    fun `blank-only facts are ignored`() {
        val out = SystemPrompts.withCoreFacts(facts = listOf("", "   ", "\n"))
        assertEquals(SystemPrompts.DEFAULT_PHANTOM, out)
    }

    @Test
    fun `facts get prepended as a bulleted CORE FACTS block`() {
        val out = SystemPrompts.withCoreFacts(facts = listOf("Кирилу 30", "Працює над PHANTOM"))
        assertTrue("CORE FACTS marker", out.contains("[CORE FACTS"))
        assertTrue("first fact bullet", out.contains("- Кирилу 30"))
        assertTrue("second fact bullet", out.contains("- Працює над PHANTOM"))
        assertTrue("base prompt still present", out.contains(SystemPrompts.DEFAULT_PHANTOM))
        assertTrue("facts block comes first", out.indexOf("CORE FACTS") < out.indexOf("Ти — Nightpost"))
    }

    @Test
    fun `custom base prompt is honoured`() {
        val custom = "SYSTEM: only this."
        val out = SystemPrompts.withCoreFacts(prompt = custom, facts = listOf("a"))
        assertTrue(out.contains("- a"))
        assertTrue(out.endsWith(custom))
    }
}
