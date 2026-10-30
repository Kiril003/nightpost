package local.phantom.companion.core.data.calendar

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CalendarEventEntity)

    @Update
    suspend fun update(event: CalendarEventEntity)

    @Query("SELECT * FROM calendar_events ORDER BY start_ms ASC")
    suspend fun getAll(): List<CalendarEventEntity>

    @Query("SELECT * FROM calendar_events WHERE start_ms >= :fromMs AND start_ms < :toMs ORDER BY start_ms ASC")
    suspend fun inRange(fromMs: Long, toMs: Long): List<CalendarEventEntity>

    @Query("SELECT * FROM calendar_events WHERE start_ms >= :fromMs ORDER BY start_ms ASC LIMIT :limit")
    suspend fun upcoming(fromMs: Long, limit: Int): List<CalendarEventEntity>

    /** Live read for the agenda card — re-emits on every calendar write. */
    @Query("SELECT * FROM calendar_events WHERE start_ms >= :fromMs ORDER BY start_ms ASC LIMIT :limit")
    fun upcomingFlow(fromMs: Long, limit: Int): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE id = :id")
    suspend fun byId(id: String): CalendarEventEntity?

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM calendar_events")
    suspend fun count(): Int
}
