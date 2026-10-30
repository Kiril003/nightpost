package local.phantom.companion.core.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** Доступ до людей і речей, які памʼять уже знає. */
@Dao
interface MemoryEntityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MemoryEntityEntity)

    @Update
    suspend fun update(entity: MemoryEntityEntity)

    @Query("SELECT * FROM memory_entities ORDER BY last_seen_ms DESC")
    suspend fun getAll(): List<MemoryEntityEntity>

    @Query("SELECT * FROM memory_entities WHERE id = :id")
    suspend fun byId(id: String): MemoryEntityEntity?

    @Query("SELECT * FROM memory_entities WHERE name_key = :nameKey")
    suspend fun byNameKey(nameKey: String): List<MemoryEntityEntity>

    @Query("SELECT * FROM memory_entities WHERE peer_id = :peerId LIMIT 1")
    suspend fun byPeer(peerId: String): MemoryEntityEntity?

    @Query("SELECT COUNT(*) FROM memory_entities")
    suspend fun count(): Int

    @Query("DELETE FROM memory_entities WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM memory_entities")
    suspend fun clear()
}
