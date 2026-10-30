package local.phantom.companion.core.data.patterns

/**
 * Phase 3 W4-7 — thin façade over [PatternDao]. Mirrors the W4-5 /
 * W4-6 repository pattern so the [PatternDetector] stays testable
 * with a fake repository.
 */
class PatternRepository(
    private val dao: PatternDao,
) {
    suspend fun findByTrigger(kind: String, value: String): PatternEntity? =
        dao.findByTrigger(kind, value)

    suspend fun insert(entity: PatternEntity): Long = dao.insert(entity)

    suspend fun bumpOccurrence(id: String, nowMs: Long) = dao.bumpOccurrence(id, nowMs)

    suspend fun suppress(id: String) = dao.suppress(id)

    suspend fun pickActionable(threshold: Int, sinceMs: Long): List<PatternEntity> =
        dao.pickActionable(threshold, sinceMs)

    suspend fun cleanupStale(olderThanMs: Long): Int = dao.cleanupStale(olderThanMs)

    suspend fun totalCount(): Int = dao.totalCount()

    suspend fun recent(limit: Int = 20): List<PatternEntity> = dao.recent(limit)
}
