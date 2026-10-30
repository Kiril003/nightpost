package local.phantom.companion.core.context

data class ContextSnapshot(
    val timestampMs: Long,
    val state: SystemState,
    val confidence: Float,           // 0..1
    val rationale: List<String>,     // human-readable reasons (debug/UI)
)
