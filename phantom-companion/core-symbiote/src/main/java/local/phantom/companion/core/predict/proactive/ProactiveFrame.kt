package local.phantom.companion.core.predict.proactive

/** Compact, model-ready snapshot of the operator's situation at one instant. */
data class ProactiveFrame(
    val epochMs: Long,
    val signals: Map<String, String>,   // e.g. "gnss_trust"->"SPOOFED", "route"->"12 min, jam ahead"
    val salienceHint: Int,              // cheap 0..100 change-magnitude from the producer; NOT the decision to speak
)
