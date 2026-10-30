package local.phantom.companion.core.tool

import kotlinx.serialization.json.JsonObject

/**
 * Terminal result of a [Tool.execute] call. The sealed hierarchy makes
 * every outcome explicit — call sites cannot silently ignore
 * [PermissionDenied] or [Cancelled].
 *
 * [durationMs] is carried by every variant so callers can log latency
 * without unwrapping.
 */
sealed interface ToolResult {
    val durationMs: Long

    /**
     * The tool completed successfully.
     *
     * @param output   Plain-text representation suitable for the AI prompt.
     * @param structured Machine-readable payload. The card layer reads it by
     *   FIXED key names — a list under `results` (rows keyed `title`/`name`,
     *   `snippet`/`description`, `lat`/`lon`, `url`), a measurement under
     *   `distance_m`/`duration_s`. A tool that names the same datum something
     *   else draws no card at all: `medevac.nearest` said `routed_distance_m`
     *   and a life-safety answer was never rendered. Carry `source`, `as_of_ms`
     *   and `confidence` alongside so freshness and doubt survive the trip.
     */
    data class Ok(
        val output: String,
        val structured: JsonObject?,
        override val durationMs: Long,
    ) : ToolResult

    /**
     * The tool encountered an error. [code] is a short machine-readable
     * key (e.g. `"EXIT_NONZERO"`, `"TIMEOUT"`); [message] is operator-language
     * Ukrainian and must carry both why it failed and what would change the
     * outcome — it is the only text that reaches the operator, and a bare
     * «не вдалося» leaves both him and the model with nothing to do next.
     */
    data class Err(
        val code: String,
        val message: String,
        override val durationMs: Long,
    ) : ToolResult

    /**
     * At least one required permission was not in
     * [ToolContext.approvedPermissions]. The tool did not execute.
     */
    data class PermissionDenied(
        val permission: ToolPermission,
        override val durationMs: Long,
    ) : ToolResult

    /** The operator (or the supervisor) cancelled the tool mid-flight. */
    data class Cancelled(override val durationMs: Long) : ToolResult
}
