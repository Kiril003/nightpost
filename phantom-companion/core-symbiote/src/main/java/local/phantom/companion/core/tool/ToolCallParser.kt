package local.phantom.companion.core.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses a [JsonObject] emitted by the AI into a [ParsedToolCall].
 *
 * The AI is expected to emit JSON of the shape:
 * ```json
 * {"type":"tool-call","tool":"shell","args":{"cmd":"ls"},"stream":true,"say":"checking"}
 * ```
 *
 * Returns `null` if:
 * - `type` field is missing or not `"tool-call"`.
 * - `tool` field is missing.
 *
 * Optional fields (`stream`, `say`, `agentId`) fall back to safe defaults
 * (`false`, `null`, `null`) when absent.
 */
object ToolCallParser {

    /**
     * Attempts to parse [parsed] as a tool-call instruction.
     *
     * @param parsed A [JsonObject] already extracted from the AI response.
     * @return [ParsedToolCall] on success, `null` if the object is not a
     *         valid tool-call.
     */
    fun tryParse(parsed: JsonObject): ParsedToolCall? {
        val type = parsed["type"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?: return null

        if (type != "tool-call") return null

        // Trimmed: the model pads the id often enough that a single leading
        // space cost a whole turn — «unknown tool:  reminder.list», seen on
        // device 2026-07-26. Registry lookup is exact, so it must be clean.
        val toolId = parsed["tool"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val args = parsed["args"]
            ?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())

        val stream = parsed["stream"]
            ?.jsonPrimitive
            ?.booleanOrNull
            ?: false

        val say = parsed["say"]
            ?.jsonPrimitive
            ?.contentOrNull

        val agentId = parsed["agentId"]
            ?.jsonPrimitive
            ?.contentOrNull

        return ParsedToolCall(
            toolId = toolId,
            args = args,
            stream = stream,
            say = say,
            agentId = agentId,
        )
    }
}
