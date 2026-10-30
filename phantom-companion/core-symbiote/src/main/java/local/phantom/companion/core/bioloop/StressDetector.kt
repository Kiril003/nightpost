package local.phantom.companion.core.bioloop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tier 17-B — multi-modal stress detection. The vision text spec
 * names four signals: HRV crash, voice-pitch spike, typo cluster,
 * scroll-velocity spike. Each is fed via [ingest]; the detector
 * fuses them into a [StressLevel] using a thresholded weighted sum.
 *
 * Weights and thresholds intentionally live as constants — they were
 * picked from the empirical baselines the vision document references
 * and should be tuned with a separate calibration job before being
 * persisted per operator.
 */
class StressDetector(
    private val weights: Weights = Weights(),
) {

    data class Weights(
        val hrvCrash: Double = 0.4,
        val voicePitch: Double = 0.2,
        val typoCluster: Double = 0.2,
        val scrollVelocity: Double = 0.2,
    )

    private val signals = mutableMapOf(
        Signal.HRV_CRASH to 0.0,
        Signal.VOICE_PITCH to 0.0,
        Signal.TYPO_CLUSTER to 0.0,
        Signal.SCROLL_VELOCITY to 0.0,
    )

    enum class Signal { HRV_CRASH, VOICE_PITCH, TYPO_CLUSTER, SCROLL_VELOCITY }

    private val _level = MutableStateFlow(StressLevel.CALM)
    val level: StateFlow<StressLevel> = _level.asStateFlow()

    private val _score = MutableStateFlow(0.0)
    val score: StateFlow<Double> = _score.asStateFlow()

    /** Each signal is 0.0..1.0; values outside that are clamped. */
    fun ingest(signal: Signal, normalised: Double) {
        signals[signal] = normalised.coerceIn(0.0, 1.0)
        recompute()
    }

    fun reset() {
        signals.keys.forEach { signals[it] = 0.0 }
        recompute()
    }

    private fun recompute() {
        val s = signals[Signal.HRV_CRASH]!! * weights.hrvCrash +
            signals[Signal.VOICE_PITCH]!! * weights.voicePitch +
            signals[Signal.TYPO_CLUSTER]!! * weights.typoCluster +
            signals[Signal.SCROLL_VELOCITY]!! * weights.scrollVelocity
        _score.value = s
        _level.value = when {
            s >= 0.7 -> StressLevel.HIGH
            s >= 0.4 -> StressLevel.ELEVATED
            else -> StressLevel.CALM
        }
    }
}

enum class StressLevel { CALM, ELEVATED, HIGH }
