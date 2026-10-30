package local.phantom.companion.core.ai

/**
 * Operator's intent expressed to PHANTOM. The router parses it and
 * routes to a local LLM (Gemma) or cloud LLM (Gemini) based on the
 * [RoutingStrategy] and runtime conditions (network, battery, GHOST state).
 *
 * Sealed surface so future intent types (image gen, audio synthesis,
 * embedding, function-call) extend cleanly without breaking the
 * single-method [AIRouter.complete] contract.
 */
sealed interface AIIntent {
    /** Vanilla text → text. The most common path. */
    data class Text(
        val prompt: String,
        val systemPrompt: String? = null,
        val history: List<Turn> = emptyList(),
        val context: String? = null,
        val maxTokens: Int = 1024,
        val temperature: Float = 0.7f,
        val tools: List<ToolDefinition> = emptyList(),
        /**
         * R1.3 — when true the cloud route attaches Google-Search
         * grounding (`google_search` tool) alongside the function
         * declarations, so the model can answer from live web data and
         * return citations. Local routes ignore the flag.
         *
         * Defaults to FALSE (opt-in): grounding has a SEPARATE, much
         * smaller free-tier daily quota than the base model, and forcing
         * it on every turn exhausted that grounding quota — Google then
         * returned 429 RESOURCE_EXHAUSTED on EVERY request, so the app
         * went silent on all input while the base model was still fine.
         * Verified live 2026-07-03: identical request with `google_search`
         * → 429, without it → 200. Turn this on per-turn only when the
         * operator actually needs live web data.
         */
        val webSearch: Boolean = false,
        /**
         * R2.3 — multimodal parts attached to THIS user turn. The cloud
         * route serializes each as `inline_data` (base64 + mime) ahead of
         * the prompt text; local routes ignore them.
         */
        val attachments: List<InlineAttachment> = emptyList(),
        /**
         * Розмова власника з його PHANTOM: [route.BrainChain] спершу питає памʼять
         * ([route.MemoryGround]) — без опори на питання про власника відповідь «не знаю»
         * без моделі, з опорою — блок памʼяті з мітками [m:id] у системному промпті.
         * Разові службові запити (переклад, підсумок, витяг фактів) — false.
         */
        val grounded: Boolean = false,
        /**
         * Мова відповіді одним реченням ([SystemPrompts.languageRule]). Хмарний маршрут
         * ставить його ОСТАННІМ у системному приписі — після блоку памʼяті, правил
         * інструментів і контексту, які дописуються вже по дорозі. Усі вони
         * українською, і правило посеред них програвало б їм.
         */
        val languageRule: String? = null,
        val profile: BrainProfile? = null,
    ) : AIIntent {
        data class Turn(
            val role: Role,
            val content: String,
            val toolCallId: String? = null, // Used for Role.TOOL to link back to call
            val toolCalls: List<ToolCall>? = null // Used for Role.ASSISTANT to record model's calls
        )
        enum class Role { USER, ASSISTANT, SYSTEM, TOOL }

        data class ToolCall(
            val id: String,
            val name: String,
            val arguments: String
        )
    }
}

/** R2.3 — one base64-encoded multimodal part (image / pdf / audio). */
data class InlineAttachment(
    val mimeType: String,
    val base64Data: String,
)

/** Definition of a tool the AI can invoke. Matches Gemini/OpenAI function schemas. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersSchema: kotlinx.serialization.json.JsonObject // JSON Schema of parameters
)

/**
 * Returns a copy of this intent with a synthetic ASSISTANT turn prepended
 * to the history carrying [prefix] as the accumulated partial response.
 * Used by [local.phantom.companion.core.resilience.StreamResumer] so the
 * model "continues" the response rather than starting from scratch.
 */
fun AIIntent.withResumePrefix(prefix: String): AIIntent.Text {
    val asText = this as? AIIntent.Text ?: return AIIntent.Text(
        prompt = this.toString(),
    )
    val resumeTurn = AIIntent.Text.Turn(
        role = AIIntent.Text.Role.ASSISTANT,
        content = "[RESUME-PREFIX] $prefix",
    )
    return asText.copy(history = asText.history + resumeTurn)
}
