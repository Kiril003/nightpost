package local.phantom.companion.core.data.vault

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_entries")
    fun getAll(): Flow<List<VaultEntity>>

    @Query("SELECT * FROM vault_entries WHERE id = :id")
    suspend fun getById(id: String): VaultEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: VaultEntity)

    @Query("DELETE FROM vault_entries WHERE id = :id")
    suspend fun delete(id: String)
}
