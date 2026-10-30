package local.phantom.companion.core.context

data class ContextInput(
    val nowMs: Long,
    val localHour: Int,                  // 0..23
    val localDayOfWeek: Int,             // 1..7 (Mon=1)
    val accelMagnitude: Float? = null,   // m/s^2 norm minus gravity (~0 still, ~1+ moving)
    val micRmsDb: Float? = null,         // -100..0
    val batteryLevel: Int? = null,       // 0..100
    val isCharging: Boolean = false,
    val activeAppPackage: String? = null, // e.g. "com.instagram.android"
    /** Phase 1-H — sounds detected by YAMNet since the last tick. */
    val detectedSounds: List<String> = emptyList(),
)
