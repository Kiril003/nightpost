package local.phantom.companion.core.agent

import local.phantom.companion.core.constitution.AuditVerdict
import local.phantom.companion.core.constitution.ConstitutionRegistry

/**
 * Bridges [AgentSupervisor] with the constitutional layer.
 *
 * When a sub-agent is about to start, [acquire] registers a short-lived
 * [local.phantom.companion.core.constitution.FeatureManifest] derived from
 * the [AgentSpec] via [AgentSpec.toManifest]. If the [AxiomAuditor] inside
 * [ConstitutionRegistry] rejects the manifest, [acquire] returns `false`
 * and the supervisor must not run the agent.
 *
 * [release] **must** be called in a `finally` block so the ephemeral
 * manifest is removed regardless of whether the agent succeeded, failed,
 * or was cancelled.
 */
open class AgentConstitutionGate(
    private val registry: ConstitutionRegistry,
) {

    /**
     * Registers the ephemeral manifest for [spec].
     *
     * @return `true` when the manifest passed the audit; `false` when it was
     *         rejected (supervisor should return [AgentResult.Failed] immediately).
     */
    open fun acquire(spec: AgentSpec): Boolean {
        val manifest = spec.toManifest()
        val verdict = registry.register(manifest)
        return verdict is AuditVerdict.Passed
    }

    /**
     * Unregisters the ephemeral manifest for [spec].
     *
     * Safe to call even if [acquire] returned `false` — the registry's
     * [ConstitutionRegistry.unregister] is a no-op for unknown IDs.
     */
    fun release(spec: AgentSpec) {
        registry.unregister(spec.toManifest().id)
    }
}
