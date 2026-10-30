package local.phantom.companion.core.data.stream

import kotlinx.coroutines.flow.Flow

/**
 * W2-4 — façade for the ThreadRail's persistence layer.
 *
 * Centralises the bootstrap rule («Зараз» thread is auto-created on
 * first launch) and the «touch on activity» semantics so the
 * ThreadRail's order updates organically as the operator chats.
 *
 * TODO(Phase 3): wire ThreadRail UI to consume [observeAll] — the
 * Composable in `:feature-stream` still reads the in-memory
 * StreamViewModel.threads map, so persisted thread order does not
 * survive cold launch even though the table does. The repository is
 * ready; the UI just hasn't been pointed at it yet.
 */
class ThreadMetaRepository(
    private val dao: ThreadMetaDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * Ensure a default «Зараз» thread exists — call once on app
     * startup so the rail is never empty. Idempotent across launches.
     */
    /**
     * @param defaultLabel підпис гілки МОВОЮ ЛЮДИНИ. Типового значення немає
     *   навмисно: до 31.08 тут стояв `"Зараз"` жорстким літералом, і на
     *   англійському телефоні головний екран месенджера показував українське
     *   слово між англійськими — мішанку двох мов у першому ж рядку.
     *
     *   Шар даних не має мови. Він не бачить локалі, не має `Context` і не
     *   може знати, якою мовою читає людина, — тож будь-який підпис, вигаданий
     *   тут, правильний рівно для однієї країни. Обовʼязковий параметр робить
     *   це неможливим за побудовою: викликач має `Context`, отже має й мову.
     */
    suspend fun ensureDefault(
        defaultLabel: String,
        defaultId: String = DEFAULT_ID,
    ) {
        dao.insertIfAbsent(
            ThreadMetaEntity(
                id = defaultId,
                label = defaultLabel,
                lastActiveAt = nowMs(),
                kind = KIND_SYSTEM,
            ),
        )
    }

    suspend fun upsert(id: String, label: String, kind: String = KIND_TASK) {
        dao.upsert(ThreadMetaEntity(id = id, label = label, lastActiveAt = nowMs(), kind = kind))
    }

    /** R2.5 — create a new operator space; returns its generated id. */
    suspend fun createSpace(label: String, kind: String): String {
        val id = "space-" + java.util.UUID.randomUUID().toString().take(8)
        upsert(id = id, label = label, kind = kind)
        return id
    }

    /** «touch» the thread to bump its last-active timestamp (re-orders the rail). */
    suspend fun touch(id: String) = dao.touch(id, nowMs())

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun listAll(): List<ThreadMetaEntity> = dao.listAll()

    suspend fun byId(id: String): ThreadMetaEntity? = dao.byId(id)

    fun observeAll(): Flow<List<ThreadMetaEntity>> = dao.observeAll()

    suspend fun count(): Int = dao.count()

    companion object {
        const val DEFAULT_ID: String = "now"

        const val KIND_TASK: String = "task"
        const val KIND_PERSON: String = "person"
        const val KIND_DEVICE: String = "device"
        const val KIND_SYSTEM: String = "system"
    }
}
