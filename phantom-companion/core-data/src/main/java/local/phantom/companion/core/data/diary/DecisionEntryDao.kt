package local.phantom.companion.core.data.diary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DecisionEntryDao {

    /** Newest first — the diary surface scrolls top-down chronologically. */
    @Query("SELECT * FROM decision_entries ORDER BY createdAt DESC")
    fun getAll(): Flow<List<DecisionEntry>>

    @Query("SELECT * FROM decision_entries WHERE id = :id")
    suspend fun getById(id: String): DecisionEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DecisionEntry)

    @Query("DELETE FROM decision_entries WHERE id = :id")
    suspend fun delete(id: String)
}
