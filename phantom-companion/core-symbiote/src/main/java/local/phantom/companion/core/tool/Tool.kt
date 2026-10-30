package local.phantom.companion.core.tool

import kotlinx.serialization.json.JsonObject
import local.phantom.companion.core.constitution.FeatureManifest

/**
 * Contract that every AI-callable tool must implement. Tools are
 * registered in [ToolRegistry] which gates admission through
 * [local.phantom.companion.core.constitution.AxiomAuditor] — a tool
 * whose [manifest] declares a constitution violation is silently
 * excluded and its manifest appears in
 * [local.phantom.companion.core.constitution.ConstitutionRegistry.rejections].
 *
 * Tool authors declare their [permissions] set upfront; the agentic
 * loop will refuse to call [execute] if the required permissions are
 * not present in [ToolContext.approvedPermissions].
 */
interface Tool {
    /** Machine-readable stable identifier, e.g. `"shell"`, `"http"`. */
    val id: String

    /** One-line description injected into the AI system prompt via [ToolPromptInjector]. */
    val description: String

    /**
     * JSON Schema (as a [JsonObject]) describing the accepted arguments.
     * Example: `{"cmd":"string","timeoutMs":"number?"}`.
     * Keys are used by [ToolPromptInjector] to list the argument names.
     */
    val argsSchema: JsonObject

    /** Permissions this tool requires — checked by the agentic loop before dispatch. */
    val permissions: Set<ToolPermission>

    /**
     * Наслідок виклику для оператора. Ворота судять за ним, не за [permissions];
     * значення за замовчуванням немає навмисно — див. [ToolRisk].
     */
    val risk: ToolRisk

    /** Згода на кожен виклик: «завжди» для такого інструмента не запамʼятовується. */
    val askEveryCall: Boolean get() = false

    /** Whether this tool streams incremental [ToolEvent.Chunk] events during execution. */
    val streaming: Boolean

    /** Constitutional self-declaration evaluated by [ToolRegistry] at registration time. */
    val manifest: FeatureManifest

    /**
     * Execute the tool.
     *
     * @param args    The parsed argument object from the AI response.
     * @param context Execution context including approved permissions.
     * @param emit    Suspend callback for streaming [ToolEvent]s.
     * @return        Terminal [ToolResult].
     */
    suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult
}
