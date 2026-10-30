package local.phantom.companion.core.tool

import kotlinx.serialization.json.JsonObject

/**
 * The result of [ToolCallParser.tryParse] — a strongly-typed view of the
 * `{"type":"tool-call","tool":"…","args":{…}}` JSON object the AI emits.
 *
 * Optional fields default to safe values when absent from the raw JSON:
 * [stream] defaults to `false`, [say] and [agentId] default to `null`.
 */
data class ParsedToolCall(
    /** ID of the [Tool] to invoke, e.g. `"shell"`. */
    val toolId: String,
    /** Argument object forwarded verbatim to [Tool.execute]. */
    val args: JsonObject,
    /** Whether the caller expects streaming [ToolEvent.Chunk] events. */
    val stream: Boolean,
    /** Optional TTS cue the AI wants spoken while the tool runs. */
    val say: String?,
    /** Sub-agent identity tag for multi-agent dispatch (Phase 1.D). */
    val agentId: String?,
)
