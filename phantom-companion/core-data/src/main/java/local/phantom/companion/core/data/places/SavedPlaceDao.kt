package local.phantom.companion.core.data.places

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedPlaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(place: SavedPlaceEntity)

    @Update
    suspend fun update(place: SavedPlaceEntity)

    @Query("SELECT * FROM saved_places ORDER BY last_used_ms DESC")
    suspend fun getAll(): List<SavedPlaceEntity>

    @Query("SELECT * FROM saved_places WHERE category = :category ORDER BY last_used_ms DESC")
    suspend fun byCategory(category: String): List<SavedPlaceEntity>

    /** Live reads for the places card — re-emit on every save/remove. */
    @Query("SELECT * FROM saved_places ORDER BY last_used_ms DESC")
    fun allFlow(): Flow<List<SavedPlaceEntity>>

    @Query("SELECT * FROM saved_places WHERE category = :category ORDER BY last_used_ms DESC")
    fun byCategoryFlow(category: String): Flow<List<SavedPlaceEntity>>

    @Query("SELECT * FROM saved_places WHERE label = :label COLLATE NOCASE LIMIT 1")
    suspend fun byLabel(label: String): SavedPlaceEntity?

    @Query("SELECT * FROM saved_places WHERE id = :id")
    suspend fun byId(id: String): SavedPlaceEntity?

    @Query("DELETE FROM saved_places WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM saved_places")
    suspend fun count(): Int
}
