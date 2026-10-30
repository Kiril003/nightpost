package local.phantom.companion.core.predict

/**
 * Tier 15-F — given a set of [ConvictionScore]s and the current time,
 * return the milestone reviews that should fire right now. Each
 * decision can fire at most once per milestone; the caller tracks
 * "already fired" state so this function stays referentially
 * transparent.
 */
class ConvictionScheduler(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    data class DueReview(
        val decisionId: String,
        val milestone: ConvictionMilestone,
    )

    fun due(
        scores: Collection<ConvictionScore>,
        alreadyFired: Set<Pair<String, ConvictionMilestone>>,
    ): List<DueReview> {
        val now = clock()
        val out = mutableListOf<DueReview>()
        for (s in scores) {
            for (m in ConvictionMilestone.entries) {
                val due = s.recordedAtMs + m.daysAfter * MS_PER_DAY
                if (now >= due && (s.decisionId to m) !in alreadyFired) {
                    out += DueReview(s.decisionId, m)
                }
            }
        }
        return out
    }

    companion object {
        private const val MS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
