package local.phantom.companion.core.predict

/**
 * Tier 15-F — conviction score the operator attaches to a decision
 * at the moment of recording it. The slider is 1..10 to match the
 * existing Decision Diary spec.
 */
data class ConvictionScore(
    val decisionId: String,
    val score: Int,
    val recordedAtMs: Long,
) {
    init {
        require(score in 1..10) { "Conviction score must be 1..10, got $score" }
    }
}

/**
 * Tier 15-F — when to ask "ti був на 9 певен — як справи?".
 */
enum class ConvictionMilestone(val daysAfter: Int) {
    DAYS_90(90),
    DAYS_180(180),
    DAYS_365(365),
}
