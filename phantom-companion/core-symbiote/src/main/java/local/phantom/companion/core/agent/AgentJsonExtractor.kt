package local.phantom.companion.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Thin JSON extractor used by [AgentSupervisor] to pull a top-level
 * `{"type":"…"}` object out of the model's raw text output.
 *
 * Deliberately kept inside `:core-agent` rather than reusing
 * `feature-stream`'s `AssistantJsonExtractor` because a dependency on
 * `:feature-stream` would create a module cycle:
 *
 *   `:core-agent` → `:feature-stream` → (indirectly) `:core-agent`
 *
 * Only the two transport-level steps are replicated here:
 *   1. Strip markdown fences (` ```json … ``` `).
 *   2. Find the first balanced `{…}` substring (brace-depth scanner that
 *      respects string literals so embedded `"{"` doesn't confuse it).
 *
 * The `<think>…</think>` stripping present in the feature-stream version is
 * intentionally omitted — that is a UI/display concern, not a protocol one.
 * If the model emits a think block the brace scanner will skip past it to the
 * first real `{` anyway.
 */
internal object AgentJsonExtractor {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Attempts to extract a `{"type":"…"}` [JsonObject] from [raw].
     *
     * Returns `null` when:
     * - No balanced `{…}` substring is found.
     * - The extracted string is not valid JSON.
     * - The parsed object has no top-level `"type"` string field.
     */
    fun extract(raw: String): JsonObject? {
        val candidate = findBalancedObject(stripFences(raw)) ?: return null
        val parsed = runCatching { json.parseToJsonElement(candidate) }
            .getOrNull() as? JsonObject ?: return null
        val type = parsed["type"] as? JsonPrimitive
        if (type == null || !type.isString || type.content.isBlank()) return null
        return parsed
    }

    private fun stripFences(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstNewline = trimmed.indexOf('\n')
        if (firstNewline < 0) return trimmed
        val withoutOpen = trimmed.substring(firstNewline + 1)
        val closeIndex = withoutOpen.lastIndexOf("```")
        return if (closeIndex < 0) withoutOpen.trim()
        else withoutOpen.substring(0, closeIndex).trim()
    }

    private fun findBalancedObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
