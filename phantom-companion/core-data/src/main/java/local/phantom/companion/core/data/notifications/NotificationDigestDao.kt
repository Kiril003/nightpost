package local.phantom.companion.core.data.notifications

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Phase 3 W4-5 — DAO for the notification digest queue.
 *
 * Insert path: classifier marks a notification as Routine →
 * service writes a [NotificationDigestEntity] with `delivered = 0`.
 *
 * Read path: scheduler at 9 am picks rows where
 * `delivered = 0 AND delivered_at >= cutoff` (cutoff = `now - 24h`),
 * groups by `package_name` for the rendered digest line, then calls
 * [markDelivered] with the row IDs once the Stream Notice landed.
 */
@Dao
interface NotificationDigestDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: NotificationDigestEntity): Long

    /** All pending (not-yet-delivered) routine notifications since [cutoffMs]. */
    @Query(
        "SELECT * FROM notification_digest " +
            "WHERE delivered = 0 AND delivered_at >= :cutoffMs " +
            "ORDER BY delivered_at ASC",
    )
    suspend fun pickPendingSince(cutoffMs: Long): List<NotificationDigestEntity>

    /** Flip the delivered flag for the given ids. Called after Stream renders the digest. */
    @Query("UPDATE notification_digest SET delivered = 1 WHERE id IN (:ids)")
    suspend fun markDelivered(ids: List<String>)

    /**
     * Межа життя рядка. Навмисно без `delivered`: невиданий рядок, старший за
     * вікно вибірки, не потрапить у жоден дайджест уже ніколи — і саме він
     * лишався тут назавжди, поки прибирання питало про доставку.
     */
    @Query("DELETE FROM notification_digest WHERE delivered_at < :olderThanMs")
    suspend fun purgeOlderThan(olderThanMs: Long): Int

    /** Counter for tests + Settings → Діагностика. */
    @Query("SELECT COUNT(*) FROM notification_digest WHERE delivered = 0")
    suspend fun pendingCount(): Int
}
