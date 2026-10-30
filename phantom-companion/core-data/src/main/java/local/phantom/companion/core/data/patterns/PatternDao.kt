package local.phantom.companion.core.data.patterns

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Phase 3 W4-7 — DAO for [PatternEntity]. The detector is the only
 * writer; the InsightCard surface is the only reader.
 *
 * Upsert path: detector calls [findByTrigger] to find an existing
 * bucket (unique on (trigger_kind, trigger_value)) and either
 * inserts a fresh row or [bumpOccurrence]'s the existing one.
 */
@Dao
interface PatternDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PatternEntity): Long

    @Query(
        "SELECT * FROM patterns " +
            "WHERE trigger_kind = :kind AND trigger_value = :value " +
            "LIMIT 1",
    )
    suspend fun findByTrigger(kind: String, value: String): PatternEntity?

    /** Increment occurrence_count + bump last_observed_at + recompute confidence. */
    @Query(
        "UPDATE patterns SET occurrence_count = occurrence_count + 1, " +
            "last_observed_at = :nowMs, " +
            "confidence_score = MIN(1.0, (occurrence_count + 1) / 5.0) " +
            "WHERE id = :id",
    )
    suspend fun bumpOccurrence(id: String, nowMs: Long)

    @Query("UPDATE patterns SET suppressed = 1 WHERE id = :id")
    suspend fun suppress(id: String)

    /** Patterns the detector should surface: count >= threshold AND not suppressed. */
    @Query(
        "SELECT * FROM patterns " +
            "WHERE occurrence_count >= :threshold " +
            "  AND suppressed = 0 " +
            "  AND last_observed_at >= :sinceMs " +
            "ORDER BY occurrence_count DESC, last_observed_at DESC",
    )
    suspend fun pickActionable(threshold: Int, sinceMs: Long): List<PatternEntity>

    /** Drop rows that haven't been observed in the rolling window. */
    @Query("DELETE FROM patterns WHERE last_observed_at < :olderThanMs")
    suspend fun cleanupStale(olderThanMs: Long): Int

    @Query("SELECT COUNT(*) FROM patterns")
    suspend fun totalCount(): Int

    @Query("SELECT * FROM patterns ORDER BY last_observed_at DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<PatternEntity>
}
