package local.phantom.companion.core.tool

/**
 * Streaming events emitted by a [Tool] during execution. The agentic
 * loop forwards these to the UI as soon as they arrive so the operator
 * sees incremental output rather than waiting for the final result.
 *
 * - [Chunk] — raw text fragment (stdout, incremental answer, …).
 * - [Progress] — deterministic progress signal (0.0–1.0 ratio).
 * - [Notice] — informational side-band message (e.g. "connecting…").
 */
sealed interface ToolEvent {
    data class Chunk(val text: String) : ToolEvent
    data class Progress(val ratio: Float, val label: String?) : ToolEvent
    data class Notice(val message: String) : ToolEvent
}
