package local.phantom.companion.core.predict

import local.phantom.companion.core.constitution.FeatureManifest

object PredictManifests {

    val DOUBT_AMPLIFIER = FeatureManifest(
        id = "15-E.doubt-amplifier",
        tier = 15,
        displayName = "Підсилювач сумніву",
        surfaces = setOf(FeatureManifest.Surface.STREAM),
        sunsetAfterDaysIdle = 365,
    )

    val CONVICTION_SCORING = FeatureManifest(
        id = "15-F.conviction-scoring",
        tier = 15,
        displayName = "Оцінка впевненості",
        surfaces = setOf(FeatureManifest.Surface.STREAM),
        sunsetAfterDaysIdle = 365,
    )

    val REHEARSAL = FeatureManifest(
        id = "15-B.rehearsal",
        tier = 15,
        displayName = "Репетиція рішення",
        surfaces = setOf(FeatureManifest.Surface.STREAM),
        sunsetAfterDaysIdle = 365,
    )

    val SHIMMER = FeatureManifest(
        id = "15-C.shimmer",
        tier = 15,
        displayName = "Передчуттєве мерехтіння",
        surfaces = setOf(FeatureManifest.Surface.STREAM, FeatureManifest.Surface.AMBIENT),
        sunsetAfterDaysIdle = 60,
    )

    val all: List<FeatureManifest> = listOf(
        DOUBT_AMPLIFIER, CONVICTION_SCORING, REHEARSAL, SHIMMER,
    )
}
