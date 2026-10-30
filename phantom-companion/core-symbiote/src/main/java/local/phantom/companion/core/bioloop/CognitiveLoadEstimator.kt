package local.phantom.companion.core.bioloop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tier 15-D / 17 — produces a `CognitiveLoad` value the Stream
 * renderer can use to thin / fatten the visible thread density.
 * Inputs are normalised 0.0..1.0; combination is a clamped weighted
 * sum. Stays in core-bioloop because it depends on stress signals.
 */
class CognitiveLoadEstimator(
    private val stressWeight: Double = 0.5,
    private val recentTypoWeight: Double = 0.25,
    private val recentScreenChurnWeight: Double = 0.25,
) {

    private var stress = 0.0
    private var typo = 0.0
    private var churn = 0.0

    private val _load = MutableStateFlow(CognitiveLoad.LOW)
    val load: StateFlow<CognitiveLoad> = _load.asStateFlow()

    private val _score = MutableStateFlow(0.0)
    val score: StateFlow<Double> = _score.asStateFlow()

    fun ingestStress(value: Double) {
        stress = value.coerceIn(0.0, 1.0); recompute()
    }

    fun ingestTypo(value: Double) {
        typo = value.coerceIn(0.0, 1.0); recompute()
    }

    fun ingestChurn(value: Double) {
        churn = value.coerceIn(0.0, 1.0); recompute()
    }

    private fun recompute() {
        val s = (stress * stressWeight) +
            (typo * recentTypoWeight) +
            (churn * recentScreenChurnWeight)
        _score.value = s
        _load.value = when {
            s >= 0.7 -> CognitiveLoad.HIGH
            s >= 0.4 -> CognitiveLoad.MEDIUM
            else -> CognitiveLoad.LOW
        }
    }
}

enum class CognitiveLoad { LOW, MEDIUM, HIGH }
