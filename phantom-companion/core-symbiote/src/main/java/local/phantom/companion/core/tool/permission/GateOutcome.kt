package local.phantom.companion.core.tool.permission

import local.phantom.companion.core.tool.ToolPermission

/**
 * Result of a [ToolPermissionGate.check] call.
 *
 * - [Granted] — the operator approved the call; [approved] contains the
 *   permissions that should be forwarded to [local.phantom.companion.core.tool.ToolContext].
 * - [Denied]  — the operator rejected the call or has a persistent NEVER
 *   decision stored. The tool must not be executed; [reason] is a short
 *   human-readable explanation for the diary / UI.
 */
sealed interface GateOutcome {
    data class Granted(
        val approved: Set<ToolPermission>,
        /** Як саме дозволено — рядок журналу «за чиїм словом». */
        val via: ConsentPath = ConsentPath.READ,
    ) : GateOutcome
    data class Denied(val reason: String) : GateOutcome
}
