package local.phantom.companion.core.data.tasks

import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Tasks + checklists the phantom can create, list and tick off. */
class TaskStore(private val dao: TaskDao) {

    suspend fun add(
        title: String,
        listName: String = "inbox",
        priority: Int = 0,
        dueMs: Long? = null,
        notes: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val clean = title.trim()
        if (clean.isEmpty()) return ""
        val list = listName.trim().ifBlank { "inbox" }
        val id = UUID.randomUUID().toString()
        dao.insert(
            TaskEntity(
                id = id,
                title = clean,
                notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                done = false,
                priority = priority.coerceIn(0, 2),
                dueMs = dueMs,
                listName = list,
                position = dao.maxPosition(list) + 1,
                createdAt = nowMs,
                completedAt = null,
            ),
        )
        return id
    }

    suspend fun list(listName: String? = null): List<TaskEntity> =
        if (listName.isNullOrBlank()) dao.getAll() else dao.byList(listName.trim())

    suspend fun pending(limit: Int = 20): List<TaskEntity> = dao.pending(limit)

    /** Live list for the task-list card; null [listName] = all open tasks. */
    fun liveFlow(listName: String? = null, limit: Int = 30): Flow<List<TaskEntity>> =
        if (listName.isNullOrBlank()) dao.pendingFlow(limit) else dao.byListFlow(listName.trim())

    /** Mark done/undone; returns true if the task existed. */
    suspend fun setDone(id: String, done: Boolean = true, nowMs: Long = System.currentTimeMillis()): Boolean =
        dao.setDone(id, done, if (done) nowMs else null) > 0

    suspend fun get(id: String): TaskEntity? = dao.byId(id)

    suspend fun remove(id: String): Boolean {
        val exists = dao.byId(id) != null
        if (exists) dao.deleteById(id)
        return exists
    }

    suspend fun count(): Int = dao.count()
}
