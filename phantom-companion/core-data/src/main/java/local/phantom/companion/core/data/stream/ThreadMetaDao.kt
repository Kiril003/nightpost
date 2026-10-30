package local.phantom.companion.core.data.stream

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreadMetaDao {

    /** Idempotent upsert. New threads get inserted; existing threads have last_active_at refreshed. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: ThreadMetaEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(meta: ThreadMetaEntity): Long

    @Query("DELETE FROM threads_meta WHERE id = :id")
    suspend fun delete(id: String)

    /** Most-recent-first ordering — drives the ThreadRail's left-to-right pill order. */
    @Query("SELECT * FROM threads_meta ORDER BY last_active_at DESC")
    fun observeAll(): Flow<List<ThreadMetaEntity>>

    @Query("SELECT * FROM threads_meta ORDER BY last_active_at DESC")
    suspend fun listAll(): List<ThreadMetaEntity>

    @Query("SELECT * FROM threads_meta WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ThreadMetaEntity?

    @Query("UPDATE threads_meta SET last_active_at = :ts WHERE id = :id")
    suspend fun touch(id: String, ts: Long)

    @Query("SELECT COUNT(*) FROM threads_meta")
    suspend fun count(): Int
}
