package local.phantom.companion.core.data.tasks

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One task / checklist item. `list_name` groups items into a checklist. */
@Entity(
    tableName = "tasks",
    indices = [Index("list_name"), Index("done"), Index("due_ms")],
)
data class TaskEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "notes") val notes: String?,
    @ColumnInfo(name = "done") val done: Boolean,
    /** 0 normal · 1 high · 2 urgent. */
    @ColumnInfo(name = "priority") val priority: Int,
    @ColumnInfo(name = "due_ms") val dueMs: Long?,
    @ColumnInfo(name = "list_name") val listName: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
)
