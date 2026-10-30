package local.phantom.companion.core.embodiment

import local.phantom.companion.core.context.SystemState

/**
 * Tier 14-A — per-state proprioceptive tick pattern. A waveform is a
 * sequence of (duration, amplitude) pairs that maps directly to
 * [android.os.VibrationEffect.createWaveform] when a real Vibrator
 * is available. Amplitudes are 0..255 to match Android's API.
 *
 * The amplitudes are intentionally low (≤80/255). The vision goal is
 * "kinaesthetic internalisation, not alarm" — a tick the operator
 * notices only on the periphery of attention.
 */
data class HapticPattern(
    val timings: LongArray,
    val amplitudes: IntArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HapticPattern) return false
        return timings.contentEquals(other.timings) &&
            amplitudes.contentEquals(other.amplitudes)
    }

    override fun hashCode(): Int =
        31 * timings.contentHashCode() + amplitudes.contentHashCode()

    companion object {

        /** Single short tick. FOCUS heartbeat. */
        val FOCUS = HapticPattern(
            timings = longArrayOf(0L, 50L),
            amplitudes = intArrayOf(0, 50),
        )

        /** Two ticks in speech-rhythm. DIALOGUE. */
        val DIALOGUE = HapticPattern(
            timings = longArrayOf(0L, 40L, 80L, 40L),
            amplitudes = intArrayOf(0, 60, 0, 60),
        )

        /** Three mirror-symmetric ticks. SENTINEL. */
        val SENTINEL = HapticPattern(
            timings = longArrayOf(0L, 30L, 60L, 30L, 60L, 30L),
            amplitudes = intArrayOf(0, 70, 0, 70, 0, 70),
        )

        /** Silence. GHOST. The absence is the signal. */
        val GHOST = HapticPattern(
            timings = longArrayOf(0L),
            amplitudes = intArrayOf(0),
        )

        /** Long low pulse. DREAM. */
        val DREAM = HapticPattern(
            timings = longArrayOf(0L, 200L),
            amplitudes = intArrayOf(0, 30),
        )

        /** Rare identifying pattern. SHADOW. */
        val SHADOW = HapticPattern(
            timings = longArrayOf(0L, 20L, 40L, 20L, 80L, 40L),
            amplitudes = intArrayOf(0, 40, 0, 40, 0, 60),
        )

        fun forState(state: SystemState): HapticPattern = when (state) {
            SystemState.FOCUS -> FOCUS
            SystemState.DIALOGUE -> DIALOGUE
            SystemState.SENTINEL -> SENTINEL
            SystemState.GHOST -> GHOST
            SystemState.DREAM -> DREAM
            SystemState.SHADOW -> SHADOW
        }
    }
}
