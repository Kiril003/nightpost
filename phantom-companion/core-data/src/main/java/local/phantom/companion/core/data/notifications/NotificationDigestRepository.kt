package local.phantom.companion.core.data.notifications

/**
 * Phase 3 W4-5 — thin façade over [NotificationDigestDao] so service +
 * scheduler call sites stay testable with a fake repository while the
 * production wiring binds the real DAO.
 *
 * The [enqueue] / [pickPendingSince] / [markDelivered] split mirrors
 * the W2-1 stream-items repository pattern — service writes, scheduler
 * reads + flushes.
 */
class NotificationDigestRepository(
    private val dao: NotificationDigestDao,
) {

    suspend fun enqueue(entity: NotificationDigestEntity): Long = dao.insert(entity)

    suspend fun pickPendingSince(cutoffMs: Long): List<NotificationDigestEntity> =
        dao.pickPendingSince(cutoffMs)

    suspend fun markDelivered(ids: List<String>) {
        if (ids.isNotEmpty()) dao.markDelivered(ids)
    }

    suspend fun purgeOlderThan(olderThanMs: Long): Int = dao.purgeOlderThan(olderThanMs)

    suspend fun pendingCount(): Int = dao.pendingCount()
}
