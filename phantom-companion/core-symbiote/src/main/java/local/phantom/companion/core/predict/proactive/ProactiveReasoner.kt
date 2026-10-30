package local.phantom.companion.core.predict.proactive

data class ProactiveUtterance(val say: String, val toolCall: String? = null, val urgency: Urgency)

enum class Urgency { AMBIENT, NOTABLE, CRITICAL }

/**
 * The sole judge of whether — and what — to say. The real impl is the on-device LLM;
 * the engine never decides topics, only battery hygiene, pacing and anti-repetition.
 */
fun interface ProactiveReasoner {
    // Returns an utterance to deliver unprompted, or null to STAY SILENT.
    suspend fun judge(frame: ProactiveFrame, recentDialogue: String, recentlySaid: List<String>): ProactiveUtterance?
}
