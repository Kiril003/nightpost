package local.phantom.companion.core.constitution

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of symbiote features. Each Tier 14+ module
 * registers its [FeatureManifest] at boot through [AppContainer].
 * The registry enforces the audit on register — a failing manifest
 * is rejected and reported so the caller never sees a successful
 * registration of a constitution-violating capability.
 */
class ConstitutionRegistry(
    private val auditor: AxiomAuditor = AxiomAuditor(),
) {

    private val store = ConcurrentHashMap<String, FeatureManifest>()
    private val _manifests = MutableStateFlow<List<FeatureManifest>>(emptyList())
    val manifests: StateFlow<List<FeatureManifest>> = _manifests.asStateFlow()

    private val _rejections = MutableStateFlow<List<AuditVerdict.Failed>>(emptyList())
    val rejections: StateFlow<List<AuditVerdict.Failed>> = _rejections.asStateFlow()

    fun register(manifest: FeatureManifest): AuditVerdict {
        val verdict = auditor.audit(manifest)
        when (verdict) {
            is AuditVerdict.Passed -> {
                store[manifest.id] = manifest
                _manifests.value = store.values.toList()
            }
            is AuditVerdict.Failed -> {
                // Two boot paths write this flow (ToolRegistry.init and
                // AppContainer.bootSymbioteManifests), and the diagnostics card
                // shows its size — a read-modify-write would drop one verdict,
                // and a plain append would count the same refusal twice if a
                // registry is ever rebuilt in a live process.
                _rejections.update { current ->
                    current.filterNot { it.featureId == verdict.featureId } + verdict
                }
            }
        }
        return verdict
    }

    fun unregister(featureId: String) {
        if (store.remove(featureId) != null) {
            _manifests.value = store.values.toList()
        }
    }

    fun snapshot(): List<FeatureManifest> = store.values.toList()
}
