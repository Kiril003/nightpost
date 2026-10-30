package local.phantom.companion.core.data.lifelog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for managing vector-embedded Life Log entries.
 */
@Dao
interface LifeLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: LifeLogEntryEntity)

    @Query("SELECT * FROM life_log_entries ORDER BY timestamp_ms DESC")
    suspend fun getAllEntries(): List<LifeLogEntryEntity>

    @Query("SELECT * FROM life_log_entries WHERE timestamp_ms >= :sinceMs ORDER BY timestamp_ms DESC")
    suspend fun getEntriesSince(sinceMs: Long): List<LifeLogEntryEntity>

    @Query("DELETE FROM life_log_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    /** QA-fix 2026-07-17 — flush of a whole event family (the
     *  ScreenSnapshot firehose rows; see AppContainer's archive gate). */
    @Query("DELETE FROM life_log_entries WHERE event_type = :eventType")
    suspend fun deleteByType(eventType: String): Int

    @Query("DELETE FROM life_log_entries")
    suspend fun clear()
}
