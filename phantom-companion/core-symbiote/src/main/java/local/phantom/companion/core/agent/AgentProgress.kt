package local.phantom.companion.core.agent

/**
 * A single progress snapshot emitted by [AgentSupervisor] to [AgentProgressSink].
 *
 * @param agentId    The running agent's stable ID.
 * @param stepIndex  1-based step counter. Step 0 is the initial "thinking" ping
 *                   emitted before the first model call; step 1+ are tool rounds.
 * @param action     Tool ID being dispatched, `"thinking"`, or `"done"`.
 * @param status     One of: `"running"` | `"ok"` | `"error"` | `"skipped"`.
 * @param detail     Optional human-readable note, e.g. `"5 lines stdout"` or
 *                   `"DENIED: NETWORK"`. May be truncated to 120 chars by the
 *                   supervisor before forwarding.
 */
data class AgentProgress(
    val agentId: String,
    val stepIndex: Int,
    val action: String,
    val status: String,
    val detail: String? = null,
)
