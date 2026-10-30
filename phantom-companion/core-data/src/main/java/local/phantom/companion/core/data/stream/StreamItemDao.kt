package local.phantom.companion.core.data.stream

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * W2-1 — DAO for the `stream_items` table.
 *
 * The contract from the Wave 2 plan:
 *  - insertOrUpdate(item)   — upsert via REPLACE conflict strategy
 *  - delete(id)             — single-row removal by primary key
 *  - byThread(threadId, …)  — observable Flow of a thread's items,
 *                             oldest → newest
 *  - lastN(limit)           — last-N items across every thread
 *  - searchText(query, …)   — LIKE-based text search for the future
 *                             Settings → Діагностика surface
 *  - cleanupBefore(cutoff)  — TTL job (W2-5)
 *  - deleteByThreadId(id)   — purge GHOST-mode threads on session end
 */
@Dao
interface StreamItemDao {

    /** Bulk upsert. REPLACE collapses streaming-token rewrites onto the same row id. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(items: List<StreamItemEntity>)

    /** Single-row upsert convenience. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(item: StreamItemEntity)

    @Query("DELETE FROM stream_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM stream_items WHERE thread_id = :threadId")
    suspend fun deleteByThreadId(threadId: String)

    /**
     * Прибирання цілої родини рядків за префіксом id — те, чим
     * `notif-%` (сповіщення чужих застосунків) вилучаються при старті.
     * Повертає кількість вилучених, щоб виклик міг це довести.
     */
    @Query("DELETE FROM stream_items WHERE id LIKE :prefix || '%' ESCAPE '\\'")
    suspend fun deleteByIdPrefix(prefix: String): Int

    /**
     * Cold-launch read path: oldest → newest items in [threadId],
     * capped at [limit]. The renderer only ever shows the most
     * recent ~100; older content stays on disk for the search /
     * Діагностика surfaces.
     */
    @Query(
        """
        SELECT * FROM stream_items
         WHERE thread_id = :threadId
         ORDER BY seq_no ASC
         LIMIT :limit
        """
    )
    suspend fun byThread(threadId: String, limit: Int = 100): List<StreamItemEntity>

    /** Same shape as [byThread] but observable, for the active-thread renderer. */
    @Query(
        """
        SELECT * FROM stream_items
         WHERE thread_id = :threadId
         ORDER BY seq_no ASC
         LIMIT :limit
        """
    )
    fun byThreadFlow(threadId: String, limit: Int = 100): Flow<List<StreamItemEntity>>

    /**
     * Last-N items across every thread, newest → oldest. Used by
     * the AI-history feeder once W2-6 lands so the model sees the
     * tail of the operator's recent interactions.
     */
    @Query(
        """
        SELECT * FROM stream_items
         ORDER BY seq_no DESC
         LIMIT :limit
        """
    )
    suspend fun lastN(limit: Int): List<StreamItemEntity>

    /** Same shape but scoped to a single thread (W2-6 prefers per-thread history). */
    @Query(
        """
        SELECT * FROM stream_items
         WHERE thread_id = :threadId
         ORDER BY seq_no DESC
         LIMIT :limit
        """
    )
    suspend fun lastNInThread(threadId: String, limit: Int): List<StreamItemEntity>

    /**
     * Lowercase-LIKE text search across `text_content`. Used by
     * Settings → Діагностика's «знайти повідомлення» surface
     * (Phase 3-D will replace this with FTS5 once the search
     * volume warrants the extra schema).
     */
    @Query(
        """
        SELECT * FROM stream_items
         WHERE LOWER(text_content) LIKE '%' || LOWER(:query) || '%' ESCAPE '\'
         ORDER BY seq_no DESC
         LIMIT :limit
        """
    )
    suspend fun searchText(query: String, limit: Int = 50): List<StreamItemEntity>

