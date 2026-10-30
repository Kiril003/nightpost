package local.phantom.companion.core.context

import kotlinx.serialization.Serializable

/**
 * Epoch II: Perception / Context Frame.
 *
 * A unified snapshot of reality representing all semantic channels
 * at a given moment.
 */
@Serializable
data class ContextFrame(
    val timestampMs: Long,
    val state: String, // String representation of SystemState
    val who: WhoContext,
    val where: WhereContext,
    val what: WhatContext,
    val feel: FeelContext,
    val owe: OweContext,
    val device: DeviceContext,
    val mesh: MeshContext
)

@Serializable
data class WhoContext(
    val profileId: String?,
    val activeRole: String? = null,
    val hasPeopleNearby: Boolean = false
)

@Serializable
data class WhereContext(
    val placeLabel: String,            // e.g. "Home", "Work", "On the Go", "Unknown"
    val lat: Double? = null,
    val lon: Double? = null,
    val speed: Float? = null
)

@Serializable
data class WhatContext(
    val activeAppPackage: String? = null,
    val detectedSounds: List<String> = emptyList(),
    val screenSummary: String? = null
)

@Serializable
data class FeelContext(
    val stressLevel: Double? = null,           // 0..1 calculated from StressDetector
    val cognitiveLoad: Double? = null,       // 0..1 calculated from CognitiveLoadEstimator
    val emotionLabel: String? = null,          // from EmotionFusion
    val breathingRate: Double? = null        // breathing rate from BreathDetector
)

@Serializable
data class OweContext(
    val commitmentsCount: Int = 0,             // active tasks or commitments detected
    val nextAlarmMs: Long? = null
)

@Serializable
data class DeviceContext(
    val batteryLevel: Int? = null,
    val isCharging: Boolean = false,
    val storageAvailableBytes: Long? = null
)

@Serializable
data class MeshContext(
    val isDesktopConnected: Boolean = false,
    val latencyMs: Long? = null
)
