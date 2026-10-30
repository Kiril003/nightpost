package local.phantom.companion.core.tool.diary

/**
 * Immutable audit record for a single tool invocation.
 *
 * Captured by the agentic loop after every [local.phantom.companion.core.tool.Tool.execute]
 * call (successful or not) and written to [DecisionDiary].
 *
 * @param id            Unique entry ID (UUID v4 or similar).
 * @param timestamp     Wall-clock epoch millis at the moment of recording.
 * @param toolId        The [local.phantom.companion.core.tool.Tool.id] that was invoked.
 * @param args          JSON-serialised arguments passed to the tool (may be truncated).
 * @param outcome       Short outcome code:
 *                        `"ok"` — [local.phantom.companion.core.tool.ToolResult.Ok],
 *                        `"err:<code>"` — [local.phantom.companion.core.tool.ToolResult.Err],
 *                        `"denied"` — [local.phantom.companion.core.tool.ToolResult.PermissionDenied],
 *                        `"cancelled"` — [local.phantom.companion.core.tool.ToolResult.Cancelled].
 * @param outputDigest  First 200 characters of the tool output (for quick review).
 * @param agentId       Identifier of the sub-agent that triggered the call, or
 *                      `null` if called from the root user request.
 * @param agentDepth    Recursion depth: 0 = root, 1 = first-level sub-agent, etc.
 * @param risk          Тариф ризику інструмента на момент виклику:
 *                      `"read"` / `"device"` / `"outward"`, або [RISK_UNKNOWN],
 *                      якщо місце виклику його ще не називає.
 * @param decision      Як вирішився дозвіл: `"read"` (не питали — читання),
 *                      `"once"`, `"always"`, `"never"`, `"denied"`; `null` —
 *                      ворота не зважували цей виклик.
 */
data class DecisionEntry(
    val id: String,
    val timestamp: Long,
    val toolId: String,
    val args: String,
    val outcome: String,
    val outputDigest: String,
    val agentId: String?,
    val agentDepth: Int,
    val risk: String = RISK_UNKNOWN,
    val decision: String? = null,
) {
    companion object {
        /** Чесніше за «read»: запис знає, що ризику йому не назвали. */
        const val RISK_UNKNOWN: String = "unknown"
    }
}
