package local.phantom.companion.feature.stream.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import local.phantom.companion.core.agent.AgentProgress
import local.phantom.companion.core.agent.AgentSpec

/** Payload shape of the `agent-progress` widget, from launch to terminal state. */
object AgentProgressPayload {

    fun initial(
        spec: AgentSpec,
        status: String,
        lastStep: AgentProgress? = null,
        summary: String? = null,
    ): String = buildJsonObject {
        put("agentId", JsonPrimitive(spec.agentId))
        put("goal", JsonPrimitive(spec.goal))
        put("status", JsonPrimitive(status))
        put("stepsRun", JsonPrimitive(lastStep?.stepIndex ?: 0))
        put("currentAction", JsonPrimitive(lastStep?.action.orEmpty()))
        put("currentStatus", JsonPrimitive(lastStep?.status.orEmpty()))
        put("currentDetail", JsonPrimitive(lastStep?.detail.orEmpty()))
        put("summary", JsonPrimitive(summary.orEmpty()))
        spec.allowedTools.takeIf { it.isNotEmpty() }?.let { tools ->
            put("tools", JsonArray(tools.map { JsonPrimitive(it) }))
        }
    }.toString()

    fun merge(payloadJson: String, progress: AgentProgress): String = buildJsonObject {
        reuse(payloadJson)
        put("status", JsonPrimitive("running"))
        put("stepsRun", JsonPrimitive(progress.stepIndex))
        put("currentAction", JsonPrimitive(progress.action))
        put("currentStatus", JsonPrimitive(progress.status))
        put("currentDetail", JsonPrimitive(progress.detail.orEmpty()))
    }.toString()

    fun terminal(
        payloadJson: String,
        status: String,
        summary: String,
        stepsRun: Int,
        durationMs: Long,
    ): String = buildJsonObject {
        reuse(payloadJson)
        put("status", JsonPrimitive(status))
        put("summary", JsonPrimitive(summary))
        put("stepsRun", JsonPrimitive(stepsRun))
        put("durationMs", JsonPrimitive(durationMs))
    }.toString()

    fun sealInterrupted(payloadJson: String): String? {
        val existing = runCatching {
            Json.parseToJsonElement(payloadJson) as? JsonObject
        }.getOrNull() ?: return null
        val status = (existing["status"] as? JsonPrimitive)?.content ?: return null
        if (status != "running") return null
        return buildJsonObject {
            existing.forEach { (k, v) -> put(k, v) }
            put("status", JsonPrimitive(STATUS_INTERRUPTED))
        }.toString()
    }

    const val STATUS_INTERRUPTED = "interrupted"

    private fun kotlinx.serialization.json.JsonObjectBuilder.reuse(payloadJson: String) {
        val existing = runCatching {
            Json.parseToJsonElement(payloadJson) as? JsonObject
        }.getOrNull() ?: JsonObject(emptyMap())
        existing.forEach { (k, v) -> put(k, v) }
    }
}
