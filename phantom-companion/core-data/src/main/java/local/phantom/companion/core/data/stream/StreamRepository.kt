package local.phantom.companion.core.data.stream

import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/**
 * W2-1 — façade over [StreamItemDao] for the feature layer.
 *
 * Wraps the DAO so the StreamViewModel doesn't import Room types
 * directly, and centralises the «assign next seq_no per thread»
 * sequencing that every insert path needs. Per-thread sequencing
 * lives here (not on the entity) because two writers racing on the
 * same thread must agree on order, and SQLite's auto-increment is
 * global, not per-thread.
 */
class StreamRepository(
    private val dao: StreamItemDao,
) {
    /**
     * Once an id is assigned a seq_no, it's remembered here for the
     * life of the process. [StreamPersistenceWriter] re-maps every
     * live [StreamItem][local.phantom.companion.feature.stream.domain.StreamItem]
     * to a fresh entity on every debounce flush — including ones
     * already on disk — and [StreamItemEntityMapper][local.phantom.companion.feature.stream.persist.StreamItemEntityMapper]
     * always stamps `seqNo = -1` on those. Without this cache,
     * [upsertAll] would hand a streaming item a brand-new (larger)
     * seq_no on every re-flush, so its position kept sliding to the
     * end of the thread each time a token arrived — scrambling
     * on-disk order for anything that streamed. Restoring history
     * hydrates this map too (see [byThread]/[lastN] etc.) so ids
     * read back from a previous session are recognised immediately.
     */
    private val assignedSeqNo = ConcurrentHashMap<String, Long>()

    /**
     * Insert [item] with an auto-assigned per-thread seq_no when the
     * caller passed `seqNo = -1`, otherwise honour the caller's value
     * (used by the cold-launch import path which already has the
     * order baked in).
     */
    suspend fun upsert(item: StreamItemEntity): StreamItemEntity {
        val resolved = if (item.seqNo < 0L) {
            val seqNo = assignedSeqNo[item.id] ?: (dao.maxSeqNoForThread(item.threadId) + 1L)
            item.copy(seqNo = seqNo)
        } else item
        assignedSeqNo[item.id] = resolved.seqNo
        dao.insertOrUpdate(resolved)
        return resolved
    }

    /**
     * Bulk upsert. Any item with `seqNo = -1` gets the next free
     * per-thread value — reusing this id's previously assigned seq_no
     * if it has one, so re-flushing a still-streaming item never
     * moves it. New ids sharing a thread within the same batch are
     * sequenced against each other via [highWater], not just against
     * what's already on disk.
     *
     * [highWater] is a MAX, never a plain assignment, and is always
     * seeded from the on-disk max for the thread. A batch routinely
     * mixes re-written old rows (a tool-trace widget flipping
     * running → done keeps its original low seq_no) with brand-new
     * ones; letting a reused low seq_no become the baseline made the
     * next new item collide with rows already on disk, and it
     * rendered in the middle of old history instead of at the end.
     */
    suspend fun upsertAll(items: List<StreamItemEntity>) {
        if (items.isEmpty()) return
        val highWater = mutableMapOf<String, Long>()
        val resolved = items.map { item ->
            val onDiskMax = highWater.getOrPut(item.threadId) {
                dao.maxSeqNoForThread(item.threadId)
            }
            val reused = if (item.seqNo >= 0L) item.seqNo else assignedSeqNo[item.id]
            val seqNo = reused ?: (onDiskMax + 1L)
            highWater[item.threadId] = maxOf(onDiskMax, seqNo)
            assignedSeqNo[item.id] = seqNo
            item.copy(seqNo = seqNo)
        }
        dao.insertOrUpdate(resolved)
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun deleteByThreadId(threadId: String) = dao.deleteByThreadId(threadId)

    /** Вилучає всю родину рядків з таким префіксом id. Повертає скільки. */
    suspend fun deleteByIdPrefix(prefix: String): Int {
        val removed = dao.deleteByIdPrefix(escapeLike(prefix))
        assignedSeqNo.keys.removeIf { it.startsWith(prefix) }
        return removed
    }

    suspend fun byThread(threadId: String, limit: Int = 100): List<StreamItemEntity> =
        dao.byThread(threadId, limit).also(::hydrateSeqNoCache)

    fun byThreadFlow(threadId: String, limit: Int = 100): Flow<List<StreamItemEntity>> =
        dao.byThreadFlow(threadId, limit)

    suspend fun lastN(limit: Int): List<StreamItemEntity> =
        dao.lastN(limit).also(::hydrateSeqNoCache)

    suspend fun lastNInThread(threadId: String, limit: Int): List<StreamItemEntity> =
        dao.lastNInThread(threadId, limit).also(::hydrateSeqNoCache)

    /** Seed [assignedSeqNo] from rows already on disk, so an id restored
     *  from a previous session is recognised the first time it flows
     *  back through [upsertAll] (e.g. an edit or a re-flush). */
    private fun hydrateSeqNoCache(rows: List<StreamItemEntity>) {
        rows.forEach { assignedSeqNo[it.id] = it.seqNo }
    }

    suspend fun searchText(query: String, limit: Int = 50): List<StreamItemEntity> =
        dao.searchText(escapeLike(query), limit)

    /** LIKE wildcards in operator text (`%`, `_`) are literals, not patterns —
     *  searching «100%» must not match every row. Paired with ESCAPE '\'. */
    private fun escapeLike(q: String): String =
        q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** W2-5 — daily TTL job calls this with `now - retentionDays * 86_400_000`. */
    suspend fun cleanupBefore(cutoffMs: Long): Int = dao.cleanupBefore(cutoffMs)

    /**
     * One decay sweep. Returns what it did, so the diagnostics screen can
     * say «стиснуто 214 карток» instead of a silent number of deletions.
     */
    suspend fun decay(
        nowMs: Long,
        policy: ChatDecay.Policy = ChatDecay.Policy(),
        batch: Int = 500,
    ): DecayReport {
        val cutoff = nowMs - policy.verbatimDays * 86_400_000L
        val rows = dao.decayCandidates(cutoff, batch)
        var shrunk = 0
        var dropped = 0
        rows.forEach { row ->
            when (ChatDecay.fateOf(row, nowMs, policy)) {
                ChatDecay.Fate.SHRINK -> {
                    dao.shrinkPayload(row.id, ChatDecay.digestOf(row.widgetType, row.widgetPayload))
                    shrunk++
                }
                ChatDecay.Fate.DROP -> {
                    dao.delete(row.id)
                    dropped++
                }
                ChatDecay.Fate.KEEP -> Unit
            }
        }
        return DecayReport(scanned = rows.size, shrunk = shrunk, dropped = dropped)
    }

    data class DecayReport(val scanned: Int, val shrunk: Int, val dropped: Int)

    suspend fun count(): Int = dao.count()

    suspend fun updatePinned(id: String, pinned: Boolean) = dao.updatePinned(id, pinned)

    suspend fun pinnedTexts(limit: Int = 20): List<StreamItemEntity> = dao.pinnedTexts(limit)

    suspend fun distinctTagBlobs(): List<String> = dao.distinctTagBlobs()

    suspend fun updateEmbedding(id: String, embedding: FloatArray) =
        dao.updateEmbedding(id, embedding)

    suspend fun embeddedTexts(limit: Int = 2000): List<StreamItemEntity> =
        dao.embeddedTexts(limit)
}
