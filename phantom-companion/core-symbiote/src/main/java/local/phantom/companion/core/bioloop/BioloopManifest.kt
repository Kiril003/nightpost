package local.phantom.companion.core.bioloop

import local.phantom.companion.core.constitution.FeatureManifest

object BioloopManifests {

    val HRV_TEMPO = FeatureManifest(
        id = "17-A.hrv-tempo",
        tier = 17,
        displayName = "ВСР → темп кулі",
        surfaces = setOf(FeatureManifest.Surface.AMBIENT),
        sunsetAfterDaysIdle = 90,
    )

    val STRESS_DETECTOR = FeatureManifest(
        id = "17-B.stress-detector",
        tier = 17,
        displayName = "Виявлення стресу",
        surfaces = setOf(FeatureManifest.Surface.STREAM),
        sunsetAfterDaysIdle = 90,
    )

    val SLEEP_UI = FeatureManifest(
        id = "17-C.sleep-ui",
        tier = 17,
        displayName = "Інтерфейс за фазою сну",
        surfaces = setOf(FeatureManifest.Surface.AMBIENT),
        sunsetAfterDaysIdle = 120,
    )

    val CIRCADIAN_VOICE = FeatureManifest(
        id = "17-D.circadian-voice",
        tier = 17,
        displayName = "Добове налаштування голосу",
        surfaces = setOf(FeatureManifest.Surface.VOICE),
        sunsetAfterDaysIdle = 120,
    )

    val BREATH_ANIM = FeatureManifest(
        id = "17-E.breath-animation",
        tier = 17,
        displayName = "Куля в темпі дихання",
        surfaces = setOf(FeatureManifest.Surface.AMBIENT),
        sunsetAfterDaysIdle = 60,
    )

    val EMOTION_FUSION = FeatureManifest(
        id = "17-F.emotion-fusion",
        tier = 17,
        displayName = "Злиття емоцій за EDA",
        sunsetAfterDaysIdle = 90,
    )

    val all: List<FeatureManifest> = listOf(
        HRV_TEMPO, STRESS_DETECTOR, SLEEP_UI,
        CIRCADIAN_VOICE, BREATH_ANIM, EMOTION_FUSION,
    )
}
