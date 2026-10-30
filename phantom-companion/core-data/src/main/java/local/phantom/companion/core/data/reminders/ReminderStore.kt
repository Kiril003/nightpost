package local.phantom.companion.core.data.reminders

import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Time-based reminders the phantom sets and the app later surfaces. */
class ReminderStore(private val dao: ReminderDao) {

    suspend fun add(
        text: String,
        triggerAtMs: Long,
        leadMinutes: Int = 0,
        recurrence: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val clean = text.trim()
        if (clean.isEmpty()) return ""
        val id = UUID.randomUUID().toString()
        dao.insert(
            ReminderEntity(
                id = id,
                text = clean,
                triggerAtMs = triggerAtMs,
                leadMinutes = leadMinutes.coerceAtLeast(0),
                recurrence = recurrence?.trim()?.takeIf { it.isNotEmpty() },
                fired = false,
                createdAt = nowMs,
            ),
        )
        return id
    }

    suspend fun pending(limit: Int = 20): List<ReminderEntity> = dao.pending(limit)

    /** Live pending list for the reminder card. */
    fun pendingFlow(limit: Int = 30): Flow<List<ReminderEntity>> = dao.pendingFlow(limit)

    suspend fun due(nowMs: Long = System.currentTimeMillis()): List<ReminderEntity> = dao.due(nowMs)

    suspend fun get(id: String): ReminderEntity? = dao.byId(id)

    suspend fun markFired(id: String): Boolean = dao.markFired(id) > 0

    /** Roll a repeating reminder onto its next occurrence; false when it was one-shot. */
    suspend fun rollToNext(id: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val current = dao.byId(id) ?: return false
        val next = Recurrence.next(current.recurrence, current.triggerAtMs, nowMs) ?: return false
        dao.insert(current.copy(triggerAtMs = next, fired = false))
        return true
    }

    suspend fun remove(id: String): Boolean {
        val exists = dao.byId(id) != null
        if (exists) dao.deleteById(id)
        return exists
    }

    suspend fun count(): Int = dao.count()
}
