package local.phantom.companion.core.agent

import kotlinx.serialization.Serializable
import local.phantom.companion.core.constitution.FeatureManifest

/**
 * Immutable specification that drives a single [AgentSupervisor] run.
 *
 * @param agentId       Stable identifier for this agent run (UUID v4 by default).
 * @param goal          Natural-language goal injected as the first USER turn.
 * @param plan          Optional ordered decomposition the AI already proposed;
 *                      forwarded to the system prompt for grounding.
 * @param allowedTools  Set of tool IDs the supervisor is permitted to dispatch.
 *                      The supervisor will refuse (and fail) any tool not listed.
 * @param maxSteps      Hard cap on tool-call rounds before [AgentResult.Failed]
 *                      with reason `"budget_exhausted"` is returned.
 * @param budgetMs      Wall-clock deadline; exceeded mid-loop yields same outcome.
 * @param preferLocal   If `true` the supervisor picks the local Gemma router;
 *                      otherwise the cloud Gemini router is used.
 * @param parentItemId  Stream item ID for UI threading (Phase 2.C).
 */
@Serializable
data class AgentSpec(
    val agentId: String = java.util.UUID.randomUUID().toString(),
    val goal: String,
    val plan: List<String>? = null,
    val allowedTools: Set<String> = emptySet(),
    val maxSteps: Int = 6,
    val budgetMs: Long = 60_000L,
    val preferLocal: Boolean = false,
    val parentItemId: String? = null,
)

/**
 * Declares a short-lived [FeatureManifest] for this agent run so the
 * constitutional layer can track it. Registered/unregistered in Phase 2.C.
 */
fun AgentSpec.toManifest(): FeatureManifest = FeatureManifest(
    id = "agent.adhoc.$agentId",
    tier = 2,
    displayName = "Підагент: ${goal.take(40)}",
    sunsetAfterDaysIdle = 1,
    surfaces = setOf(FeatureManifest.Surface.STREAM),
)
