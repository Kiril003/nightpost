package local.phantom.companion.core.tool

import android.content.Context

/**
 * Execution context threaded into every [Tool.execute] call. The
 * [approvedPermissions] set is the contract between the permission gate
 * (Phase 1.B) and the tool layer — only permissions explicitly listed
 * here are considered granted for this invocation.
 *
 * [agentDepth] tracks recursion: 0 = root user request, 1 = sub-agent
 * spawned by the root, and so on. Tools that side-effect the device
 * may enforce a depth cap.
 */
data class ToolContext(
    val appContext: Context,
    val operatorId: String,
    val requestId: String,
    val agentDepth: Int = 0,
    val approvedPermissions: Set<ToolPermission>,
)
