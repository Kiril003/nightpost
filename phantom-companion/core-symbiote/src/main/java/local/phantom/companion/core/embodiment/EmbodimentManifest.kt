package local.phantom.companion.core.embodiment

import local.phantom.companion.core.constitution.FeatureManifest

/**
 * Constitution manifests for every Tier 14 sub-feature that ships in
 * this module. They live alongside the implementation so the audit
 * surface and the code they describe stay in lockstep — drift becomes
 * a code-review diff, not a runtime surprise.
 */
object EmbodimentManifests {

    val PROPRIOCEPTIVE_TICKER = FeatureManifest(
        id = "14-A.proprioceptive-ticker",
        tier = 14,
        displayName = "Відчуття присутності",
        surfaces = setOf(FeatureManifest.Surface.HAPTIC),
        sunsetAfterDaysIdle = 60,
    )

    val POSTURE_MONITOR = FeatureManifest(
        id = "14-E.posture-monitor",
        tier = 14,
        displayName = "Дзеркало постави",
        surfaces = setOf(FeatureManifest.Surface.AMBIENT),
        sunsetAfterDaysIdle = 90,
    )

    val all: List<FeatureManifest> = listOf(PROPRIOCEPTIVE_TICKER, POSTURE_MONITOR)
}
