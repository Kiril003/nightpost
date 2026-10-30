package local.phantom.companion.core.bioloop

/**
 * Tier 17-A — HRV-derived UI animation tempo, with thresholds taken
 * verbatim from the vision doc. The orb's breathing-cycle duration
 * is a function of this enum, so call sites stay decoupled from the
 * numerical HRV bands.
 */
enum class UiTempo(val breathingCycleSeconds: Double) {
    RELAXED(breathingCycleSeconds = 6.0),
    STEADY(breathingCycleSeconds = 4.0),
    STRESS(breathingCycleSeconds = 0.0);

    companion object {
        /**
         * Maps a heart-rate (bpm) reading to a tempo bucket using the
         * vision thresholds: ≤60 relaxed, 60-80 steady, >80 stress.
         */
        fun fromBpm(bpm: Int): UiTempo = when {
            bpm <= 60 -> RELAXED
            bpm <= 80 -> STEADY
            else -> STRESS
        }
    }
}
