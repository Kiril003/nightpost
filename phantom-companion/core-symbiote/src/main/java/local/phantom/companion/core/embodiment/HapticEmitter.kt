package local.phantom.companion.core.embodiment

/**
 * Abstracts the physical actuator. The real implementation wraps
 * [android.os.VibratorManager]; tests inject a fake that records
 * every emission so the schedule is observable without a phone.
 */
interface HapticEmitter {
    fun emit(pattern: HapticPattern)
    fun cancel()
}

class RecordingHapticEmitter : HapticEmitter {
    val history: MutableList<HapticPattern> = mutableListOf()
    var cancelled: Int = 0
        private set

    override fun emit(pattern: HapticPattern) {
        history += pattern
    }

    override fun cancel() {
        cancelled += 1
    }
}

class NullHapticEmitter : HapticEmitter {
    override fun emit(pattern: HapticPattern) = Unit
    override fun cancel() = Unit
}