    /**
     * W2-5 retention: deletes any item older than [cutoffMs]. The
     * daily WorkManager job calls this with `now - retentionDays *
     * 86_400_000`. Returns the number of rows deleted so the job
     * can log its work for the Settings → Діагностика surface.
     *
     * Pinned rows are exempt, as in [decayCandidates]. A pin is the
     * operator's only manual override over decay, so retention must
     * not be the one clock that ignores it.
     */
    @Query("DELETE FROM stream_items WHERE created_at < :cutoffMs AND pinned = 0")
    suspend fun cleanupBefore(cutoffMs: Long): Int

    /**
     * How many rows a [cleanupBefore] at the same [cutoffMs] would remove.
     * The predicate is character-for-character the one above, so the number
     * the retention screen promises before a sweep is the number the sweep
     * deletes — the whole point of showing it.
     */
    @Query("SELECT COUNT(*) FROM stream_items WHERE created_at < :cutoffMs AND pinned = 0")
    suspend fun countOlderThan(cutoffMs: Long): Int

    /** Oldest item's wall-clock ms, or null on an empty table. */
    @Query("SELECT MIN(created_at) FROM stream_items")
    suspend fun minCreatedAt(): Long?

    /** Pinned rows — the ones no retention cutoff can ever touch. */
    @Query("SELECT COUNT(*) FROM stream_items WHERE pinned = 1")
    suspend fun pinnedCount(): Int

    /** Decay candidates: everything past the verbatim window, oldest first. */
    @Query(
        """
        SELECT * FROM stream_items
         WHERE created_at < :cutoffMs AND pinned = 0
         ORDER BY created_at ASC
         LIMIT :limit
        """
    )
    suspend fun decayCandidates(cutoffMs: Long, limit: Int): List<StreamItemEntity>

    /** Keeps the row, drops the weight. */
    @Query("UPDATE stream_items SET widget_payload = :digest WHERE id = :id")
    suspend fun shrinkPayload(id: String, digest: String)

    /** Test / Settings helper — total row count. */
    @Query("SELECT COUNT(*) FROM stream_items")
    suspend fun count(): Int

    /** R2.7 — write the final-text embedding blob.
     *
     *  QA-fix 2026-07-17: a FloatArray @Query parameter is a *collection
     *  binding* to Room — it expands into `?,?,?,…` (meant for IN
     *  clauses), so `SET embedding = ?,?,…` was a syntax error and every
     *  embedding write since R2.7 failed; semantic recall ran on an
     *  empty index. The blob must be bound as ONE ByteArray, encoded
     *  exactly like the entity field's [local.phantom.companion.core.data.lifelog.LifeLogConverters]
     *  so [embeddedTexts] reads decode symmetrically. */
    suspend fun updateEmbedding(id: String, embedding: FloatArray) {
        val blob = local.phantom.companion.core.data.lifelog.LifeLogConverters()
            .fromFloatArray(embedding) ?: return
        updateEmbeddingBlob(id, blob)
    }

    @Query("UPDATE stream_items SET embedding = :blob WHERE id = :id")
    suspend fun updateEmbeddingBlob(id: String, blob: ByteArray)

    /** R2.7 — vector-search candidates: finalized texts with an embedding. */
    @Query(
        """
        SELECT * FROM stream_items
         WHERE kind = 'text' AND embedding IS NOT NULL AND streaming = 0
         ORDER BY created_at DESC
         LIMIT :limit
        """
    )
    suspend fun embeddedTexts(limit: Int = 2000): List<StreamItemEntity>

    /** Per-thread max seq_no, used to assign monotonic ids on insert. -1 if no rows. */
    @Query("SELECT COALESCE(MAX(seq_no), -1) FROM stream_items WHERE thread_id = :threadId")
    suspend fun maxSeqNoForThread(threadId: String): Long

    @Query("UPDATE stream_items SET pinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: String, pinned: Boolean)

    @Query(
        """
        SELECT * FROM stream_items
         WHERE pinned = 1 AND kind = 'text'
         ORDER BY seq_no DESC
         LIMIT :limit
        """
    )
    suspend fun pinnedTexts(limit: Int = 20): List<StreamItemEntity>

    @Query("SELECT DISTINCT tags FROM stream_items WHERE tags IS NOT NULL AND tags != ''")
    suspend fun distinctTagBlobs(): List<String>
}
