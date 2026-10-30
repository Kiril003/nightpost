package local.phantom.companion.core.data.reminders

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: ReminderEntity)

    @Update
    suspend fun update(reminder: ReminderEntity)

    @Query("SELECT * FROM reminders ORDER BY trigger_at_ms ASC")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE fired = 0 ORDER BY trigger_at_ms ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<ReminderEntity>

    /** Live read for the reminder card — re-emits on every reminder write. */
    @Query("SELECT * FROM reminders WHERE fired = 0 ORDER BY trigger_at_ms ASC LIMIT :limit")
    fun pendingFlow(limit: Int): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE fired = 0 AND trigger_at_ms <= :nowMs ORDER BY trigger_at_ms ASC")
    suspend fun due(nowMs: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: String): ReminderEntity?

    @Query("UPDATE reminders SET fired = 1 WHERE id = :id")
    suspend fun markFired(id: String): Int

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM reminders")
    suspend fun count(): Int
}
