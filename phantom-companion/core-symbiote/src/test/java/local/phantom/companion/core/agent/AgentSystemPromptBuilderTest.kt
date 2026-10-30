package local.phantom.companion.core.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSystemPromptBuilderTest {

    private fun buildSpec(
        goal: String = "list files",
        allowedTools: Set<String> = setOf("echo", "shell"),
        maxSteps: Int = 4,
        budgetMs: Long = 30_000L,
    ) = AgentSpec(
        goal = goal,
        allowedTools = allowedTools,
        maxSteps = maxSteps,
        budgetMs = budgetMs,
    )

    @Test
    fun `rendered prompt contains the goal`() {
        val spec = buildSpec(goal = "summarise the inbox")
        val prompt = AgentSystemPromptBuilder.build(spec, "[TOOLS: echo()—echo; ]")
        assertTrue("Prompt should contain the goal", prompt.contains("summarise the inbox"))
    }

    @Test
    fun `rendered prompt contains all allowed tool IDs`() {
        val spec = buildSpec(allowedTools = setOf("echo", "http"))
        val prompt = AgentSystemPromptBuilder.build(spec, "[TOOLS: echo()—echo; http(url)—http; ]")
        assertTrue("Prompt should list 'echo'", prompt.contains("echo"))
        assertTrue("Prompt should list 'http'", prompt.contains("http"))
    }

    @Test
    fun `rendered prompt contains the tools block verbatim`() {
        val toolsBlock = "[TOOLS: shell(cmd)—run shell; ]"
        val spec = buildSpec()
        val prompt = AgentSystemPromptBuilder.build(spec, toolsBlock)
        assertTrue("Prompt should include the toolsBlock", prompt.contains(toolsBlock))
    }

    @Test
    fun `rendered prompt enforces JSON-only output rule`() {
        val spec = buildSpec()
        val prompt = AgentSystemPromptBuilder.build(spec, "[TOOLS: (none)]")
        assertTrue("Prompt should mention tool-call JSON shape",
            prompt.contains("tool-call"))
        assertTrue("Prompt should mention agent-done JSON shape",
            prompt.contains("agent-done"))
        // The rule says no raw text / markdown — verify the key constraint phrase.
        assertTrue("Prompt should forbid markdown",
            prompt.contains("markdown") || prompt.contains("JSON"))
    }

    @Test
    fun `rendered prompt contains maxSteps and budgetMs limits`() {
        val spec = buildSpec(maxSteps = 7, budgetMs = 45_000L)
        val prompt = AgentSystemPromptBuilder.build(spec, "[TOOLS: (none)]")
        assertTrue("Prompt should contain maxSteps value", prompt.contains("7"))
        assertTrue("Prompt should contain budgetMs value", prompt.contains("45000"))
    }

    @Test
    fun `empty allowedTools renders as (none)`() {
        val spec = buildSpec(allowedTools = emptySet())
        val prompt = AgentSystemPromptBuilder.build(spec, "[TOOLS: (none)]")
        assertTrue("Prompt should say (none) for empty tool set", prompt.contains("(none)"))
    }
}
