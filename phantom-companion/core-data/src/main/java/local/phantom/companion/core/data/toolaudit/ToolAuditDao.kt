package local.phantom.companion.core.data.toolaudit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ToolAuditDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ToolAuditEntity)

    /**
     * Найновіші перші. `rowid` у сортуванні розрізняє виклики, що лягли в ту
     * саму мілісекунду — агентна петля робить їх пачками.
     */
    @Query(
        """
        SELECT * FROM tool_audit
        ORDER BY timestamp DESC, rowid DESC
        LIMIT :limit
        """
    )
    suspend fun recent(limit: Int): List<ToolAuditEntity>

    /**
     * Лишає рівно [keep] найновіших рядків, решту видаляє. Те саме сортування,
     * що і в [recent]: інакше обрізання й читання розійшлися б на однакових
     * мітках часу і зникав би не той рядок, що видно останнім.
     */
    @Query(
        """
        DELETE FROM tool_audit WHERE id NOT IN (
            SELECT id FROM tool_audit ORDER BY timestamp DESC, rowid DESC LIMIT :keep
        )
        """
    )
    suspend fun pruneToNewest(keep: Int)
}
