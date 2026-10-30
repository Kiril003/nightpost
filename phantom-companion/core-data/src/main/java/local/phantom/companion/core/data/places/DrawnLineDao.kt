package local.phantom.companion.core.data.places

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DrawnLineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(line: DrawnLineEntity)

    @Query("SELECT * FROM drawn_lines ORDER BY created_at DESC")
    suspend fun getAll(): List<DrawnLineEntity>

    @Query("SELECT * FROM drawn_lines ORDER BY created_at DESC")
    fun allFlow(): Flow<List<DrawnLineEntity>>

    @Query("SELECT * FROM drawn_lines WHERE id = :id")
    suspend fun byId(id: String): DrawnLineEntity?

    @Query("SELECT label FROM drawn_lines")
    suspend fun labels(): List<String>

    @Query("DELETE FROM drawn_lines WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT COUNT(*) FROM drawn_lines")
    suspend fun count(): Int
}
