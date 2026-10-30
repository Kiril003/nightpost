package local.phantom.companion.feature.stream.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The `tool-trace` card's payload — what the operator sees while an instrument
 * runs and after it answers.
 *
 * It is built and amended in four places while a chain is live, each of them
 * hand-rolling the same JSON and the same merge. The merge is the part that
 * bites: a streaming tool arrives as many chunks, every one of which has to
 * land on top of the previous payload without losing the fields already there
 * and without letting a long-running tool grow the row without bound.
 */
object ToolTracePayload {

    /** A tool can talk for a long time; the card shows the tail, not a novel. */
    const val MAX_OUTPUT = 2000

    fun start(toolId: String, status: String, args: String, say: String?): String =
        buildJsonObject {
            put("toolId", JsonPrimitive(toolId))
            put("status", JsonPrimitive(status))
            put("args", JsonPrimitive(args))
            if (say != null) put("say", JsonPrimitive(say))
        }.toString()

    /**
     * Appends [chunk] to the payload's running output, keeping every other
     * field the card already carries. Unparseable input is treated as an empty
     * payload rather than thrown away — a malformed trace still has to show
     * what the tool is saying.
     */
    fun append(payload: String, chunk: String): String {
        val existing = parse(payload)
        val previous = (existing["output"] as? JsonPrimitive)
            ?.let { runCatching { it.content }.getOrNull() }
            .orEmpty()
        return buildJsonObject {
            existing.forEach { (k, v) -> put(k, v) }
            put("output", JsonPrimitive((previous + chunk).takeLast(MAX_OUTPUT)))
        }.toString()
    }

    private fun parse(payload: String): JsonObject =
        runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
}
