package local.phantom.companion.core.constitution

/**
 * A feature-self-description that [AxiomAuditor] evaluates. Every
 * symbiote-tier capability registers one of these at boot so the
 * constitutional layer can refuse to enable a feature whose own
 * disclosures violate an axiom.
 *
 * The fields are intentionally declarative rather than inferred — a
 * feature author who writes `harmsOperatorAgency = true` is openly
 * saying "this should not ship" and the auditor blocks it. The
 * format is the contract; you cannot lie *and* compile.
 */
data class FeatureManifest(
    val id: String,
    val tier: Int,
    val displayName: String,
    /** Set to true if the feature increases dependence on phantom. */
    val erodesOperatorAgency: Boolean = false,
    /** True if it shares operator-private data with a third party. */
    val exfiltratesPrivate: Boolean = false,
    /** True if it acts on behalf of the operator without explicit consent. */
    val actsWithoutConsent: Boolean = false,
    /** True if it could be wielded against children or cognitively impaired. */
    val abusableAgainstWeak: Boolean = false,
    /** Days of inactivity after which sunset auto-disables (negative = never). */
    val sunsetAfterDaysIdle: Int = SUNSET_DEFAULT_DAYS,
    /** Surfaces the feature uses for output — informs power-asymmetry guard. */
    val surfaces: Set<Surface> = emptySet(),
) {

    enum class Surface { STREAM, HAPTIC, VOICE, NOTIFICATION, AMBIENT, MAP, AR }

    companion object {
        /**
         * Default sunset window. Picked at 180 days because 6 months
         * matches the canonical reflection cadence in Tier 15-F
         * (Conviction Scoring 90/180/365). Lower if the feature
         * touches sensitive data.
         */
        const val SUNSET_DEFAULT_DAYS = 180
    }
}
