package local.phantom.companion.core.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** Access to the durable long-term fact store (Tier 2). */
@Dao
interface MemoryFactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: MemoryFactEntity)

    @Update
    suspend fun update(fact: MemoryFactEntity)

    @Query("SELECT * FROM memory_facts")
    suspend fun getAll(): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE category = :category")
    suspend fun getByCategory(category: String): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts ORDER BY last_confirmed_ms DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE entity_id = :entityId ORDER BY last_confirmed_ms DESC")
    suspend fun byEntity(entityId: String): List<MemoryFactEntity>

    @Query("SELECT COUNT(*) FROM memory_facts")
    suspend fun count(): Int

    @Query("UPDATE memory_facts SET confirmed = :confirmed WHERE id = :id")
    suspend fun setConfirmed(id: String, confirmed: Int)

    @Query("UPDATE memory_facts SET entity_id = :entityId WHERE id = :id")
    suspend fun setEntity(id: String, entityId: String?)

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM memory_facts WHERE entity_id = :entityId")
    suspend fun deleteByEntity(entityId: String)

    @Query("DELETE FROM memory_facts WHERE confidence < :floor")
    suspend fun pruneBelow(floor: Float): Int

    @Query("DELETE FROM memory_facts")
    suspend fun clear()
}
