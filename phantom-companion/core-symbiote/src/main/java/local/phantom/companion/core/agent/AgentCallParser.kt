package local.phantom.companion.core.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Parses AI-emitted JSON into [ParsedAgentCall] variants.
 *
 * Two top-level shapes are recognised:
 *
 * **Single agent** (`"type":"agent"`):
 * ```json
 * {"type":"agent","say":"...","payload":{
 *   "goal":"...","plan":["..."],"tools":["http","file"],
 *   "maxSteps":6,"budgetMs":45000,"preferLocal":false,"agentId":"optional"
 * }}
 * ```
 *
 * **Parallel agents** (`"type":"agent-parallel"`):
 * ```json
 * {"type":"agent-parallel","say":"...","agents":[
 *   {"agentId":"a1","goal":"...","tools":["http"]},
 *   {"agentId":"a2","goal":"...","tools":["calendar"]}
 * ]}
 * ```
 *
 * Hard caps applied during parsing:
 * - [maxSteps] clamped to `[1, MAX_STEPS_HARD_CAP]` (default 12)
 * - [budgetMs] clamped to `[1_000, MAX_BUDGET_HARD_CAP_MS]` (default 180 000);
 *   past [AgentMissionHub.LONG_MISSION_MS] the run leaves the chat coroutine
 * - Parallel array truncated to [MAX_PARALLEL_HARD_CAP] entries (default 3)
 */
sealed interface ParsedAgentCall {
    val say: String?

    data class Single(override val say: String?, val spec: AgentSpec) : ParsedAgentCall
    data class Parallel(override val say: String?, val specs: List<AgentSpec>) : ParsedAgentCall
}

object AgentCallParser {

    // A mission longer than AgentMissionHub.LONG_MISSION_MS is hosted by a
    // foreground service, so the ceiling is what the phone can really do
    // rather than what one screen's coroutine survives.
    const val MAX_STEPS_HARD_CAP = 40
    const val MAX_BUDGET_HARD_CAP_MS = 60 * 60_000L
    const val MAX_PARALLEL_HARD_CAP = 3

    /**
     * Attempts to parse [parsed] as an agent-dispatch instruction.
     *
     * @return [ParsedAgentCall.Single] / [ParsedAgentCall.Parallel] on
     *         success, `null` when the JSON shape is unrecognised or
     *         mandatory fields are missing.
     */
    fun tryParse(parsed: JsonObject): ParsedAgentCall? =
        when ((parsed["type"] as? JsonPrimitive)?.content) {
            "agent" -> parseSingle(parsed)
            "agent-parallel" -> parseParallel(parsed)
            else -> null
        }

    // ── Internal ─────────────────────────────────────────────────────────────

    internal fun parseSingle(obj: JsonObject): ParsedAgentCall.Single? {
        val say = (obj["say"] as? JsonPrimitive)?.content
        // Accept payload wrapper OR flat object (for inner parallel elements).
        val payload = (obj["payload"] as? JsonObject) ?: obj

        val goal = (payload["goal"] as? JsonPrimitive)
            ?.content?.ifBlank { null }
            ?: return null

        val plan = (payload["plan"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }

        val tools = (payload["tools"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.toSet()
            ?: emptySet()

        val maxSteps = (payload["maxSteps"] as? JsonPrimitive)
            ?.content?.toIntOrNull()
            ?: 6

        val budgetMs = (payload["budgetMs"] as? JsonPrimitive)
            ?.content?.toLongOrNull()
            ?: 60_000L

        val preferLocal = (payload["preferLocal"] as? JsonPrimitive)
            ?.booleanOrNull
            ?: false

        val agentId = (payload["agentId"] as? JsonPrimitive)?.content
            ?: UUID.randomUUID().toString()

        return ParsedAgentCall.Single(
            say = say,
            spec = AgentSpec(
                agentId = agentId,
                goal = goal,
                plan = plan,
                allowedTools = tools,
                maxSteps = maxSteps.coerceIn(1, MAX_STEPS_HARD_CAP),
                budgetMs = budgetMs.coerceIn(1_000L, MAX_BUDGET_HARD_CAP_MS),
                preferLocal = preferLocal,
            ),
        )
    }

    private fun parseParallel(obj: JsonObject): ParsedAgentCall.Parallel? {
        val say = (obj["say"] as? JsonPrimitive)?.content
        val arr = (obj["agents"] as? JsonArray) ?: return null

        val specs = arr.mapNotNull { el ->
            val asObj = el as? JsonObject ?: return@mapNotNull null
            // Wrap the inner object in a synthetic {"type":"agent","payload":…}
            // so parseSingle handles all field extraction uniformly.
            val synthetic = buildJsonObject {
                put("type", "agent")
                put("payload", asObj)
            }
            parseSingle(synthetic)?.spec
        }

        if (specs.isEmpty()) return null
        return ParsedAgentCall.Parallel(say = say, specs = specs.take(MAX_PARALLEL_HARD_CAP))
    }
}
