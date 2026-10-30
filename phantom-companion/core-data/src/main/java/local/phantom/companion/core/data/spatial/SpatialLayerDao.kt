package local.phantom.companion.core.data.spatial

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SpatialLayerDao {

    /** All layers, newest first — the map observes this live. */
    @Query("SELECT * FROM spatial_layers ORDER BY created_at DESC")
    fun observeAll(): Flow<List<SpatialLayerEntity>>

    @Query("SELECT * FROM spatial_layers ORDER BY created_at DESC")
    suspend fun getAll(): List<SpatialLayerEntity>

    @Query(
        "SELECT * FROM spatial_layers WHERE owner_thread_id = :threadId " +
            "ORDER BY created_at DESC",
    )
    suspend fun getForThread(threadId: String): List<SpatialLayerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(layer: SpatialLayerEntity)

    @Query("UPDATE spatial_layers SET visible = :visible WHERE id = :id")
    suspend fun setVisible(id: String, visible: Boolean)

    @Query("DELETE FROM spatial_layers WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM spatial_layers")
    suspend fun deleteAll(): Int

    @Query("DELETE FROM spatial_layers WHERE owner_thread_id = :threadId")
    suspend fun deleteForThread(threadId: String): Int

    @Query("DELETE FROM spatial_layers WHERE owner_thread_id = :threadId AND origin = :origin")
    suspend fun deleteForThreadOrigin(threadId: String, origin: String): Int

    @Query("DELETE FROM spatial_layers WHERE origin = :origin")
    suspend fun deleteByOrigin(origin: String): Int

    /** TTL sweep — live world layers (transit/aircraft/hazard) self-expire. */
    @Query("DELETE FROM spatial_layers WHERE expires_at IS NOT NULL AND expires_at <= :nowMs")
    suspend fun deleteExpired(nowMs: Long): Int
}
