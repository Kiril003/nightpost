package local.phantom.companion.core.tool

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.constitution.AuditVerdict
import local.phantom.companion.core.constitution.ConstitutionRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of AI-callable [Tool]s. Admission is gated by
 * [ConstitutionRegistry]: each tool's [Tool.manifest] is registered at
 * construction time and only tools whose manifest passes
 * [local.phantom.companion.core.constitution.AxiomAuditor] are kept.
 *
 * Rejected tools are silently excluded — the rejection verdict is already
 * visible in [ConstitutionRegistry.rejections]; this class does not
 * re-surface it to avoid double-reporting.
 *
 * @param tools               Initial set of tools to evaluate.
 * @param constitutionRegistry Live registry used for manifest audit + tracking.
 */
class ToolRegistry(
    tools: List<Tool>,
    constitutionRegistry: ConstitutionRegistry,
) {

    private val store = ConcurrentHashMap<String, Tool>()
    private val _tools = MutableStateFlow<List<Tool>>(emptyList())

    /** Observable snapshot of all currently registered (passing) tools. */
    val tools: StateFlow<List<Tool>> = _tools.asStateFlow()

    private val _rejectedTools = MutableStateFlow<Map<String, AuditVerdict.Failed>>(emptyMap())

    /**
     * Tool id -> the verdict that excluded it, keyed by [Tool.id] rather than
     * [local.phantom.companion.core.constitution.FeatureManifest.id] — the two
     * are not the same string for every tool, so this is the only reliable way
     * to ask "was THIS tool id refused" instead of "was some manifest id".
     */
    val rejectedTools: StateFlow<Map<String, AuditVerdict.Failed>> = _rejectedTools.asStateFlow()

    init {
        val rejected = mutableMapOf<String, AuditVerdict.Failed>()
        for (tool in tools) {
            when (val verdict = constitutionRegistry.register(tool.manifest)) {
                is AuditVerdict.Passed -> store[tool.id] = tool
                is AuditVerdict.Failed -> rejected[tool.id] = verdict
            }
            // Failed verdict is already in constitutionRegistry.rejections —
            // no need to throw or log here.
        }
        _tools.value = store.values.toList()
        _rejectedTools.value = rejected
    }

    private val aliases = mapOf(
        "map.search" to "geo.search",
        "spatial.search" to "geo.search",
        "map.dossier" to "place.info",
        "weather.get" to "weather.now",
        "phone.call" to "phone.dial",
    )

    /**
     * Returns the [Tool] with the given [id], or `null` if it was never
     * registered or was rejected during the constitution audit.
     */
    fun get(id: String): Tool? = store[id] ?: aliases[id]?.let { store[it] }

    /** Returns an immutable snapshot of all registered (passing) tools. */
    fun list(): List<Tool> = store.values.toList()

    /** The verdict that excluded [id], or `null` if [id] was never a rejected tool. */
    fun rejectionFor(id: String): AuditVerdict.Failed? = _rejectedTools.value[id]
}
