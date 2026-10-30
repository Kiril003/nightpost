package local.phantom.companion.core.agent

/**
 * Builds the SYSTEM prompt injected at the start of every agent conversation.
 *
 * Extracted as a separate object so [AgentSupervisor] remains thin and
 * [AgentSystemPromptBuilderTest] can verify the rendered content without
 * spinning up a full supervisor.
 *
 * The prompt is intentionally terse: the model must emit exactly one
 * top-level JSON object per turn — either a `tool-call` or `agent-done`.
 * Any other output is treated as a protocol error by the supervisor.
 *
 * @param spec        The [AgentSpec] driving this run.
 * @param toolsBlock  The `[TOOLS: …]` block produced by
 *                    [local.phantom.companion.core.tool.ToolPromptInjector.render].
 *                    Passed in as a string so this object has no dependency on
 *                    the Android-aware tool layer.
 */
object AgentSystemPromptBuilder {

    fun build(spec: AgentSpec, toolsBlock: String): String = """
        Ти sub-агент Nightpost. Goal: ${spec.goal}.
        Доступні tools (тільки ці): ${spec.allowedTools.joinToString(", ").ifEmpty { "(none)" }}.

        Випускай ПО ОДНОМУ tool-call за раз як {"type":"tool-call","tool":"<id>","args":{...}}.
        Коли goal досягнуто → {"type":"agent-done","summary":"<коротко>","artifacts":[...]}.
        Без сирого тексту, без markdown — тільки JSON-обʼєкт верхнього рівня.

        Жорсткі ліміти: maxSteps=${spec.maxSteps}, budgetMs=${spec.budgetMs}.

        $toolsBlock
    """.trimIndent()
}
