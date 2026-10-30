package local.phantom.companion.core.constitution

/**
 * Tier 29-A — the gate every symbiote feature passes before it is
 * permitted to register itself in [AppContainer]. A failing manifest
 * does not crash the process; instead the auditor returns a
 * [AuditVerdict.Failed] which call sites are expected to surface
 * through the error bus and refuse to wire the feature.
 *
 * The auditor is intentionally deterministic — there is no LLM judge
 * in this layer. A feature author signs their own intent through
 * [FeatureManifest], and lying ships as a verdict that can be diffed
 * in code review.
 */
class AxiomAuditor {

    fun audit(manifest: FeatureManifest): AuditVerdict {
        val violations = mutableListOf<AxiomViolation>()

        if (manifest.erodesOperatorAgency) {
            violations += AxiomViolation(
                axiom = Axiom.OPERATOR_ADVOCATE,
                reason = "feature declared erodesOperatorAgency=true",
            )
        }
        if (manifest.exfiltratesPrivate) {
            violations += AxiomViolation(
                axiom = Axiom.OPERATOR_ADVOCATE,
                reason = "feature declared exfiltratesPrivate=true",
            )
        }
        if (manifest.actsWithoutConsent) {
            violations += AxiomViolation(
                axiom = Axiom.REFUSAL_OK,
                reason = "feature declared actsWithoutConsent=true",
            )
        }
        if (manifest.abusableAgainstWeak) {
            violations += AxiomViolation(
                axiom = Axiom.POWER_ASYMMETRY_GUARD,
                reason = "feature declared abusableAgainstWeak=true",
            )
        }
        if (manifest.sunsetAfterDaysIdle == 0) {
            // Zero means "expire immediately"; that is almost certainly
            // a bug. The contract is "negative for never, positive for
            // a window". Reject so the operator is not silently shipped
            // a feature that refuses to enable itself.
            violations += AxiomViolation(
                axiom = Axiom.SUNSET_CLAUSE,
                reason = "sunsetAfterDaysIdle=0 is ambiguous",
            )
        }

        return if (violations.isEmpty()) {
            AuditVerdict.Passed(manifest.id)
        } else {
            AuditVerdict.Failed(manifest.id, manifest.displayName, violations.toList())
        }
    }
}

sealed interface AuditVerdict {
    val featureId: String

    data class Passed(override val featureId: String) : AuditVerdict
    data class Failed(
        override val featureId: String,
        val displayName: String,
        val violations: List<AxiomViolation>,
    ) : AuditVerdict
}

data class AxiomViolation(
    val axiom: Axiom,
    val reason: String,
)
