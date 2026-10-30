package local.phantom.companion.core.tool

/**
 * Renders the `[TOOLS: …]` block that is injected into the AI system
 * prompt so the model knows which tools are available and what arguments
 * each tool accepts.
 *
 * Output format (single line, no newlines):
 * ```
 * [TOOLS: shell(cmd,timeoutMs)—run a shell command; http(url,method)—make an HTTP request; ]
 * ```
 * If no tools are registered the block degrades to `[TOOLS: (none)]`.
 */
object ToolPromptInjector {

    /**
     * Produces a `[TOOLS: …]` prompt block for the given [tools] list.
     *
     * Argument names are extracted from [Tool.argsSchema] key order.
     * Description is [Tool.description].
     */
    fun render(tools: List<Tool>): String {
        if (tools.isEmpty()) return "[TOOLS: (none)]"

        val entries = tools.joinToString(separator = "; ") { tool ->
            val argNames = tool.argsSchema.keys.joinToString(",")
            "${tool.id}($argNames)—${tool.description}"
        }
        return "[TOOLS: $entries; ]"
    }
}
