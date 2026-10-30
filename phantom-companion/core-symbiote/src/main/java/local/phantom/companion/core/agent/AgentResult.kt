package local.phantom.companion.core.agent

import kotlinx.serialization.json.JsonElement

/**
 * Terminal outcome of an [AgentSupervisor.run] call.
 *
 * Every variant carries [agentId], [stepsRun], and [durationMs] so callers
 * can log telemetry without pattern-matching on the concrete type.
 */
sealed interface AgentResult {
    val agentId: String
    val stepsRun: Int
    val durationMs: Long

    /**
     * The agent reached its goal. [summary] is the AI-provided short
     * description; [artifacts] are the structured outputs it produced.
     */
    data class Done(
        override val agentId: String,
        override val stepsRun: Int,
        override val durationMs: Long,
        val summary: String,
        val artifacts: List<JsonElement>,
    ) : AgentResult

    /**
     * The agent could not complete the goal. [reason] is a machine-readable
     * code from the following set:
     *   - `"tool_forbidden:<id>"` — tool not in allowedTools
     *   - `"tool_missing:<id>"`   — tool not in registry
     *   - `"tool_error"`          — tool returned ToolResult.Err
     *   - `"permission_denied:<reason>"` — ToolPermissionGate denied
     *   - `"budget_exhausted"`    — maxSteps or budgetMs exceeded
     *   - `"bad_response"`        — AI returned unparseable JSON
     *   - `"bad_tool_call"`       — tool-call JSON was malformed
     *   - `"router_error:<msg>"`  — AIRouter threw an exception
     *   - `"unrecognized_type"`   — top-level "type" was unexpected
     *   - `"internal"`            — unexpected exception
     */
    data class Failed(
        override val agentId: String,
        override val stepsRun: Int,
        override val durationMs: Long,
        val reason: String,
        val partialSummary: String? = null,
    ) : AgentResult

    /**
     * The coroutine scope was cancelled externally before the agent finished.
     * Phase 2.B will expose a first-class cancellation API; until then this
     * is only emitted when the caller cancels the enclosing coroutine scope.
     */
    data class Cancelled(
        override val agentId: String,
        override val stepsRun: Int,
        override val durationMs: Long,
    ) : AgentResult
}
