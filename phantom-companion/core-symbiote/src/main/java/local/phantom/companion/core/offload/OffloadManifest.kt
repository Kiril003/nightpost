package local.phantom.companion.core.offload

import local.phantom.companion.core.constitution.FeatureManifest

object OffloadManifests {

    val MENTAL_PARK = FeatureManifest(
        id = "23-A.mental-park",
        tier = 23,
        displayName = "Ментальний парк",
        surfaces = setOf(FeatureManifest.Surface.STREAM),
        sunsetAfterDaysIdle = 365,
    )

    val WORKING_MEMORY = FeatureManifest(
        id = "23-C.working-memory",
        tier = 23,
        displayName = "Кільце робочої памʼяті",
        surfaces = setOf(FeatureManifest.Surface.STREAM),
        sunsetAfterDaysIdle = 60,
    )

    val WILLPOWER = FeatureManifest(
        id = "23-D.willpower",
        tier = 23,
        displayName = "Зовнішня воля",
        surfaces = setOf(FeatureManifest.Surface.HAPTIC, FeatureManifest.Surface.NOTIFICATION),
        sunsetAfterDaysIdle = 90,
    )

    val ANCHOR = FeatureManifest(
        id = "23-E.anchor-objects",
        tier = 23,
        displayName = "Обʼєкти-якорі",
        surfaces = setOf(FeatureManifest.Surface.STREAM),
        sunsetAfterDaysIdle = 180,
    )

    val all: List<FeatureManifest> = listOf(MENTAL_PARK, WORKING_MEMORY, WILLPOWER, ANCHOR)
}
