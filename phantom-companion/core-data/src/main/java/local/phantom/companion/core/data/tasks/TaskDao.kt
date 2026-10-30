package local.phantom.companion.core.data.tasks

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("SELECT * FROM tasks ORDER BY done ASC, priority DESC, position ASC")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE list_name = :listName ORDER BY done ASC, priority DESC, position ASC")
    suspend fun byList(listName: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE done = 0 ORDER BY priority DESC, due_ms IS NULL, due_ms ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<TaskEntity>

    /** Live reads for the task-list card — re-emit on every setDone/insert. */
    @Query("SELECT * FROM tasks WHERE list_name = :listName ORDER BY done ASC, priority DESC, position ASC")
    fun byListFlow(listName: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE done = 0 ORDER BY priority DESC, due_ms IS NULL, due_ms ASC LIMIT :limit")
    fun pendingFlow(limit: Int): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: String): TaskEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM tasks WHERE list_name = :listName")
    suspend fun maxPosition(listName: String): Int

    @Query("UPDATE tasks SET done = :done, completed_at = :completedAt WHERE id = :id")
    suspend fun setDone(id: String, done: Boolean, completedAt: Long?): Int

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun count(): Int
}
